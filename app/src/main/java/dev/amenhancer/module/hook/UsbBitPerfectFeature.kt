package dev.amenhancer.module.hook

import android.content.Context
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
                UsbBitPerfectController.tryApply(applicationContext, track)
            }
        })
        return TargetCapabilityInstall.Active(
            "Installed Android 14+ USB Bit-Perfect mixer preference hook on AudioTrack.play",
        )
    }
}

internal object UsbBitPerfectController {
    fun tryApply(context: Context, track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val routed = runCatching { track.routedDevice }.getOrNull() ?: return
        if (!routed.isUsbAudioOutput()) {
            clearPreference(manager, track.audioAttributes)
            return
        }
        val trackFormat = track.format
        val selected = manager.getSupportedMixerAttributes(routed)
            .asSequence()
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .firstOrNull { candidate -> candidate.format.matchesExactly(trackFormat) }
        if (selected == null) {
            clearPreference(manager, track.audioAttributes)
            ModernXposedRuntime.log(
                "usb_bit_perfect: no exact mixer format for ${trackFormat.describe()} on ${routed.productName}",
            )
            return
        }
        val applied = runCatching {
            manager.setPreferredMixerAttributes(track.audioAttributes, routed, selected)
        }.getOrDefault(false)
        ModernXposedRuntime.log(
            if (applied) {
                "usb_bit_perfect: active ${selected.format.describe()} on ${routed.productName}"
            } else {
                "usb_bit_perfect: device rejected ${selected.format.describe()} on ${routed.productName}"
            },
        )
    }

    private fun clearPreference(manager: AudioManager, attributes: android.media.AudioAttributes) {
        runCatching { manager.clearPreferredMixerAttributes(attributes) }
    }

    internal fun AudioFormat.matchesExactly(other: AudioFormat): Boolean =
        sampleRate == other.sampleRate &&
            encoding == other.encoding &&
            channelCount == other.channelCount

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
