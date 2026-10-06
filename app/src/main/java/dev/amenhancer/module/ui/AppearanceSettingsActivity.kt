package dev.amenhancer.module.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.amenhancer.module.ui.theme.AppAppearanceSettings
import dev.amenhancer.module.ui.theme.AppThemeMode
import dev.amenhancer.module.ui.theme.AppleMusicSettingsPalette
import dev.amenhancer.module.ui.theme.AppearancePreferences

/** The same grouped settings appearance as the Apple Music embedded pages. */
class AppearanceSettingsActivity : ComponentActivity() {
    private lateinit var preferences: AppearancePreferences
    private lateinit var appearance: AppAppearanceSettings
    private lateinit var colors: AppleMusicSettingsPalette

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearancePreferences.themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppearancePreferences(this)
        appearance = preferences.settings()
        colors = AppleMusicSettingsPalette.resolve(this)
        configureSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            addView(ScrollView(this@AppearanceSettingsActivity).apply {
                isFillViewport = true
                addView(modeSettings())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }

    private fun topBar(): View = FrameLayout(this).apply {
        addView(ImageView(this@AppearanceSettingsActivity).apply {
            setImageDrawable(EmbeddedGlyphDrawable(EmbeddedGlyphKind.BackArrow, colors.onSurface))
            contentDescription = "返回"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(this@AppearanceSettingsActivity.colors.background)
                setStroke(dp(1), this@AppearanceSettingsActivity.colors.outline)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(12)
        })
        addView(TextView(this@AppearanceSettingsActivity).apply {
            text = "外观与主题"
            textSize = 18f
            setTextColor(colors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
        ).apply {
            marginStart = dp(64)
            marginEnd = dp(64)
        })
    }

    private fun modeSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(32))
        addView(TextView(this@AppearanceSettingsActivity).apply {
            text = "显示模式"
            textSize = 14f
            setTextColor(colors.onSurfaceVariant)
            setPadding(dp(20), dp(12), dp(20), dp(10))
        })
        addView(LinearLayout(this@AppearanceSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(this@AppearanceSettingsActivity.colors.surface)
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
            AppThemeMode.entries.forEachIndexed { index, mode ->
                if (index > 0) addView(View(this@AppearanceSettingsActivity).apply {
                    setBackgroundColor(colors.divider)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(20)
                })
                addView(modeRow(mode))
            }
        })
        addView(TextView(this@AppearanceSettingsActivity).apply {
            text = "自动模式跟随系统的浅色或深色设置。"
            textSize = 13f
            setTextColor(colors.onSurfaceVariant)
            setPadding(dp(20), dp(12), dp(20), 0)
        })
    }

    private fun modeRow(mode: AppThemeMode): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(68)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        isClickable = true
        isFocusable = true
        isSelected = mode == appearance.mode
        background = RippleDrawable(ColorStateList.valueOf(colors.divider), null, null)
        addView(TextView(this@AppearanceSettingsActivity).apply {
            text = mode.displayName
            textSize = 17f
            setTextColor(colors.onSurface)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(this@AppearanceSettingsActivity).apply {
            setImageDrawable(AppleMusicSelectionDrawable(mode == appearance.mode,
                if (mode == appearance.mode) colors.primary else colors.switchTrackOff))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        for (index in 0 until childCount) {
            getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        contentDescription = mode.displayName
        setOnClickListener {
            if (mode != appearance.mode) {
                preferences.save(appearance.copy(mode = mode))
                recreate()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (colors.isDark) {
            window.decorView.systemUiVisibility and lightFlags.inv()
        } else {
            window.decorView.systemUiVisibility or lightFlags
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
