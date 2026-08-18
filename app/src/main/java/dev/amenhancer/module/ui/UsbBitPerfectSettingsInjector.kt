package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.res.ColorStateList
import dev.amenhancer.module.R
import java.util.WeakHashMap

/** Adds a compact navigation entry to the AM++ main settings page. */
internal object UsbBitPerfectSettingsInjector : Application.ActivityLifecycleCallbacks {
    private val listeners = WeakHashMap<SettingsActivity, ViewTreeObserver.OnGlobalLayoutListener>()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val settings = activity as? SettingsActivity ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { injectIfNeeded(settings) }
        listeners[settings] = listener
        settings.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun onActivityDestroyed(activity: Activity) {
        val settings = activity as? SettingsActivity ?: return
        listeners.remove(settings)?.let { listener ->
            val observer = settings.window.decorView.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }

    private fun injectIfNeeded(activity: SettingsActivity) {
        if (!activity.window.decorView.containsText("AM++")) return
        val content = activity.window.decorView.findDescendantScrollView()
            ?.getChildAt(0) as? LinearLayout ?: return
        if (content.findViewWithTag<View>(CARD_TAG) != null) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val dark = (activity.resources.configuration.uiMode and 0x30) == 0x20
        val surface = if (dark) Color.rgb(34, 27, 30) else Color.WHITE
        val outline = if (dark) Color.rgb(77, 62, 67) else Color.rgb(235, 221, 226)
        val onSurface = if (dark) Color.rgb(248, 239, 242) else Color.rgb(34, 27, 30)
        val onVariant = if (dark) Color.rgb(213, 195, 201) else Color.rgb(113, 99, 104)
        val primary = if (dark) Color.rgb(255, 139, 176) else Color.rgb(210, 56, 108)

        content.addView(View(activity), LinearLayout.LayoutParams(1, dp(24)))
        content.addView(LinearLayout(activity).apply {
            tag = CARD_TAG
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(surface)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), outline)
            }
            elevation = dp(2).toFloat()
            clipToOutline = true

            addView(TextView(activity).apply {
                text = "音频"
                textSize = 14f
                setTextColor(primary)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(16), dp(18), dp(16), dp(8))
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(88)
                isClickable = true
                isFocusable = true
                contentDescription = "USB Bit-Perfect 设置"
                setPadding(dp(16), dp(10), dp(14), dp(14))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "USB Bit-Perfect"
                        textSize = 17f
                        setTextColor(onSurface)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    addView(TextView(activity).apply {
                        text = "查看总开关、实时状态与 AudioTrack → Mixer → USB DAC 音频链路"
                        textSize = 13.5f
                        setTextColor(onVariant)
                        setPadding(0, dp(4), dp(8), 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(onVariant)
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                setOnClickListener {
                    activity.startActivity(Intent(activity, UsbBitPerfectSettingsActivity::class.java))
                }
            })
        })
    }

    private fun View.findDescendantScrollView(): ScrollView? {
        if (this is ScrollView) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val found = group.getChildAt(index).findDescendantScrollView()
            if (found != null) return found
        }
        return null
    }

    private fun View.containsText(text: String): Boolean {
        if (this is TextView && this.text?.toString() == text) return true
        val group = this as? ViewGroup ?: return false
        return (0 until group.childCount).any { group.getChildAt(it).containsText(text) }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private const val CARD_TAG = "ampp_usb_bit_perfect_card"
}
