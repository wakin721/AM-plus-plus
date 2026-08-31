package dev.amenhancer.module.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class UsbBitPerfectFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_USB_BIT_PERFECT

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        if (!settings.usbBitPerfectEnabled) {
            UsbDirectUacController.configure(false)
            UsbExclusiveAaudioController.configure(false)
            return FeatureInstallResult.disabled()
        }
        UsbDirectUacController.configure(settings.usbDirectUacEnabled)
        UsbExclusiveAaudioController.configure(settings.usbExclusiveAaudioEnabled)
        return context.target.usbBitPerfect.install().toFeatureInstallResult()
    }
}

internal fun interface UsbBitPerfectTarget {
    fun install(): TargetCapabilityInstall
}

internal class AppleMusicUsbBitPerfectTarget(
    private val application: Application,
) : UsbBitPerfectTarget {
    override fun install(): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return TargetCapabilityInstall.Degraded("USB Bit-Perfect requires Android 14 or newer")
        }
        if (!UsbBitPerfectStatusRequestResponder(application).register()) {
            return TargetCapabilityInstall.Degraded(
                "USB Bit-Perfect status request receiver could not be registered",
            )
        }
        UsbExclusiveSystemVolumeObserver.register(application)

        val play = AudioTrack::class.java.getDeclaredMethod("play")
        ModernXposedRuntime.hookMethod(play, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val track = param.thisObject as? AudioTrack ?: return
                if (UsbDirectUacController.beforePlay(application, track)) {
                    param.result = null
                    return
                }
                if (UsbExclusiveAaudioController.beforePlay(application, track)) {
                    param.result = null
                    return
                }
                UsbBitPerfectController.tryApply(application, track, afterStart = false)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val track = param.thisObject as? AudioTrack ?: return
                if (UsbDirectUacController.isActive(track)) return
                if (UsbExclusiveAaudioController.isActive(track)) return
                UsbBitPerfectController.tryApply(application, track, afterStart = true)
            }
        })

        ModernXposedRuntime.hookAllMethods(
            AudioTrack::class.java,
            "write",
            object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as? AudioTrack ?: return
                    UsbDirectUacController.interceptWrite(track, param.args)?.let { written ->
                        param.result = written
                        return
                    }
                    UsbExclusiveAaudioController.interceptWrite(
                        application,
                        track,
                        param.args,
                    )?.let { written ->
                        param.result = written
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val track = param.thisObject as? AudioTrack ?: return
                    if (UsbDirectUacController.isActive(track)) return
                    if (UsbExclusiveAaudioController.isActive(track)) return
                    UsbDirectUacController.afterOriginalWrite(
                        application,
                        track,
                        param.args,
                        param.result,
                    )
                    if (UsbDirectUacController.isActive(track)) return
                    if (UsbDirectUacController.allowsAaudioFallback(track)) {
                        UsbExclusiveAaudioController.afterOriginalWrite(
                            track,
                            param.args,
                            param.result,
                        )
                    }
                }
            },
        )

        listOf("setVolume", "setStereoVolume").forEach { operation ->
            ModernXposedRuntime.hookAllMethods(
                AudioTrack::class.java,
                operation,
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val track = param.thisObject as? AudioTrack ?: return
                        UsbDirectUacController.afterVolumeChange(
                            track,
                            operation,
                            param.args,
                            param.result,
                        )
                        UsbExclusiveAaudioController.afterVolumeChange(
                            track,
                            operation,
                            param.args,
                            param.result,
                        )
                    }
                },
            )
        }

        listOf("pause", "stop", "flush", "release").forEach { operation ->
            ModernXposedRuntime.hookAllMethods(
                AudioTrack::class.java,
                operation,
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val track = param.thisObject as? AudioTrack ?: return
                        UsbDirectUacController.onTransportControl(application, track, operation)
                        UsbExclusiveAaudioController.onTransportControl(track, operation)
                    }
                },
            )
        }

        return TargetCapabilityInstall.Active(
            buildString {
                append("Installed Android 14+ USB Bit-Perfect mixer preference hook on AudioTrack.play")
                if (UsbDirectUacController.isEnabled()) {
                    append(" plus experimental USB Host/UAC usbfs direct takeover")
                }
                if (UsbExclusiveAaudioController.isEnabled()) {
                    append(" plus experimental AAudio EXCLUSIVE fallback")
                }
            },
        )
    }
}

/**
 * AAudio EXCLUSIVE does not pass through AudioFlinger's software volume stage.
 * Mirror the media step selected by the system volume UI into the PCM gain used
 * by [UsbExclusiveAaudioController]. The receiver is process-local and only
 * accepts the framework media-stream broadcast.
 */
