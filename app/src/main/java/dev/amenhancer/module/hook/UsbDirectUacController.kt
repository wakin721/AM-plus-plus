package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Build
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental user-space USB Audio takeover.
 *
 * The controller is intentionally fail-open. Apple Music keeps using its
 * original AudioTrack until a supported Java write path has been observed and
 * the native engine has successfully claimed a permission-backed UAC streaming
 * interface. Any broker/native/write failure releases the USB interface and
 * resumes the original AudioTrack so Android's normal audio path can continue.
 */
internal object UsbDirectUacController {
    private const val TRACK_HANDOFF_IDLE_NANOS = 1_000_000_000L
    private const val TRACK_HANDOFF_STARTUP_GRACE_NANOS = 2_000_000_000L
    private const val PRODUCER_STALL_TIMEOUT_NANOS = 30_000_000_000L

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val internalTransition = ThreadLocal.withInitial { false }

    private var session: Session? = null
    private var latestTrack: WeakReference<AudioTrack>? = null
    private var observedTrack: WeakReference<AudioTrack>? = null
    private var pendingTakeover: PendingTakeover? = null
    private var failedTrack: WeakReference<AudioTrack>? = null
    private var lastFailure: Failure? = null
    private var applicationContext: Context? = null
    private val trackVolumes = WeakHashMap<AudioTrack, StereoGain>()

    @Volatile private var pcmBufferMs: Int = 500
    @Volatile private var transferBufferMs: Int = 0

    fun configure(
        isEnabled: Boolean,
        pcmBufferMs: Int = 500,
        transferBufferMs: Int = 0,
    ) {
        synchronized(lock) {
            enabled.set(isEnabled)
            this.pcmBufferMs = pcmBufferMs
            this.transferBufferMs = transferBufferMs
            if (!isEnabled) {
                val context = applicationContext
                pendingTakeover = null
                closeSessionLocked()
                trackVolumes.clear()
                if (context != null) UsbDirectDeviceClient.release(context)
                observedTrack = null
                failedTrack = null
                lastFailure = null
                applicationContext = null
            }
        }
        UsbDirectSystemVolumeObserver.syncPolling()
    }

    fun isEnabled(): Boolean = enabled.get()

    /** Called by the existing process-local poller; never keep orphaned silence alive. */
    fun checkPlaybackHealth(nowRealtimeNanos: Long = System.nanoTime()) {
        synchronized(lock) {
            val active = session ?: return
            val track = active.track.get()
            if (track == null) {
                closeSessionLocked()
                UsbDirectDeviceClient.release(active.context, active.lease)
                return
            }
            if (active.suspended) return
            if (!active.keepAlive.hasSafeRoute()) {
                failWrite(track, active, "系统静音轨道偏离内置扬声器，已恢复系统输出")
                return
            }
            val lastPcm = active.lastWriteRealtimeNanos.takeIf { it > 0L } ?: active.ownerStartedRealtimeNanos
            if (nowRealtimeNanos - lastPcm >= PRODUCER_STALL_TIMEOUT_NANOS) {
                ModernXposedRuntime.log("usb_direct: PCM producer stalled; restoring system playback")
                failWrite(track, active, "30 秒未收到新音频数据，已恢复系统输出")
            }
        }
    }

    fun isInternalTransition(): Boolean = internalTransition.get() == true

    internal fun <T> withInternalTransition(operation: () -> T): T {
        val previous = internalTransition.get()
        internalTransition.set(true)
        return try { operation() } finally { internalTransition.set(previous) }
    }

    fun isActive(track: AudioTrack): Boolean = synchronized(lock) {
        session?.track?.get() === track
    }

    /** The original track is internally paused; expose the transport owned by USB instead. */
    fun playbackState(track: AudioTrack): Int? = synchronized(lock) {
        if (isInternalTransition()) return null
        val active = session?.takeIf { it.track.get() === track }
        if (active == null) {
            return if (pendingTransport(track) != null) AudioTrack.PLAYSTATE_PLAYING else null
        }
        if (active.suspended) AudioTrack.PLAYSTATE_PAUSED else AudioTrack.PLAYSTATE_PLAYING
    }

    fun playbackHeadPosition(track: AudioTrack): Int? = synchronized(lock) {
        if (isInternalTransition()) return null
        val active = session?.takeIf { it.track.get() === track }
            ?: return pendingTransport(track)?.positionBaseFrames?.toInt()
        val position = UsbDirectUacBridge.playbackPosition(active.handle) ?: return null
        (active.positionBaseFrames + position[0]).toInt()
    }

