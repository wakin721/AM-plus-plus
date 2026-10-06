package dev.amenhancer.module.hook
import dev.amenhancer.module.ModuleConstants
internal fun AppleMusicDpiOverrideStatus.asFeatureResult(): FeatureInstallResult = when (state) {
        AppleMusicDpiOverrideState.DISABLED -> FeatureInstallResult.disabled(message)
        AppleMusicDpiOverrideState.ACTIVE -> FeatureInstallResult.active(message)
        AppleMusicDpiOverrideState.DEGRADED -> FeatureInstallResult.degraded(message)
    }

internal class AppleMusicDpiOverrideFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_APPLE_MUSIC_DPI

    override fun install(context: HookContext): FeatureInstallResult =
        AppleMusicHostFactory.densityStatus().asFeatureResult()
}