internal object UsbExclusiveSystemVolumeObserver {
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val VOLUME_POLL_INTERVAL_MILLIS = 2_000L
    private val registered = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val index = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            UsbDirectUacController.onSystemMediaVolumeChanged(index)
            UsbExclusiveAaudioController.onSystemMediaVolumeChanged(index)
        }
    }

    fun register(application: Application) {
        if (registered.compareAndSet(false, true)) {
            val result = runCatching {
                val filter = IntentFilter(VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    application.registerReceiver(receiver, filter)
                }
            }
            if (result.isFailure) {
                registered.set(false)
                ModernXposedRuntime.log(
                    "usb_exclusive: system media volume observer unavailable",
                    result.exceptionOrNull(),
                )
            }
        }
        scheduleVolumePolling(application)
    }

    private fun scheduleVolumePolling(application: Application) {
        if (!polling.compareAndSet(false, true)) return
        val manager = application.getSystemService(AudioManager::class.java) ?: run {
            polling.set(false)
            return
        }
        val handler = Handler(application.mainLooper)
        val poll = object : Runnable {
            override fun run() {
                runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
                    .getOrNull()
                    ?.let { index ->
                        UsbDirectUacController.onSystemMediaVolumeChanged(index)
                        UsbExclusiveAaudioController.onSystemMediaVolumeChanged(index)
                    }
                handler.postDelayed(this, VOLUME_POLL_INTERVAL_MILLIS)
            }
        }
        handler.post(poll)
    }
}

internal object UsbBitPerfectController {
    private val latestTrack = AtomicReference<WeakReference<AudioTrack>?>(null)
    private val lastStatus = AtomicReference(waitingPlayback())

