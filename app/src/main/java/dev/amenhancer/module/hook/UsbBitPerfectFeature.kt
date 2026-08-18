package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import dev.amenhancer.module.ModuleConstants

internal class UsbBitPerfectFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_USB_BIT_PERFECT

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().usbBitPerfectEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.usbBitPerfect.install().toFeatureInstallResult()
    }
}

internal fun interface UsbBitPerfectTarget {
    fun install(): TargetCapabilityInstall
}

internal class AppleMusicUsbBitPerfectTarget(
    private val applicationContext: Context,
) : UsbBitPerfectTarget {
    override fun install(): TargetCapabilityInstall {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return TargetCapabilityInstall.Degraded("USB Bit-Perfect requires Android 14 or newer")
        }
        val play = AudioTrack::class.java.getDeclaredMethod("play")
        ModernXposedRuntime.hookMethod(play, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val track = param.thisObject as? AudioTrack ?: return
                UsbBitPerfectController.tryApply(applicationContext, track, afterStart = false)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val track = param.thisObject as? AudioTrack ?: return
                UsbBitPerfectController.tryApply(applicationContext, track, afterStart = true)
            }
        })
        return TargetCapabilityInstall.Active(
            "Installed Android 14+ USB Bit-Perfect mixer preference hook on AudioTrack.play",
        )
    }
}

internal object UsbBitPerfectController {
    fun tryApply(context: Context, track: AudioTrack, afterStart: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val attributes = track.audioAttributes
        if (attributes.usage != AudioAttributes.USAGE_MEDIA) return
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
            ModernXposedRuntime.log(
                "usb_bit_perfect: no exact mixer format for ${trackFormat.describe()} on ${device.productName}",
            )
            return
        }

        val applied = runCatching {
            manager.setPreferredMixerAttributes(attributes, device, selected)
        }.getOrElse { error ->
            ModernXposedRuntime.log("usb_bit_perfect: mixer request failed", error)
            false
        }
        ModernXposedRuntime.log(
            if (applied) {
                "usb_bit_perfect: active ${selected.format.describe()} on ${device.productName}"
            } else {
                "usb_bit_perfect: device rejected ${selected.format.describe()} on ${device.productName}"
            },
        )
    }

    private fun resolveUsbDevice(
        manager: AudioManager,
        track: AudioTrack,
        afterStart: Boolean,
    ): AudioDeviceInfo? {
        if (afterStart) {
            runCatching { track.routedDevice }.getOrNull()
                ?.takeIf(AudioDeviceInfo::isUsbAudioOutput)
                ?.let { return it }
        }
        runCatching { track.preferredDevice }.getOrNull()
            ?.takeIf(AudioDeviceInfo::isUsbAudioOutput)
            ?.let { return it }
        val usbOutputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(AudioDeviceInfo::isUsbAudioOutput)
        return usbOutputs.singleOrNull()
    }

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
