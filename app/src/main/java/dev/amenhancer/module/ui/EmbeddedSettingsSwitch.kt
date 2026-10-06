package dev.amenhancer.module.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton

/** Keep the visual bounds independent of the host's Switch theme and font scale. */
internal class EmbeddedSettingsSwitch(context: Context) : CompoundButton(context, null, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colorEvaluator = ArgbEvaluator()
    private var ready = false
    private var animator: ValueAnimator? = null
    internal var motionKey: String? = null
    internal var motionProgress = 0f
        private set

    init {
        buttonDrawable = null
        background = null
        setPadding(0, 0, 0, 0)
        isClickable = true
        isFocusable = true
        minimumWidth = (56 * resources.displayMetrics.density).toInt()
        minimumHeight = (44 * resources.displayMetrics.density).toInt()
        motionProgress = if (isChecked) 1f else 0f
        ready = true
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        // CompoundButton can invoke this override during its constructor.
        if (ready && changed) animateToCheckedState()
    }

    internal fun resumeMotionFrom(progress: Float) {
        animator?.cancel()
        motionProgress = progress.coerceIn(0f, 1f)
        if (isAttachedToWindow) animateToCheckedState() else invalidate()
    }

    private fun animateToCheckedState() {
        animator?.cancel()
        animator = null
        val target = if (isChecked) 1f else 0f
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() || motionProgress == target) {
            motionProgress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(motionProgress, target).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                motionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateToCheckedState()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        motionProgress = if (isChecked) 1f else 0f
        super.onDetachedFromWindow()
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        animator?.cancel()
        animator = null
        motionProgress = if (isChecked) 1f else 0f
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val geometry = embeddedSwitchGeometry(
            width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            motionProgress, layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
        paint.color = colorEvaluator.evaluate(
            motionProgress, EmbeddedSettingsPalette.switchTrackOff, EmbeddedSettingsPalette.switchTrackOn,
        ) as Int
        canvas.drawRoundRect(
            geometry.left, geometry.top, geometry.right, geometry.bottom,
            geometry.trackRadius, geometry.trackRadius, paint,
        )
        paint.color = Color.WHITE
        canvas.drawCircle(geometry.thumbX, geometry.thumbY, geometry.thumbRadius, paint)
    }
}

internal data class EmbeddedSwitchGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val trackRadius: Float,
    val thumbX: Float,
    val thumbY: Float,
    val thumbRadius: Float,
)

internal fun embeddedSwitchGeometry(
    width: Float,
    height: Float,
    density: Float,
    checked: Boolean,
    rtl: Boolean,
): EmbeddedSwitchGeometry = embeddedSwitchGeometry(width, height, density, if (checked) 1f else 0f, rtl)

internal fun embeddedSwitchGeometry(
    width: Float,
    height: Float,
    density: Float,
    progress: Float,
    rtl: Boolean,
): EmbeddedSwitchGeometry {
    val fraction = progress.coerceIn(0f, 1f)
    val scale = minOf(density, width / 54f, height / 44f).coerceAtLeast(0f)
    val left = (width - 54f * scale) / 2f
    val top = (height - 34f * scale) / 2f
    return EmbeddedSwitchGeometry(
        left = left,
        top = top,
        right = left + 54f * scale,
        bottom = top + 34f * scale,
        trackRadius = 17f * scale,
        thumbX = left + (17f + 20f * (if (rtl) 1f - fraction else fraction)) * scale,
        thumbY = height / 2f,
        thumbRadius = (8.5f + 4.5f * fraction) * scale,
    )
}
