package dev.amenhancer.module.config

import android.app.Activity
import android.os.Bundle

/** No settings UI: starting the module initializes its service and visibility grant. */
class SettingsBridgeBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
