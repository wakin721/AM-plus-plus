package dev.amenhancer.module.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.CompoundButton

/** Keep the visual bounds independent of the host's Switch theme and font scale. */
internal class EmbeddedSettingsSwitch(context: Context) : CompoundButton(context, null, 0) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buttonDrawable = null
        background = null
        setPadding(0, 0, 0, 0)
        isClickable = true
        isFocusable = true
        minimumWidth = (56 * resources.displayMetrics.density).toInt()
        minimumHeight = (44 * resources.displayMetrics.density).toInt()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val geometry = embeddedSwitchGeometry(
            width.toFloat(), height.toFloat(), resources.displayMetrics.density,
            isChecked, layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
        paint.color = if (isChecked) EmbeddedSettingsPalette.switchTrackOn else EmbeddedSettingsPalette.switchTrackOff
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
): EmbeddedSwitchGeometry {
    val scale = minOf(density, width / 54f, height / 44f).coerceAtLeast(0f)
    val left = (width - 54f * scale) / 2f
    val top = (height - 34f * scale) / 2f
    return EmbeddedSwitchGeometry(
        left = left,
        top = top,
        right = left + 54f * scale,
        bottom = top + 34f * scale,
        trackRadius = 17f * scale,
        thumbX = left + (if (checked != rtl) 37f else 17f) * scale,
        thumbY = height / 2f,
        thumbRadius = (if (checked) 13f else 8.5f) * scale,
    )
}