    fun playbackTimestamp(track: AudioTrack, timestamp: AudioTimestamp): Boolean? = synchronized(lock) {
        if (isInternalTransition()) return null
        val active = session?.takeIf { it.track.get() === track }
            ?: return if (pendingTransport(track) != null) false else null
        val position = UsbDirectUacBridge.playbackPosition(active.handle) ?: return false
        if (position[1] <= 0L) return false
        timestamp.framePosition = (active.positionBaseFrames + position[0]) and 0xFFFF_FFFFL
        timestamp.nanoTime = position[1]
        true
    }

    /** Called only under the controller lock; cancellation immediately restores raw queries. */
    private fun pendingTransport(track: AudioTrack): PendingTakeover? =
        pendingTakeover?.takeIf { it.originalPaused && it.track.get() === track }

    /** Receives the media step selected by Android's system volume UI. */
    fun onSystemMediaVolumeChanged(volumeIndex: Int) {
        if (!enabled.get() || volumeIndex < 0) return
        val active = synchronized(lock) { session } ?: return
        val streamGain = querySystemMediaGain(
            active.audioManager,
            active.deviceType,
            preferredIndex = volumeIndex,
        ) ?: return
        active.streamGainCache.refresh { streamGain }
    }

    /** Suppress only redundant play() calls after USB Direct already owns the track. */
    fun beforePlay(context: Context, track: AudioTrack): Boolean {
        if (!enabled.get() || isInternalTransition()) return false
        if (!track.isMediaTrack()) return false
        applicationContext = context.applicationContext
        latestTrack = WeakReference(track)
        return synchronized(lock) {
            val active = session?.takeIf { it.track.get() === track }
                ?: return pendingTransport(track) != null
            active.suspended = false
            if (!active.keepAlive.start()) {
                failWrite(track, active, "无法恢复系统后台播放状态")
                return@synchronized false
            }
            UsbDirectUacBridge.resume(active.handle)
            active.lastWriteRealtimeNanos = System.nanoTime()
            active.playbackPower.keepAwake()
            true
        }
    }

