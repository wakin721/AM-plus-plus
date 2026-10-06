package dev.amenhancer.module.hook

internal fun TargetCapabilityInstall.toFeatureInstallResult(): FeatureInstallResult = when (this) {
    is TargetCapabilityInstall.Active -> FeatureInstallResult.active(message)
    is TargetCapabilityInstall.Degraded -> FeatureInstallResult.degraded(message)
    is TargetCapabilityInstall.Unsupported -> FeatureInstallResult.unsupported(message)
}
