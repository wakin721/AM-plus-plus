package dev.amenhancer.module.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal class LauncherIconController(context: Context) {
    private val appContext = context.applicationContext
    private val launcherComponent = ComponentName(
        appContext,
        LAUNCHER_ALIAS_CLASS,
    )

    fun isHidden(): Boolean = when (
        appContext.packageManager.getComponentEnabledSetting(launcherComponent)
    ) {
        // A fresh install follows the manifest's disabled launcher default.
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> true
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
        else -> true
    }

    fun setHidden(hidden: Boolean) {
        appContext.packageManager.setComponentEnabledSetting(
            launcherComponent,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        const val LAUNCHER_ALIAS_CLASS = "dev.amenhancer.module.LauncherAlias"
    }
}
