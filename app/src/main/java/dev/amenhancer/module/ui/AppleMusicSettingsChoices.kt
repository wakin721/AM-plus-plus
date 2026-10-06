package dev.amenhancer.module.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.amenhancer.module.ui.theme.AppleMusicSettingsPalette

/** Circular selection mark used by Apple Music's grouped choice rows. */
internal class AppleMusicSelectionDrawable(private val selected: Boolean, private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@AppleMusicSelectionDrawable.color }
    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.085f
        canvas.drawCircle(x, y, size * 0.40f, paint)
        if (selected) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, size * 0.23f, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** All options are visible together; selection uses the same neutral group and red radio as settings. */
internal fun showAppleMusicChoiceDialog(
    context: Context,
    title: String,
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val colors = AppleMusicSettingsPalette.resolve(context)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val heading = TextView(context).apply {
        text = title
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(colors.onSurface)
        setPadding(dp(20), dp(24), dp(20), dp(16))
    }
    val group = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(colors.surface); cornerRadius = dp(16).toFloat() }
        clipToOutline = true
    }
    val body = ScrollView(context).apply {
        setPadding(dp(20), 0, dp(20), dp(8))
        addView(group)
    }
    val dialog = AlertDialog.Builder(context).setCustomTitle(heading).setView(body)
        .setNegativeButton("取消", null).create()
    labels.forEachIndexed { index, label ->
        if (index > 0) group.addView(View(context).apply {
            setBackgroundColor(colors.divider)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(20) })
        group.addView(object : LinearLayout(context) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = index == selected
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = RippleDrawable(ColorStateList.valueOf(colors.divider), null, null)
            isClickable = true
            isFocusable = true
            contentDescription = label
            addView(TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(colors.onSurface)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageDrawable(AppleMusicSelectionDrawable(index == selected,
                    if (index == selected) colors.primary else colors.switchTrackOff))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(16) })
            setOnClickListener { onSelected(index); dialog.dismiss() }
        })
    }
    dialog.show()
    dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
        setColor(colors.background); cornerRadius = dp(24).toFloat()
    })
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colors.primary)
    SettingsPageMotion(body).enter()
}
