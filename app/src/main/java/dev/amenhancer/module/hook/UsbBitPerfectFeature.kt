package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

internal class UsbBitPerfectFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_USB_BIT_PERFECT

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        if (!settings.usbBitPerfectEnabled) {
            UsbDirectUacController.configure(false)
            return FeatureInstallResult.disabled()
        }
        UsbDirectUacController.configure(settings.usbDirectUacEnabled)
        return context.target.usbBitPerfect.install().toFeatureInstallResult()
    }
}
