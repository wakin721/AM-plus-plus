package dev.amenhancer.module.hook

import android.app.Activity
import android.view.KeyEvent
import java.lang.reflect.Method

/** Older host Activities route Back through keys or onBackPressed; Android 13+ also uses the page's dispatcher. */
internal object SettingsPageBackInstaller {
    fun install(loader: ClassLoader, activityNames: List<String>, observer: SettingsEntryObserver) {
        val types = activityNames.mapNotNull { runCatching { loader.loadClass(it) }.getOrNull() } + Activity::class.java
        val methods = types.flatMap { type ->
            listOfNotNull(
                inheritedMethod(type, "onBackPressed"),
                inheritedMethod(type, "dispatchKeyEvent", KeyEvent::class.java),
            )
        }.distinctBy(Method::toGenericString)
        methods.forEach { method ->
            runCatching {
                ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!observer.hasSettingsPage(activity)) return
                        if (method.name == "dispatchKeyEvent") {
                            val event = param.args.firstOrNull() as? KeyEvent ?: return
                            if (event.keyCode != KeyEvent.KEYCODE_BACK) return
                            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                                observer.onSettingsBackPressed(activity)
                            }
                            param.result = true
                        } else if (observer.onSettingsBackPressed(activity)) {
                            param.result = null
                        }
                    }
                })
            }.onFailure { ModernXposedRuntime.log("AM++ settings Back hook failed: ${method.name}", it) }
        }
    }

    private fun inheritedMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        generateSequence(type) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredMethod(name, *parameters) }.getOrNull()
        }.firstOrNull()
}