    /** Intercept a Java AudioTrack.write only after the direct usbfs engine owns the track. */
    fun interceptWrite(track: AudioTrack, args: Array<Any?>): Int? {
        if (!enabled.get() || isInternalTransition()) return null
        val active = synchronized(lock) {
            session?.takeIf { it.track.get() === track }
        } ?: return null
        // The native ring retains writes while suspended and applies its normal
        // capacity/backpressure. Reporting success here without queuing loses PCM.
        val gains = effectiveGains(active)

        val written = when (val data = args.firstOrNull()) {
            is FloatArray -> {
                if (track.format.encoding != AudioFormat.ENCODING_PCM_FLOAT) {
                    return failWrite(track, active, "FloatArray write 与 AudioTrack encoding 不一致")
                }
                val offset = args.getOrNull(1) as? Int
                    ?: return failWrite(track, active, "FloatArray write 缺少 offset")
                val size = args.getOrNull(2) as? Int
                    ?: return failWrite(track, active, "FloatArray write 缺少 size")
                UsbDirectUacBridge.writeFloats(
                    active.handle,
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
                    return failWrite(track, active, "ShortArray write 与 AudioTrack encoding 不一致")
                }
                val offset = args.getOrNull(1) as? Int
                    ?: return failWrite(track, active, "ShortArray write 缺少 offset")
                val size = args.getOrNull(2) as? Int
                    ?: return failWrite(track, active, "ShortArray write 缺少 size")
                UsbDirectUacBridge.writeShorts(
                    active.handle,
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
                    ?: return failWrite(track, active, "ByteArray write 缺少 offset")
                val size = args.getOrNull(2) as? Int
                    ?: return failWrite(track, active, "ByteArray write 缺少 size")
                UsbDirectUacBridge.writeBytes(
                    active.handle,
                    data,
                    offset,
                    size,
                    blocking = writeModeForArray(args) != AudioTrack.WRITE_NON_BLOCKING,
                    gainLeft = gains.left,
                    gainRight = gains.right,
                )
            }

            is ByteBuffer -> writeByteBuffer(active, track, data, args, gains)
            else -> return failWrite(
                track,
                active,
                "Apple Music 切换到当前 USB Direct 原型无法接管的 AudioTrack.write 重载",
            )
        }

        if (written < 0) {
            return failWrite(
                track,
                active,
                UsbDirectUacBridge.lastError("USB isochronous PCM 写入失败"),
            )
        }
        if (written > 0) {
            synchronized(lock) {
                session?.takeIf { it.track.get() === track }?.apply {
                    hasWrittenPcm = true
                    lastWriteRealtimeNanos = System.nanoTime()
                    if (!suspended) playbackPower.keepAwake()
                }
            }
        }
        return written
    }

    /** Mirrors AudioTrack's app-level fades after the original track is paused. */
    fun afterVolumeChange(
        track: AudioTrack,
        operation: String,
        args: Array<Any?>,
        result: Any?,
    ) {
        if (!enabled.get() || isInternalTransition()) return
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

    /**
     * Observe one successful original write before claiming USB. The first
     * buffer is deliberately allowed through Android so unsupported devices
     * never become silent merely because the experimental switch is enabled.
     */
    fun afterOriginalWrite(
        context: Context,
        track: AudioTrack,
        args: Array<Any?>,
        result: Any?,
    ) {
        if (!enabled.get() || isInternalTransition()) return
        if (!track.isMediaTrack()) return
        if (result !is Int || result <= 0) return
        applicationContext = context.applicationContext
        latestTrack = WeakReference(track)

        if (!isSupportedWrite(track, args)) {
            markFailure(
                track,
                "当前 Java AudioTrack.write/PCM encoding 不受 USB Direct 原型支持",
                FailureKind.UNSUPPORTED,
            )
            return
        }
        if (failedTrack?.get() === track) return
        observedTrack = WeakReference(track)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return

        val now = System.nanoTime()
        synchronized(lock) {
            if (!enabled.get() || pendingTakeover != null) return
            val active = session
            if (active != null) {
                val existingTrack = active.track.get()
                val sameTrack = existingTrack === track
                val existingTrackIdle = UsbDirectTrackHandoffPolicy.isIdleOwner(
                    ownerStartedRealtimeNanos = active.ownerStartedRealtimeNanos,
                    lastWriteRealtimeNanos = active.lastWriteRealtimeNanos,
                    nowRealtimeNanos = now,
                    idleThresholdNanos = TRACK_HANDOFF_IDLE_NANOS,
                    startupGraceNanos = TRACK_HANDOFF_STARTUP_GRACE_NANOS,
                )
                if (!UsbDirectTrackHandoffPolicy.shouldHandoff(
                        sameTrack = sameTrack,
                        suspended = active.suspended,
                        existingTrackAlive = existingTrack != null,
                        existingTrackIdle = existingTrackIdle,
                    )
                ) {
                    return
                }
                closeSessionLocked()
                UsbDirectDeviceClient.release(active.context, active.lease)
            }
            val request = PendingTakeover(WeakReference(track))
            pendingTakeover = request
            // Serialize acquisition and cancellation with transport controls.
            // Broker callbacks run outside the client's lock.
            val started = UsbDirectDeviceClient.acquire(context, track.format) { resultValue ->
                val currentTrack = request.track.get()
                when (resultValue) {
                    is UsbDirectDeviceClient.AcquireResult.Failed -> synchronized(lock) {
                        if (pendingTakeover !== request) return@acquire
                        pendingTakeover = null
                        if (currentTrack != null && enabled.get()) {
                            markFailure(
                                currentTrack,
                                resultValue.reason,
                                classifyFailure(resultValue.reason),
                            )
                            ModernXposedRuntime.log("usb_direct: broker acquire failed: ${resultValue.reason}")
                        }
                    }
                    is UsbDirectDeviceClient.AcquireResult.Acquired -> {
                        if (currentTrack == null) {
                            synchronized(lock) {
                                if (pendingTakeover === request) pendingTakeover = null
                            }
                            UsbDirectDeviceClient.release(context, resultValue.lease)
                        } else {
                            hotTakeover(context, currentTrack, resultValue.lease, request)
                        }
                    }
                }
            }
            if (!started && pendingTakeover === request) {
                pendingTakeover = null
            }
        }
    }

    fun onTransportControl(context: Context, track: AudioTrack, operation: String) {
        if (isInternalTransition()) return

        synchronized(lock) {
            val active = session?.takeIf { it.track.get() === track }
            val ownsPending = pendingTakeover?.track?.get() === track

            when (UsbDirectTrackHandoffPolicy.actionFor(operation)) {
                UsbDirectTrackHandoffAction.SUSPEND -> {
                    if (active != null) {
                        active.suspended = true
                        active.hasWrittenPcm = false
                        UsbDirectUacBridge.suspend(active.handle)
                        active.keepAlive.pause()
                        active.playbackPower.release()
                    }
                }

                UsbDirectTrackHandoffAction.FLUSH -> {
                    if (active != null) {
                        active.hasWrittenPcm = false
                        active.positionBaseFrames = 0L
                        UsbDirectUacBridge.flush(active.handle)
                    }
                }

                UsbDirectTrackHandoffAction.CLOSE -> {
                    if (active != null) {
                        closeSessionLocked()
                        UsbDirectDeviceClient.release(active.context, active.lease)
                    }
                }
            }

            // Keep the request pending through nativeOpen so a transport change
            // also cancels a takeover whose broker response has already arrived.
            if (ownsPending) {
                pendingTakeover = null
                UsbDirectDeviceClient.release(context)
            }

            if (operation == "pause" || operation == "stop" || operation == "flush") {
                if (failedTrack?.get() === track) {
                    failedTrack = null
                    lastFailure = null
                }
            }
            if (operation == "release") {
                trackVolumes.remove(track)
                if (observedTrack?.get() === track) observedTrack = null
                if (failedTrack?.get() === track) failedTrack = null
                if (latestTrack?.get() === track) latestTrack = null
                lastFailure = null
            }
        }
    }

    fun currentStatus(): UsbBitPerfectStatusDetails? {
        if (!enabled.get()) return null

        synchronized(lock) {
            val active = session
            val track = active?.track?.get()
            if (active != null && track != null) {
                return statusFor(
                    state = if (!active.suspended && active.hasWrittenPcm) {
                        UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE
                    } else {
                        UsbBitPerfectStatusProtocol.STATE_DIRECT_CONFIGURED
                    },
                    track = track,
                    lease = active.lease,
                    message = buildString {
                        append("USB AudioStreaming interface ${active.lease.interfaceNumber}")
                        append(" alt ${active.lease.alternateSetting} 已由 AM++ 独占 claim；")
                        append("EP 0x${active.lease.endpointAddress.toString(16)} 使用 native usbfs isochronous OUT")
                        if (active.hasWrittenPcm) append("，PCM 已实际进入 USB URB。")
                        else append("，等待下一批 PCM。")
                    },
                )
            }
            val pending = pendingTakeover?.track?.get()
            if (pending != null) {
                return statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_DIRECT_ACQUIRING,
                    track = pending,
                    message = "正在向 AM++ USB broker 请求已授权 DAC，并尝试 claim AudioStreaming interface。",
                )
            }
        }

        val track = latestTrack?.get()
            ?: return UsbBitPerfectStatusDetails(
                state = UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED,
                message = "USB 直通已启用，等待 Apple Music 创建媒体 AudioTrack。",
            )
        val failure = lastFailure.takeIf { failedTrack?.get() === track }
        if (failure != null) {
            return statusFor(
                state = when (failure.kind) {
                    FailureKind.PERMISSION -> UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED
                    FailureKind.UNSUPPORTED -> UsbBitPerfectStatusProtocol.STATE_DIRECT_UNSUPPORTED_DEVICE
                    FailureKind.OTHER -> UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK
                },
                track = track,
                message = "USB Direct 未建立：${failure.reason}。已保留/恢复原 AudioTrack，可继续使用 Android 系统输出。",
            )
        }
        if (observedTrack?.get() === track) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED,
                track = track,
                message = "已观察到可接管的 Java PCM，等待播放状态/USB broker 条件满足后尝试直通。",
            )
        }
        return statusFor(
            state = UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED,
            track = track,
            message = "等待可接管的 Java AudioTrack.write PCM；native/shared-buffer 写入不会被本原型强行静音。",
        )
    }

    private fun hotTakeover(
        context: Context,
        track: AudioTrack,
        lease: UsbDirectDeviceClient.Lease,
        request: PendingTakeover,
    ) {
        internalTransition.set(true)
        try {
            val manager: AudioManager
            val deviceType: Int
            val positionBaseFrames: Long
            synchronized(lock) {
                if (pendingTakeover !== request || !enabled.get() || session != null ||
                    failedTrack?.get() === track ||
                    runCatching { track.playState }.getOrNull() != AudioTrack.PLAYSTATE_PLAYING
                ) {
                    if (pendingTakeover === request) pendingTakeover = null
                    UsbDirectDeviceClient.release(context, lease)
                    return
                }
                manager = context.getSystemService(AudioManager::class.java) ?: run {
                    pendingTakeover = null
                    markFailure(track, "无法读取系统媒体音量", FailureKind.OTHER)
                    UsbDirectDeviceClient.release(context, lease)
                    return
                }
                deviceType = runCatching { track.routedDevice?.type }
                    .getOrNull()
                    ?: AudioDeviceInfo.TYPE_USB_DEVICE
                positionBaseFrames = runCatching { track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL }
                    .getOrDefault(0L)
                val paused = runCatching {
                    track.pause()
                    true
                }.getOrElse { error ->
                    markFailure(
                        track,
                        "暂停原 AudioTrack 失败：${error.message ?: error.javaClass.simpleName}",
                        FailureKind.OTHER,
                    )
                    false
                }
                if (!paused) {
                    pendingTakeover = null
                    UsbDirectDeviceClient.release(context, lease)
                    return
                }
                request.positionBaseFrames = positionBaseFrames
                request.originalPaused = true
                runCatching { track.flush() }
            }

            // Do not hold the controller lock over native USB claim/control I/O.
            // pause/flush/stop/release can invalidate this request while it opens.
            when (
                val opened = UsbDirectUacBridge.open(
                    context = context,
                    lease = lease,
                    pcmBufferMs = pcmBufferMs,
                    transferBufferMs = transferBufferMs,
                )
            ) {
                is UsbDirectUacBridge.OpenResult.Failed -> {
                    synchronized(lock) {
                        if (pendingTakeover === request && enabled.get()) {
                            pendingTakeover = null
                            markFailure(track, opened.reason, classifyFailure(opened.reason))
                            UsbDirectDeviceClient.release(context, lease)
                            runCatching { track.play() }
                                .onFailure { error -> ModernXposedRuntime.log("usb_direct: fallback play failed", error) }
                        } else {
                            UsbDirectDeviceClient.release(context, lease)
                        }
                    }
                }

                is UsbDirectUacBridge.OpenResult.Opened -> {
                    val streamGain = querySystemMediaGain(manager, deviceType) ?: 0f
                    var keepAliveFailed = false
                    val installed = synchronized(lock) {
                        if (pendingTakeover !== request || !enabled.get() || session != null) {
                            false
                        } else {
                            val nextSession = Session(
                                track = WeakReference(track),
                                handle = opened.handle,
                                lease = lease,
                                context = context.applicationContext,
                                audioManager = manager,
                                deviceType = deviceType,
                                streamGainCache = UsbDirectVolumeCache(streamGain),
                                positionBaseFrames = positionBaseFrames,
                            )
                            if (!nextSession.keepAlive.start()) {
                                keepAliveFailed = true
                                pendingTakeover = null
                                markFailure(track, "无法建立系统后台播放状态", FailureKind.OTHER)
                                return@synchronized false
                            }
                            session = nextSession
                            session?.playbackPower?.keepAwake()
                            pendingTakeover = null
                            failedTrack = null
                            lastFailure = null
                            true
                        }
                    }
                    if (!installed) {
                        UsbDirectUacBridge.close(opened.handle)
                        UsbDirectDeviceClient.release(context, lease)
                        if (keepAliveFailed) synchronized(lock) {
                            if (enabled.get() && session == null && failedTrack?.get() === track) {
                                runCatching { track.play() }
                                    .onFailure { ModernXposedRuntime.log("usb_direct: keep-alive fallback play failed", it) }
                            }
                        }
                        return
                    }
                    ModernXposedRuntime.log(
                        "usb_direct: configured ${lease.sampleRate}Hz ${lease.bitResolution}-bit/" +
                            "${lease.channels}ch if=${lease.interfaceNumber}:${lease.alternateSetting} " +
                            "ep=0x${lease.endpointAddress.toString(16)} ${lease.deviceName}",
                    )
                }
            }
        } finally {
            internalTransition.set(false)
        }
    }

    /** Return null after restoring AudioTrack so the current write can fail-open. */
    private fun failWrite(track: AudioTrack, expectedSession: Session, reason: String): Int? {
        synchronized(lock) {
            val active = session?.takeIf {
                it === expectedSession && it.track.get() === track
            } ?: return null
            closeSessionLocked()
            UsbDirectDeviceClient.release(active.context, active.lease)
            markFailure(track, reason, classifyFailure(reason))
            val closedOwnedSession = !active.suspended
            if (!UsbDirectWriteFailurePolicy.shouldResumeOriginalTrack(closedOwnedSession)) return null
            // Keep recovery ordered with pause/stop/release. A late write failure
            // must neither restart a cancelled track nor undo an intentional pause.
            internalTransition.set(true)
            try {
                runCatching { track.play() }
                    .onFailure { error -> ModernXposedRuntime.log("usb_direct: write fallback play failed", error) }
            } finally {
                internalTransition.set(false)
            }
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
        val copy = ByteArray(sizeBytes)
        data.duplicate().get(copy)
        val written = UsbDirectUacBridge.writeBytes(
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
        if (!UsbDirectUacBridge.supportsEncoding(track.format.encoding)) return false
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
        val trackGain = synchronized(lock) {
            trackVolumes[active.track.get()] ?: StereoGain.FULL
        }
        return StereoGain(
            left = active.streamGainCache.effectiveGain(trackGain.left),
            right = active.streamGainCache.effectiveGain(trackGain.right),
        )
    }

    private fun querySystemMediaGain(
        manager: AudioManager,
        deviceType: Int,
        preferredIndex: Int? = null,
    ): Float? {
        val maximum = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrNull()
            ?: return null
        val index = preferredIndex?.takeIf { it in 0..maximum }
            ?: runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return null
        val muted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }
            .getOrDefault(index <= 0)
        val db = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                manager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType)
            }.getOrNull()
        } else {
            null
        }
        return UsbDirectVolumePolicy.streamGain(index, maximum, muted, db)
    }

    private fun markFailure(track: AudioTrack, reason: String, kind: FailureKind) {
        failedTrack = WeakReference(track)
        lastFailure = Failure(reason, kind)
    }

    private fun classifyFailure(reason: String): FailureKind {
        val normalized = reason.lowercase()
        return when {
            "permission" in normalized || "authorize" in normalized || "授权" in reason -> FailureKind.PERMISSION
            "asynchronous" in normalized ||
                "feedback" in normalized ||
                "no uac" in normalized ||
                "unsupported" in normalized ||
                "不受" in reason -> FailureKind.UNSUPPORTED
            else -> FailureKind.OTHER
        }
    }

    private fun closeSessionLocked() {
        session?.let {
            it.keepAlive.close()
            it.playbackPower.release()
            UsbDirectUacBridge.close(it.handle)
        }
        session = null
    }

    private fun statusFor(
        state: String,
        track: AudioTrack,
        lease: UsbDirectDeviceClient.Lease? = null,
        message: String,
    ): UsbBitPerfectStatusDetails = UsbBitPerfectStatusDetails(
        state = state,
        deviceName = lease?.deviceName,
        trackSampleRate = track.format.sampleRate,
        trackEncoding = track.format.encoding,
        trackChannels = track.format.channelCount,
        mixerSampleRate = lease?.sampleRate ?: 0,
        mixerEncoding = lease?.let(::usbEncoding) ?: 0,
        mixerChannels = lease?.channels ?: 0,
        message = message,
    )

    private fun usbEncoding(lease: UsbDirectDeviceClient.Lease): Int = when {
        lease.bitResolution <= 16 -> AudioFormat.ENCODING_PCM_16BIT
        lease.bitResolution <= 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        else -> AudioFormat.ENCODING_PCM_32BIT
    }

    private fun AudioTrack.isMediaTrack(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            audioAttributes.usage == AudioAttributes.USAGE_MEDIA

    private data class Session(
        val track: WeakReference<AudioTrack>,
        val handle: Long,
        val lease: UsbDirectDeviceClient.Lease,
        val context: Context,
        val audioManager: AudioManager,
        val deviceType: Int,
        val streamGainCache: UsbDirectVolumeCache,
        var positionBaseFrames: Long = 0L,
        val playbackPower: UsbDirectPlaybackPower = UsbDirectPlaybackPower(context),
        val keepAlive: UsbDirectPlaybackKeepAlive = UsbDirectPlaybackKeepAlive(context),
        var hasWrittenPcm: Boolean = false,
        var suspended: Boolean = false,
        val ownerStartedRealtimeNanos: Long = System.nanoTime(),
        var lastWriteRealtimeNanos: Long = 0L,
    )

    /** Identity survives the broker response until nativeOpen commits or is cancelled. */
    private class PendingTakeover(val track: WeakReference<AudioTrack>) {
        var originalPaused = false
        var positionBaseFrames = 0L
    }

    private data class StereoGain(val left: Float, val right: Float) {
        companion object {
            val FULL = StereoGain(1f, 1f)
        }
    }

    private data class Failure(
        val reason: String,
        val kind: FailureKind,
    )

    private enum class FailureKind {
        PERMISSION,
        UNSUPPORTED,
        OTHER,
    }
}