    fun tryApply(context: Context, track: AudioTrack, afterStart: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val attributes = track.audioAttributes
        if (attributes.usage != AudioAttributes.USAGE_MEDIA) return
        latestTrack.set(WeakReference(track))

        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val device = resolveUsbDevice(manager, track, afterStart) ?: return
        val trackFormat = track.format
        val selected = runCatching { manager.getSupportedMixerAttributes(device) }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .firstOrNull { candidate -> candidate.format.matchesExactly(trackFormat) }

        if (selected == null) {
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED,
                    device = device,
                    trackFormat = trackFormat,
                    message = "USB HAL 未提供与当前 AudioTrack 完全匹配的 Bit-Perfect mixer",
                ),
            )
            ModernXposedRuntime.log(
                "usb_bit_perfect: no exact mixer format for ${trackFormat.describe()} on ${device.productName}",
            )
            return
        }

        val applied = runCatching {
            manager.setPreferredMixerAttributes(attributes, device, selected)
        }.getOrElse { error ->
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED,
                    device = device,
                    trackFormat = trackFormat,
                    mixerFormat = selected.format,
                    message = error.message ?: error.javaClass.simpleName,
                ),
            )
            ModernXposedRuntime.log("usb_bit_perfect: mixer request failed", error)
            false
        }
        if (!applied) {
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED,
                    device = device,
                    trackFormat = trackFormat,
                    mixerFormat = selected.format,
                    message = "系统拒绝设置 preferred mixer attributes",
                ),
            )
            ModernXposedRuntime.log(
                "usb_bit_perfect: device rejected ${selected.format.describe()} on ${device.productName}",
            )
            return
        }

        val preferred = runCatching {
            manager.getPreferredMixerAttributes(attributes, device)
        }.getOrNull()
        val verified = preferred != null &&
            preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            preferred.format.matchesExactly(trackFormat)
        if (!verified) {
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED,
                    device = device,
                    trackFormat = trackFormat,
                    mixerFormat = preferred?.format ?: selected.format,
                    message = "系统未保留可验证的 Bit-Perfect preferred mixer",
                ),
            )
            return
        }

        publish(
            statusFor(
                state = if (afterStart) {
                    UsbBitPerfectStatusProtocol.STATE_ACTIVE
                } else {
                    UsbBitPerfectStatusProtocol.STATE_CONFIGURED
                },
                device = device,
                trackFormat = trackFormat,
                mixerFormat = preferred.format,
                message = if (afterStart) {
                    "实际 USB 路由与 Bit-Perfect mixer 已核验"
                } else {
                    "Bit-Perfect mixer 已配置，等待 AudioTrack 实际路由"
                },
            ),
        )
        ModernXposedRuntime.log(
            "usb_bit_perfect: ${if (afterStart) "active" else "configured"} " +
                "${preferred.format.describe()} on ${device.productName}",
        )
    }

    fun currentStatus(context: Context): UsbBitPerfectStatusDetails {
        val direct = UsbDirectUacController.currentStatus()
        val exclusive = UsbExclusiveAaudioController.currentStatus(context)
        if (direct != null) {
            val directFailed = direct.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK ||
                direct.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_UNSUPPORTED_DEVICE ||
                direct.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED
            val exclusiveActive = exclusive?.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE ||
                exclusive?.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED
            if (!directFailed || !exclusiveActive) return direct
            return requireNotNull(exclusive).copy(
                message = listOfNotNull(
                    direct.message,
                    exclusive.message,
                ).joinToString("\n"),
            )
        }
        exclusive?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return UsbBitPerfectStatusDetails(
                state = UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID,
                message = "需要 Android 14 或更高版本",
            )
        }
        val track = latestTrack.get()?.get() ?: return waitingPlayback()
        if (runCatching { track.playState }.getOrDefault(AudioTrack.PLAYSTATE_STOPPED) !=
            AudioTrack.PLAYSTATE_PLAYING
        ) {
            return waitingPlayback()
        }
        val attributes = track.audioAttributes
        if (attributes.usage != AudioAttributes.USAGE_MEDIA) return waitingPlayback()
        val manager = context.getSystemService(AudioManager::class.java) ?: return lastStatus.get()
        val routed = runCatching { track.routedDevice }.getOrNull()
            ?: return UsbBitPerfectStatusDetails(
                state = UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE,
                trackSampleRate = track.format.sampleRate,
                trackEncoding = track.format.encoding,
                trackChannels = track.format.channelCount,
                message = "AudioTrack 正在播放，但系统尚未报告实际输出路由",
            )
        if (!routed.isUsbAudioOutput()) {
            val usbConnected = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.isUsbAudioOutput() }
            return statusFor(
                state = if (usbConnected) {
                    UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE
                } else {
                    UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE
                },
                device = routed,
                trackFormat = track.format,
                message = if (usbConnected) {
                    "Apple Music 当前没有路由到已连接的 USB 音频设备"
                } else {
                    "当前没有可用的 USB 音频输出"
                },
            )
        }

        val trackFormat = track.format
        val preferred = runCatching {
            manager.getPreferredMixerAttributes(attributes, routed)
        }.getOrNull()
        if (preferred != null &&
            preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            preferred.format.matchesExactly(trackFormat)
        ) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_ACTIVE,
                device = routed,
                trackFormat = trackFormat,
                mixerFormat = preferred.format,
                message = "AudioTrack 正在实际 USB 路由，Bit-Perfect preference 与输出格式完全匹配",
            )
        }

        val supportedExact = runCatching { manager.getSupportedMixerAttributes(routed) }
            .getOrDefault(emptyList())
            .firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.matchesExactly(trackFormat)
            }
        if (supportedExact == null) {
            return statusFor(
                state = UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED,
                device = routed,
                trackFormat = trackFormat,
                message = "当前 USB 路由没有与 AudioTrack 完全匹配的 Bit-Perfect mixer",
            )
        }

        val previous = lastStatus.get()
        return statusFor(
            state = if (previous.state == UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED) {
                UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED
            } else {
                UsbBitPerfectStatusProtocol.STATE_NOT_ACTIVE
            },
            device = routed,
            trackFormat = trackFormat,
            mixerFormat = preferred?.format ?: supportedExact.format,
            message = previous.message ?: "Bit-Perfect mixer 可用，但当前 preference 未处于活动状态",
        )
    }

    private fun resolveUsbDevice(
        manager: AudioManager,
        track: AudioTrack,
        afterStart: Boolean,
    ): AudioDeviceInfo? {
        if (afterStart) {
            val routed = runCatching { track.routedDevice }.getOrNull()
            if (routed == null) {
                publish(
                    statusFor(
                        state = UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE,
                        trackFormat = track.format,
                        message = "等待 AudioTrack 报告实际输出路由",
                    ),
                )
                return null
            }
            if (!routed.isUsbAudioOutput()) {
                val usbConnected = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.isUsbAudioOutput() }
                publish(
                    statusFor(
                        state = if (usbConnected) {
                            UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE
                        } else {
                            UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE
                        },
                        device = routed,
                        trackFormat = track.format,
                        message = if (usbConnected) {
                            "Apple Music 当前未路由到 USB 音频设备"
                        } else {
                            "当前没有连接 USB 音频设备"
                        },
                    ),
                )
                return null
            }
            return routed
        }

        runCatching { track.preferredDevice }.getOrNull()
            ?.takeIf { it.isUsbAudioOutput() }
            ?.let { return it }
        val usbOutputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isUsbAudioOutput() }
        if (usbOutputs.isEmpty()) {
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE,
                    trackFormat = track.format,
                    message = "当前没有连接 USB 音频设备",
                ),
            )
            return null
        }
        if (usbOutputs.size > 1) {
            publish(
                statusFor(
                    state = UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE,
                    trackFormat = track.format,
                    message = "检测到多个 USB 音频输出，等待系统确定实际路由",
                ),
            )
            return null
        }
        return usbOutputs.single()
    }

    private fun publish(status: UsbBitPerfectStatusDetails) {
        lastStatus.set(status)
    }

    private fun waitingPlayback(): UsbBitPerfectStatusDetails = UsbBitPerfectStatusDetails(
        state = UsbBitPerfectStatusProtocol.STATE_WAITING_PLAYBACK,
        message = "等待 Apple Music 播放媒体 AudioTrack",
    )

    private fun statusFor(
        state: String,
        device: AudioDeviceInfo? = null,
        trackFormat: AudioFormat? = null,
        mixerFormat: AudioFormat? = null,
        message: String? = null,
    ): UsbBitPerfectStatusDetails = UsbBitPerfectStatusDetails(
        state = state,
        deviceName = device?.productName?.toString()?.takeIf(String::isNotBlank),
        trackSampleRate = trackFormat?.sampleRate ?: 0,
        trackEncoding = trackFormat?.encoding ?: 0,
        trackChannels = trackFormat?.channelCount ?: 0,
        mixerSampleRate = mixerFormat?.sampleRate ?: 0,
        mixerEncoding = mixerFormat?.encoding ?: 0,
        mixerChannels = mixerFormat?.channelCount ?: 0,
        message = message,
    )

    internal fun AudioFormat.matchesExactly(other: AudioFormat): Boolean =
        sampleRate == other.sampleRate &&
            encoding == other.encoding &&
            channelMask == other.channelMask &&
            channelIndexMask == other.channelIndexMask

    internal fun AudioDeviceInfo.isUsbAudioOutput(): Boolean = isSink && when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> true
        else -> false
    }

    private fun AudioFormat.describe(): String =
        "${sampleRate}Hz encoding=$encoding channels=$channelCount"
}

