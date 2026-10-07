package dev.amenhancer.module.hook

import android.graphics.Canvas
import android.graphics.Matrix
import android.view.View

/** Only Android drawing primitives cross the host Compose class-loader boundary. */
interface ChromeGlassPainter {
    val capturing: Boolean
    fun prepare(source: View)
    fun draw(identity: Any, source: View, canvas: Canvas, localToScreen: () -> Matrix?, width: Float, height: Float,
        popup: Boolean, nativeColor: Int)
}
