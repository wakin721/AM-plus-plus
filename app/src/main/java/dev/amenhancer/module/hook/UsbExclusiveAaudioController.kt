package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental AAudio takeover for Apple Music media AudioTracks.
 *
 * Safety rule: never suppress the original AudioTrack until a supported Java
 * AudioTrack.write(...) path has actually been observed and an AAudio session
 * already owns that exact track. One successful original write proves the Java
 * PCM path; takeover happens before the following write so that buffer can
 * prefill AAudio instead of being flushed or duplicated during the transition.
 */
internal object UsbExclusiveAaudioController {
    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val internalTransition = ThreadLocal.withInitial { false }

    private var session: Session? = null
    private var latestTrack: WeakReference<AudioTrack>? = null
    private var observedTrack: WeakReference<AudioTrack>? = null
    private var failedTrack: WeakReference<AudioTrack>? = null
    private var lastFailure: String? = null
    private val trackVolumes = WeakHashMap<AudioTrack, StereoGain>()
    private val observedMediaVolumeIndex = AtomicInteger(UNKNOWN_VOLUME_INDEX)

    fun configure(isEnabled: Boolean) {
        enabled.set(isEnabled)
        if (!isEnabled) {
            synchronized(lock) {
                closeSessionLocked()
                trackVolumes.clear()
            }
            observedTrack = null
            failedTrack = null
            lastFailure = null
            observedMediaVolumeIndex.set(UNKNOWN_VOLUME_INDEX)
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    fun isInternalTransition(): Boolean = internalTransition.get() == true

    fun isActive(track: AudioTrack): Boolean = synchronized(lock) {
        session?.track?.get() === track
    }

    /** Receives the media step selected by Android's system volume UI. */
    fun onSystemMediaVolumeChanged(volumeIndex: Int) {
        if (!enabled.get() || volumeIndex < 0) return
        observedMediaVolumeIndex.set(volumeIndex)
    }

    /**
     * Returns true only for a redundant play() on the active owner. A different
     * track first closes the stale exclusive queue, then keeps its original
     * play() path until the Java PCM write seam has been proven.
     */
    fun beforePlay(track: AudioTrack): Boolean {
        if (!enabled.get() || internalTransition.get() == true) return false
        if (!track.isMediaTrack()) return false
        latestTrack = WeakReference(track)
        return synchronized(lock) {
            val owner = session?.track?.get()
            if (owner != null && owner !== track) {
                // A new song can start before Apple Music releases the previous
                // AudioTrack. Drop the old exclusive queue before new PCM plays.
                closeSessionLocked()
            }
            session?.track?.get() === track
        }
    }

    /** Intercepts one supported write when an exclusive AAudio session owns this track. */
    fun interceptWrite(context: Context, track: AudioTrack, args: Array<Any?>): Int? {
        if (!enabled.get() || internalTransition.get() == true) return null
        if (!track.isMediaTrack()) return null

        var active = synchronized(lock) { session?.takeIf { it.track.get() === track } }
        if (active == null) {
            if (observedTrack?.get() !== track || failedTrack?.get() === track) return null
            if (!isSupportedWrite(track, args)) return null
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
            val manager = context.getSystemService(AudioManager::class.java) ?: return null
            val routed = runCatching { track.routedDevice }.getOrNull()
                ?.takeIf { it.isUsbAudioOutput() }
                ?: return null
            hotTakeover(track, routed, manager)
            active = synchronized(lock) { session?.takeIf { it.track.get() === track } }
                ?: return null
        }

        return synchronized(lock) {
            val owned = session?.takeIf { it === active && it.track.get() === track }
                ?: return@synchronized null
            val gains = effectiveGains(owned)
            val written = when (val data = args.firstOrNull()) {
                is FloatArray -> {
                    if (track.format.encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                        return failWrite(track, "FloatArray write 与当前 AudioTrack encoding 不一致")
                    }
                    val offset = args.getOrNull(1) as? Int
                        ?: return failWrite(track, "FloatArray write 缺少 offset")
                    val size = args.getOrNull(2) as? Int
                        ?: return failWrite(track, "FloatArray write 缺少 size")
                    UsbExclusiveAaudioBridge.writeFloats(
                        owned.handle,
                        data,
                        offset,
                        size,
                        blocking = writeModeForArray(args) != AudioTrack.WRITE_NON_BLOCKING,
                        gainLeft = gains.left,
                        gainRight = gains.right,
                    )
                }
                is ShortArray -> {
                    if (track.format.encoding != AudioFormat.ENCODING_PCM_16BIT) {
                        return failWrite(track, "ShortArray write 与当前 AudioTrack encoding 不一致")
                    }
                    val offset = args.getOrNull(1) as? Int
                        ?: return failWrite(track, "ShortArray write 缺少 offset")
                    val size = args.getOrNull(2) as? Int
                        ?: return failWrite(track, "ShortArray write 缺少 size")
                    UsbExclusiveAaudioBridge.writeShorts(
                        owned.handle,
                        data,
                        offset,
                        size,
                        blocking = writeModeForArray(args) != AudioTrack.WRITE_NON_BLOCKING,
                        gainLeft = gains.left,
                        gainRight = gains.right,
                    )
                }
                is ByteArray -> {
                    val offset = args.getOrNull(1) as? Int
                        ?: return failWrite(track, "ByteArray write 缺少 offset")
                    val size = args.getOrNull(2) as? Int
                        ?: return failWrite(track, "ByteArray write 缺少 size")
                    UsbExclusiveAaudioBridge.writeBytes(
                        owned.handle,
                        data,
                        offset,
                        size,
                        blocking = writeModeForArray(args) != AudioTrack.WRITE_NON_BLOCKING,
                        gainLeft = gains.left,
                        gainRight = gains.right,
                    )
                }
                is ByteBuffer -> writeByteBuffer(owned, track, data, args, gains)
                else -> return failWrite(
                    track,
                    "Apple Music 切换到了当前实验版本无法接管的 AudioTrack.write 重载",
                )
            }

            if (written < 0) {
                return failWrite(track, UsbExclusiveAaudioBridge.lastError("AAudio PCM 写入失败"))
            }
            if (written > 0) {
                session?.takeIf { it.track.get() === track }?.hasWrittenPcm = true
            }
            written
        }
    }

    /** Observe a successful original write; the next write is the lossless takeover boundary. */
    fun afterOriginalWrite(
        track: AudioTrack,
        args: Array<Any?>,
        result: Any?,
    ) {
        if (!enabled.get() || internalTransition.get() == true) return
        if (!track.isMediaTrack()) return
        if (result !is Int || result <= 0) return
        if (!isSupportedWrite(track, args)) return
        if (failedTrack?.get() === track) return

        latestTrack = WeakReference(track)
        observedTrack = WeakReference(track)
    }

    /** Mirrors AudioTrack's app-level fades because the original track is paused during takeover. */
    fun afterVolumeChange(
        track: AudioTrack,
        operation: String,
        args: Array<Any?>,
        result: Any?,
    ) {
        if (!enabled.get()) return
        if (result is Int && result != AudioTrack.SUCCESS) return
        val updated = when (operation) {
            "setVolume" -> (args.firstOrNull() as? Float)?.let { gain ->
                StereoGain(gain.coerceIn(0f, 1f), gain.coerceIn(0f, 1f))
            }
            "setStereoVolume" -> {
                val left = args.getOrNull(0) as? Float
                val right = args.getOrNull(1) as? Float
                if (left != null && right != null) {
                    StereoGain(left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
                } else {
                    null
                }
            }
            else -> null
        } ?: return
        synchronized(lock) { trackVolumes[track] = updated }
    }

    fun onTransportControl(track: AudioTrack, operation: String) {
        if (internalTransition.get() == true) return
        synchronized(lock) {
            if (session?.track?.get() === track) closeSessionLocked()
        }
        if (operation == "pause" || operation == "stop" || operation == "flush") {
            if (failedTrack?.get() === track) {
                failedTrack = null
                lastFailure = null
            }
        }
        if (operation == "release") {
            synchronized(lock) { trackVolumes.remove(track) }
            if (observedTrack?.get() === track) observedTrack = null
            if (failedTrack?.get() === track) failedTrack = null
            if (latestTrack?.get() === track) latestTrack = null
            lastFailure = null
        }
    }

    fun currentStatus(context: Context): UsbBitPerfectStatusDetails? {
        if (!enabled.get()) return null

        synchronized(lock) {
            val active = session
            val track = active?.track?.get()
            if (active != null && track != null) {
                return statusFor(
                    state = if (active.hasWrittenPcm) {
                        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE
                    } else {
                        UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED
                    },
                    track = track,
                    deviceName = active.deviceName,
                    mixerFormat = track.format,
                    message = if (active.hasWrittenPcm) {
                        "AAudio 已核验 sharingMode=EXCLUSIVE、USB deviceId 与 AudioTrack 格式，且 PCM 已实际写入独占流。"
                    } else {
                        "AAudio 已核验 sharingMode=EXCLUSIVE、USB deviceId 与 AudioTrack 格式；等待下一批 PCM 写入。"
                    },
                )
            }
        }

        val track = latestTrack?.get()
            ?: return UsbBitPerfectStatusDetails(
                state = UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED,
                message = "实验性 AAudio 独占已启用，等待 Apple Music 创建媒体 AudioTrack。",
            )
        val manager = context.getSystemService(AudioManager::class.java)
        val device = manager?.let { resolveVisibleUsbDevice(it, track) }
        val failure = lastFailure.takeIf { failedTrack?.get() === track }
        if (failure != null) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_FALLBACK,
                track = track,
                deviceName = device?.productName?.toString(),
                message = "AAudio 独占未建立：$failure。已恢复原 AudioTrack，继续使用 Bit-Perfect/系统输出回退路径。",
            )
        }
        if (!UsbExclusiveAaudioBridge.supportsEncoding(track.format.encoding)) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_UNSUPPORTED_PATH,
                track = track,
                deviceName = device?.productName?.toString(),
                message = "当前 AudioTrack PCM encoding 暂不支持实验性 AAudio 接管，因此保持原输出。",
            )
        }
        if (observedTrack?.get() === track) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_READY,
                track = track,
                deviceName = device?.productName?.toString(),
                message = if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    "已观察到可接管的 Java AudioTrack.write PCM；若当前 USB 路由允许，将在安全点尝试 AAudio EXCLUSIVE。"
                } else {
                    "已观察到可接管的 Java AudioTrack.write PCM；恢复播放后将尝试 AAudio EXCLUSIVE。"
                },
            )
        }
        return statusFor(
            state = UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED,
            track = track,
            deviceName = device?.productName?.toString(),
            message = "等待观察可接管的 Java AudioTrack.write PCM。若 Apple Music 使用 native/共享缓冲写入，本实验模式不会强行静音原输出。",
        )
    }

    private fun hotTakeover(
        track: AudioTrack,
        device: AudioDeviceInfo,
        manager: AudioManager,
    ) {
        internalTransition.set(true)
        try {
            synchronized(lock) {
                if (session?.track?.get() === track || failedTrack?.get() === track) return
                // Apple Music can start the next AudioTrack before releasing the old
                // one. Release that stale exclusive owner before opening the new format.
                closeSessionLocked()

                val paused = runCatching {
                    track.pause()
                    true
                }.getOrElse { error ->
                    markFailure(track, "暂停原 AudioTrack 失败：${error.message ?: error.javaClass.simpleName}")
                    false
                }
                if (!paused) return
                runCatching { track.flush() }
                if (!startExclusive(track, device, manager)) {
                    runCatching { track.play() }
                        .onFailure { error ->
                            ModernXposedRuntime.log("usb_exclusive: fallback play failed", error)
                        }
                }
            }
        } finally {
            internalTransition.set(false)
        }
    }

    private fun startExclusive(
        track: AudioTrack,
        device: AudioDeviceInfo,
        manager: AudioManager,
    ): Boolean {
        val opened = UsbExclusiveAaudioBridge.open(device.id, track.format)
        return when (opened) {
            is UsbExclusiveAaudioBridge.OpenResult.Failed -> {
                markFailure(track, opened.reason)
                ModernXposedRuntime.log(
                    "usb_exclusive: open failed on ${device.productName}: ${opened.reason}",
                )
                false
            }
            is UsbExclusiveAaudioBridge.OpenResult.Opened -> {
                synchronized(lock) {
                    closeSessionLocked()
                    session = Session(
                        track = WeakReference(track),
                        handle = opened.handle,
                        deviceId = device.id,
                        deviceType = device.type,
                        deviceName = device.productName?.toString()?.takeIf(String::isNotBlank),
                        audioManager = manager,
                    )
                    failedTrack = null
                    lastFailure = null
                }
                ModernXposedRuntime.log(
                    "usb_exclusive: configured ${track.format.sampleRate}Hz " +
                        "encoding=${track.format.encoding} channels=${track.format.channelCount} " +
                        "deviceId=${device.id} ${device.productName}",
                )
                true
            }
        }
    }

    /**
     * Returning null makes the Xposed interceptor call the original write().
     * Resume the original AudioTrack first so a native failure is fail-open.
     */
    private fun failWrite(track: AudioTrack, reason: String): Int? {
        synchronized(lock) {
            if (session?.track?.get() === track) closeSessionLocked()
        }
        markFailure(track, reason)
        internalTransition.set(true)
        try {
            runCatching { track.play() }
                .onFailure { error -> ModernXposedRuntime.log("usb_exclusive: write fallback play failed", error) }
        } finally {
            internalTransition.set(false)
        }
        return null
    }

    private fun writeByteBuffer(
        active: Session,
        track: AudioTrack,
        data: ByteBuffer,
        args: Array<Any?>,
        gains: StereoGain,
    ): Int {
        val sizeBytes = args.getOrNull(1) as? Int ?: return -1
        if (sizeBytes < 0 || sizeBytes > data.remaining()) return -1
        val bytesPerSample = UsbExclusiveAaudioBridge.bytesPerSample(track.format.encoding) ?: return -1
        val bytesPerFrame = bytesPerSample * track.format.channelCount
        if (bytesPerFrame <= 0 || sizeBytes % bytesPerFrame != 0) return -1
        val copy = ByteArray(sizeBytes)
        data.duplicate().get(copy)
        val written = UsbExclusiveAaudioBridge.writeBytes(
            active.handle,
            copy,
            0,
            sizeBytes,
            blocking = (args.getOrNull(2) as? Int) != AudioTrack.WRITE_NON_BLOCKING,
            gainLeft = gains.left,
            gainRight = gains.right,
        )
        if (written > 0) data.position(data.position() + written)
        return written
    }

    private fun isSupportedWrite(track: AudioTrack, args: Array<Any?>): Boolean {
        if (!UsbExclusiveAaudioBridge.supportsEncoding(track.format.encoding)) return false
        return when (args.firstOrNull()) {
            is FloatArray -> track.format.encoding == AudioFormat.ENCODING_PCM_FLOAT && args.size >= 4
            is ShortArray -> track.format.encoding == AudioFormat.ENCODING_PCM_16BIT && args.size >= 3
            is ByteArray -> args.size >= 3
            is ByteBuffer -> args.size == 3
            else -> false
        }
    }

    private fun writeModeForArray(args: Array<Any?>): Int =
        (args.getOrNull(3) as? Int) ?: AudioTrack.WRITE_BLOCKING

    private fun effectiveGains(active: Session): StereoGain {
        val manager = active.audioManager
        val queriedIndex = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?: return combineWithTrackGain(active, active.streamGain)
        val maximum = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?: return combineWithTrackGain(active, active.streamGain)
        val observedIndex = observedMediaVolumeIndex.get()
        val index = if (observedIndex in 0..maximum) observedIndex else queriedIndex
        val muted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }
            .getOrDefault(index <= 0)
        val db = runCatching {
            manager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, active.deviceType)
        }.getOrNull()
        val streamGain = UsbExclusiveVolumePolicy.streamGain(index, maximum, muted, db)
        active.streamGain = streamGain
        return combineWithTrackGain(active, streamGain)
    }

    private fun combineWithTrackGain(active: Session, streamGain: Float): StereoGain {
        val trackGain = trackVolumes[active.track.get()] ?: StereoGain.FULL
        return StereoGain(
            left = UsbExclusiveVolumePolicy.effectiveGain(streamGain, trackGain.left),
            right = UsbExclusiveVolumePolicy.effectiveGain(streamGain, trackGain.right),
        )
    }

    private fun resolveVisibleUsbDevice(manager: AudioManager, track: AudioTrack): AudioDeviceInfo? {
        runCatching { track.routedDevice }.getOrNull()
            ?.takeIf { it.isUsbAudioOutput() }
            ?.let { return it }
        runCatching { track.preferredDevice }.getOrNull()
            ?.takeIf { it.isUsbAudioOutput() }
            ?.let { return it }
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isUsbAudioOutput() }
            .singleOrNull()
    }

    private fun markFailure(track: AudioTrack, reason: String) {
        failedTrack = WeakReference(track)
        lastFailure = reason
    }

    private fun closeSessionLocked() {
        session?.let { UsbExclusiveAaudioBridge.close(it.handle) }
        session = null
    }

    private fun statusFor(
        state: String,
        track: AudioTrack,
        deviceName: String?,
        mixerFormat: AudioFormat? = null,
        message: String,
    ): UsbBitPerfectStatusDetails = UsbBitPerfectStatusDetails(
        state = state,
        deviceName = deviceName?.takeIf(String::isNotBlank),
        trackSampleRate = track.format.sampleRate,
        trackEncoding = track.format.encoding,
        trackChannels = track.format.channelCount,
        mixerSampleRate = mixerFormat?.sampleRate ?: 0,
        mixerEncoding = mixerFormat?.encoding ?: 0,
        mixerChannels = mixerFormat?.channelCount ?: 0,
        message = message,
    )

    private fun AudioTrack.isMediaTrack(): Boolean =
        audioAttributes.usage == AudioAttributes.USAGE_MEDIA

    private fun AudioDeviceInfo.isUsbAudioOutput(): Boolean = isSink && when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> true
        else -> false
    }

    private data class Session(
        val track: WeakReference<AudioTrack>,
        val handle: Long,
        val deviceId: Int,
        val deviceType: Int,
        val deviceName: String?,
        val audioManager: AudioManager,
        var streamGain: Float = 1f,
        var hasWrittenPcm: Boolean = false,
    )

    private data class StereoGain(val left: Float, val right: Float) {
        companion object {
            val FULL = StereoGain(1f, 1f)
        }
    }

    private const val UNKNOWN_VOLUME_INDEX = -1
}