private class UsbBitPerfectStatusRequestResponder(
    private val application: Application,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbBitPerfectStatusProtocol.REQUEST_ACTION) return
            val token = intent.getStringExtra(UsbBitPerfectStatusProtocol.EXTRA_REQUEST_TOKEN)
                ?.takeIf(String::isNotBlank)
                ?: return
            val resultReceiver = intent.resultReceiver() ?: return
            val status = UsbBitPerfectController.currentStatus(application)
            resultReceiver.send(
                UsbBitPerfectStatusProtocol.RESULT_AVAILABLE,
                Bundle().apply {
                    putString(UsbBitPerfectStatusProtocol.EXTRA_REQUEST_TOKEN, token)
                    putString(UsbBitPerfectStatusProtocol.EXTRA_STATE, status.state)
                    status.deviceName?.let {
                        putString(UsbBitPerfectStatusProtocol.EXTRA_DEVICE_NAME, it)
                    }
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_TRACK_SAMPLE_RATE, status.trackSampleRate)
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_TRACK_ENCODING, status.trackEncoding)
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_TRACK_CHANNELS, status.trackChannels)
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_MIXER_SAMPLE_RATE, status.mixerSampleRate)
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_MIXER_ENCODING, status.mixerEncoding)
                    putInt(UsbBitPerfectStatusProtocol.EXTRA_MIXER_CHANNELS, status.mixerChannels)
                    status.message?.let {
                        putString(UsbBitPerfectStatusProtocol.EXTRA_MESSAGE, it)
                    }
                },
            )
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register(): Boolean = runCatching {
        val filter = IntentFilter(UsbBitPerfectStatusProtocol.REQUEST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                receiver,
                filter,
                UsbBitPerfectStatusProtocol.REQUEST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(
                receiver,
                filter,
                UsbBitPerfectStatusProtocol.REQUEST_PERMISSION,
                null,
            )
        }
        true
    }.onFailure { error ->
        ModernXposedRuntime.log("usb_bit_perfect: status request receiver failed", error)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(
                UsbBitPerfectStatusProtocol.EXTRA_RESULT_RECEIVER,
                ResultReceiver::class.java,
            )
        } else {
            getParcelableExtra(UsbBitPerfectStatusProtocol.EXTRA_RESULT_RECEIVER)
        }
}
