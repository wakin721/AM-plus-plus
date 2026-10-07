package dev.amenhancer.module.hook

import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View
import androidx.annotation.RequiresApi
import dev.amenhancer.glass.GlassCaptureGuard
import dev.amenhancer.glass.NativeChromeGlass
import dev.amenhancer.module.config.TargetConfigClient
import java.util.WeakHashMap

@RequiresApi(33)
internal object ChromeGlassRuntime {
    private val sessions = WeakHashMap<View, NativeChromeGlass>()
    private var registration: HostSubscription? = null
    private var attempted = false

    fun discover(view: View, config: TargetConfigClient) {
        if (attempted || !config.settings().phoneLiquidGlassEnabled) return
        val build = targetBuild(view.context)
        if (!ChromeGlassFactory.supports(build)) return
        attempted = true
        runCatching {
            registration = ChromeGlassFactory.install(view.context.classLoader, build, object : ChromeGlassPainter {
                override val capturing: Boolean get() = GlassCaptureGuard.active
                override fun prepare(source: View) { session(source, config) }
                override fun draw(identity: Any, source: View, canvas: Canvas, localToScreen: () -> Matrix?,
                    width: Float, height: Float, popup: Boolean, nativeColor: Int) {
                    session(source, config)?.draw(identity, canvas, localToScreen, width, height, popup, nativeColor)
                }
            })
            ModernXposedRuntime.log("chrome glass installed for ${build.versionName} (${build.versionCode})")
        }.onFailure { ModernXposedRuntime.log("chrome glass installation kept native", it) }
    }

    private fun session(source: View, config: TargetConfigClient): NativeChromeGlass? {
        val root = source.rootView
        if (!root.isAttachedToWindow) return null
        return sessions.getOrPut(root) {
            NativeChromeGlass(root, {
                config.settings().let { it.phoneLiquidGlassEnabled to it.phoneLiquidGlassPanelBlurDp }
            }, { ModernXposedRuntime.log("chrome glass renderer kept native", it) }, { sessions.remove(root) })
        }
    }
}
