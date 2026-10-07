package dev.amenhancer.glass

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.kyant.backdrop.nativeRefractionShader
import java.util.WeakHashMap
import kotlin.math.ceil

/** Native Canvas material. Host text, semantics, touch dispatch and press effects stay native. */
@RequiresApi(33)
class NativeChromeGlass(
    private val root: View,
    private val settings: () -> Pair<Boolean, Int>,
    private val failure: (Throwable) -> Unit,
    private val disposed: () -> Unit,
) : AutoCloseable, ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val scene = RenderNode("AM++ chrome backdrop")
    private val panels = WeakHashMap<Any, Panel>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val screen = IntArray(2)
    private var observer = root.viewTreeObserver
    private var ready = false
    private var closed = false
    private var failed = false
    private var enabled = true
    private var blurDp = GlassPolicy.PANEL_BLUR_DP.toInt()
    private var nextSettings = 0L
    private var screenX = 0
    private var screenY = 0
    private var pixels: Bitmap? = null
    private var lastCapture = 0L

    init {
        observer.addOnPreDrawListener(this)
        root.addOnAttachStateChangeListener(this)
        root.postInvalidateOnAnimation()
    }

    override fun onPreDraw(): Boolean {
        if (closed || failed || GlassCaptureGuard.active) return true
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= nextSettings) {
            nextSettings = now + 500
            val current = settings()
            if (enabled != current.first) ready = false
            enabled = current.first
            blurDp = current.second.coerceIn(0, 100)
        }
        val active = panels.values.mapNotNull { panel -> runCatching { panel.coordinates?.invoke()?.let { panel to it } }.getOrNull() }
        if (ready && active.isEmpty()) return true
        if (!root.isShown ||
            !root.isHardwareAccelerated || root.width <= 0 || root.height <= 0) return true
        try {
            if (!enabled) { active.forEach { (panel, matrix) -> refresh(panel, matrix) }; return true }
            root.getLocationOnScreen(screen)
            if (!ready || root.isDirty || scene.width != root.width || scene.height != root.height ||
                screenX != screen[0] || screenY != screen[1]) {
                if (ready && now - lastCapture < 50) {
                    active.forEach { (panel, matrix) -> refresh(panel, matrix) }; return true
                }
                // A hardware capture would retain cached Compose RenderNodes, including this
                // glass, and form a recursive node graph. Software traversal replays the host's
                // layer draw blocks under the capture guard; no consumer node enters the source.
                // Half-resolution pixels are sufficient for a blurred material, capped at 20 Hz.
                val pixelWidth = (root.width + 1) / 2
                val pixelHeight = (root.height + 1) / 2
                if (pixels?.width != pixelWidth || pixels?.height != pixelHeight) {
                    scene.discardDisplayList()
                    pixels?.recycle()
                    pixels = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
                }
                val image = checkNotNull(pixels)
                image.eraseColor(windowColor())
                val capture = Canvas(image)
                capture.scale(.5f, .5f)
                GlassCaptureGuard.capture { root.draw(capture) }
                scene.setPosition(0, 0, root.width, root.height)
                val canvas = scene.beginRecording()
                try {
                    canvas.drawBitmap(image, null, RectF(0f, 0f, root.width.toFloat(), root.height.toFloat()), null)
                } finally { scene.endRecording() }
                screenX = screen[0]; screenY = screen[1]
                ready = true
                lastCapture = now
            }
            active.forEach { (panel, matrix) -> refresh(panel, matrix) }
        } catch (error: Throwable) { fail(error) }
        return true
    }

    /** Native coordinates refresh even when the host reuses a cached button RenderNode. */
    fun draw(identity: Any, canvas: Canvas, localToScreen: () -> Matrix?, width: Float, height: Float,
        popup: Boolean, nativeColor: Int) {
        if (closed || width <= 0 || height <= 0 || !width.isFinite() || !height.isFinite()) return
        val density = root.resources.displayMetrics.density
        val radius = if (popup) minOf(24 * density, minOf(width, height) / 2) else minOf(width, height) / 2
        if (!canvas.isHardwareAccelerated) { fallback(canvas, width, height, radius, nativeColor, density); return }
        try {
            val panel = panels.getOrPut(identity) { Panel() }
            if (panel.width != width || panel.height != height || panel.color != nativeColor || panel.popup != popup) panel.valid = false
            panel.width = width; panel.height = height; panel.color = nativeColor; panel.popup = popup
            panel.coordinates = localToScreen
            refresh(panel, localToScreen())
            canvas.drawRenderNode(panel.node)
            if (!ready && enabled && !failed) root.postInvalidateOnAnimation()
        } catch (error: Throwable) { fail(error); fallback(canvas, width, height, radius, nativeColor, density) }
    }

    private fun refresh(panel: Panel, matrix: Matrix?) {
        val useGlass = enabled && ready && !failed && matrix != null
        val values = FloatArray(9)
        matrix?.getValues(values)
        val density = root.resources.displayMetrics.density
        val nightMode = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (panel.valid && panel.glass == useGlass && panel.blur == blurDp && panel.density == density &&
            panel.nightMode == nightMode && values.contentEquals(panel.matrix)) return
        val width = panel.width; val height = panel.height
        val radius = if (panel.popup) minOf(24 * density, minOf(width, height) / 2) else minOf(width, height) / 2
        if (useGlass) {
            val inverse = Matrix()
            if (!checkNotNull(matrix).invert(inverse)) { panel.valid = false; return }
            val blur = blurDp * density
            val padding = ceil(maxOf(blur * 2, 24 * density)).toInt()
            panel.sample.setPosition(-padding, -padding, ceil(width).toInt() + padding, ceil(height).toInt() + padding)
            val record = panel.sample.beginRecording()
            try {
                record.translate(padding.toFloat(), padding.toFloat())
                record.concat(inverse)
                record.translate(screenX.toFloat(), screenY.toFloat())
                record.drawRenderNode(scene)
            } finally { panel.sample.endRecording() }
            val shader = panel.shader ?: nativeRefractionShader().also { panel.shader = it }
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("offset", -padding.toFloat(), -padding.toFloat())
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            val lens = minOf(24 * density, minOf(width, height) * .375f)
            shader.setFloatUniform("refractionHeight", lens)
            shader.setFloatUniform("refractionAmount", -lens)
            shader.setFloatUniform("depthEffect", 0f)
            val refraction = RenderEffect.createRuntimeShaderEffect(shader, "content")
            panel.sample.setRenderEffect(if (blur > 0) RenderEffect.createChainEffect(refraction,
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)) else refraction)
        }
        panel.node.setPosition(0, 0, ceil(width).toInt(), ceil(height).toInt())
        val canvas = panel.node.beginRecording()
        try {
            if (useGlass) {
                val bounds = RectF(0f, 0f, width, height)
                path.reset(); path.addRoundRect(bounds, radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
                canvas.drawRenderNode(panel.sample)
                val dark = nightMode == Configuration.UI_MODE_NIGHT_YES
                paint.style = Paint.Style.FILL; paint.shader = null
                paint.color = if (dark) 0x66121212 else 0x66FAFAFA
                canvas.drawRoundRect(bounds, radius, radius, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, density * .6f)
                paint.shader = LinearGradient(0f, 0f, width, height,
                    intArrayOf(0xCCFFFFFF.toInt(), 0x18FFFFFF, 0x66FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                val inset = paint.strokeWidth / 2
                bounds.inset(inset, inset)
                canvas.drawRoundRect(bounds, maxOf(0f, radius - inset), maxOf(0f, radius - inset), paint)
            } else fallback(canvas, width, height, radius, panel.color, density)
        } finally { panel.node.endRecording(); paint.shader = null; paint.style = Paint.Style.FILL }
        panel.valid = true; panel.glass = useGlass; panel.blur = blurDp; values.copyInto(panel.matrix)
        panel.density = density; panel.nightMode = nightMode
    }

    private fun fallback(canvas: Canvas, width: Float, height: Float, radius: Float, color: Int, density: Float) {
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, density * .5f)
        paint.color = 0x18000000
        val inset = paint.strokeWidth / 2
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, maxOf(0f, radius - inset), maxOf(0f, radius - inset), paint)
        paint.style = Paint.Style.FILL
    }

    private fun windowColor(): Int {
        val value = TypedValue()
        return if (root.context.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data or Color.BLACK
        else if (root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) Color.BLACK else Color.WHITE
    }

    private fun fail(error: Throwable) {
        if (failed) return
        failed = true; ready = false
        // Existing host display lists still reference these surface nodes. Replace their
        // contents with the native fallback rather than discarding the visible backgrounds.
        panels.values.forEach { panel ->
            panel.valid = false
            runCatching { refresh(panel, null) }
            panel.sample.discardDisplayList()
        }
        scene.discardDisplayList()
        pixels?.recycle(); pixels = null
        failure(error)
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = close()
    override fun close() {
        if (closed) return
        closed = true
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        root.removeOnAttachStateChangeListener(this)
        scene.discardDisplayList(); panels.values.forEach { it.node.discardDisplayList(); it.sample.discardDisplayList() }; panels.clear()
        pixels?.recycle(); pixels = null
        disposed()
    }

    private class Panel {
        val node = RenderNode("AM++ chrome glass")
        val sample = RenderNode("AM++ chrome sample")
        var shader: android.graphics.RuntimeShader? = null
        var coordinates: (() -> Matrix?)? = null
        var width = 0f
        var height = 0f
        var color = 0
        var popup = false
        var valid = false
        var glass = false
        var blur = -1
        var density = 0f
        var nightMode = 0
        val matrix = FloatArray(9)
    }
}
