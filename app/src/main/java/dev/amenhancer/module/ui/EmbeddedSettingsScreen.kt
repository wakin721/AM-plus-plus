package dev.amenhancer.module.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.model.ModuleSettings

internal fun EmbeddedSettingsHost.showSettingsPage(activity: Activity) {
    if (settingsPageSurface?.activity === activity) return
    dismissSettingsPage()
    controller.ensureSettingsBridge()
    EmbeddedSettingsPalette.update(activity, controller.appearanceMode())
    val initialSettings = runCatching { controller.currentSettings() }.getOrElse {
        Toast.makeText(activity, "无法读取 AM++ 设置", Toast.LENGTH_SHORT).show()
        return
    }
    val draft = EmbeddedSettingsDraft(initialSettings, controller::saveOrdinarySettings)
    val navigation = EmbeddedSettingsNavigation()
    val scrollPositions = mutableMapOf<EmbeddedSettingsPage, Int>()
    var renderedPage = navigation.page
    var scroll: ScrollView? = null
    lateinit var refreshView: () -> Unit
    val root = LinearLayout(activity).apply {
        tag = "ampp_embedded_settings_page"
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
    }
    val topBar = FrameLayout(activity)
    val title = TextView(activity).apply {
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(EmbeddedSettingsPalette.onSurface)
        setTypeface(typeface, Typeface.BOLD)
        setSingleLine(true)
    }
    topBar.addView(title, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    ).apply { setMargins(dp(activity, 64), 0, dp(activity, 64), 0) })
    val back = ImageView(activity).apply {
        setImageDrawable(EmbeddedGlyphDrawable(EmbeddedGlyphKind.BackArrow, EmbeddedSettingsPalette.onSurface))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10))
        contentDescription = "返回"
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(EmbeddedSettingsPalette.pageBackground)
            setStroke(dp(activity, 1), EmbeddedSettingsPalette.outline)
        }
    }
    topBar.addView(back, FrameLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        marginStart = dp(activity, 12)
    })
    root.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 64)))
    val pageContent = FrameLayout(activity)
    root.addView(pageContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    val pageMotion = SettingsPageMotion(pageContent)

    fun renderPage() {
        EmbeddedSettingsPalette.update(activity, controller.appearanceMode())
        root.setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
        title.setTextColor(EmbeddedSettingsPalette.onSurface)
        back.setImageDrawable(EmbeddedGlyphDrawable(EmbeddedGlyphKind.BackArrow, EmbeddedSettingsPalette.onSurface))
        back.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(EmbeddedSettingsPalette.pageBackground)
            setStroke(dp(activity, 1), EmbeddedSettingsPalette.outline)
        }
        val pageChanged = renderedPage != navigation.page
        val switchMotion = if (pageChanged) emptyMap() else scroll?.let(::captureSettingsSwitchMotion).orEmpty()
        scroll?.let { scrollPositions[renderedPage] = it.scrollY }
        renderedPage = navigation.page
        pageContent.removeAllViews()
        title.text = when (navigation.page) {
            EmbeddedSettingsPage.MAIN -> "AM++"
            EmbeddedSettingsPage.CUSTOM_LYRICS -> "自定义歌词"
            EmbeddedSettingsPage.USB_AUDIO -> "USB 音频输出"
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val nextScroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 24))
            addView(content)
        }
        scroll = nextScroll
        pageContent.addView(nextScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fun updateDraft(next: ModuleSettings) {
            if (!draft.update(next)) {
                Toast.makeText(activity, "保存 AM++ 设置失败，请重试", Toast.LENGTH_SHORT).show()
            }
            // Each row captures this render's settings. Refresh after a write so
            // another row cannot accidentally restore that old snapshot.
            refreshView()
        }
        if (navigation.page == EmbeddedSettingsPage.CUSTOM_LYRICS) {
            renderEmbeddedCustomLyricsPage(activity, content, draft.settings, controller.currentSongDetails(), ::updateDraft)
        } else if (navigation.page == EmbeddedSettingsPage.USB_AUDIO) {
            renderEmbeddedUsbPage(activity, content, draft.settings, ::updateDraft)
        } else {
            renderEmbeddedMainPage(
                activity, content, draft.settings,
                runCatching { controller.lyricsEntries().size }.getOrDefault(0),
                onSettingsChanged = ::updateDraft,
                onCellularDataEntryChanged = { updateDraft(draft.settings.copy(forceCellularDataEntryEnabled = it)) },
                onOpenUsb = { navigation.openUsb(); renderPage() },
                onOpenCustomLyrics = { navigation.openLyrics(); renderPage() },
                onChooseFont = { launchSafPicker(activity, EmbeddedSafOperation.Font, "*/*", EMBEDDED_FONT_MIME_TYPES) },
                onClearFont = { runAsync(activity, controller::clearFont) },
            )
        }
        restoreSettingsSwitchMotion(nextScroll, switchMotion)
        val targetScroll = scrollPositions[navigation.page] ?: 0
        nextScroll.post { nextScroll.scrollTo(0, targetScroll) }
        if (pageChanged) pageMotion.enter(if (navigation.page == EmbeddedSettingsPage.MAIN) -1 else 1)
        if (Build.VERSION.SDK_INT >= 28) root.accessibilityPaneTitle = title.text
    }

    var refreshPending = false
    refreshView = {
        if (!refreshPending) {
            refreshPending = true
            root.post {
                refreshPending = false
                if (settingsPageSurface?.root === root) renderPage()
            }
        }
    }

    val surface = EmbeddedSettingsPageSurface(activity, root,
        onBack = {
            if (navigation.back()) {
                renderPage()
            } else if (!draft.hasUnsavedChanges || draft.save()) {
                dismissSettingsPage()
            } else {
                Toast.makeText(activity, "保存 AM++ 设置失败，请重试", Toast.LENGTH_SHORT).show()
            }
        },
        onClosed = { pageMotion.cancel(); settingsPageSurface = null; pageRefresh = null },
    )
    back.setOnClickListener { surface.handleBack() }
    settingsPageSurface = surface
    pageRefresh = {
        // SAF/worker operations can change font or lyrics settings externally.
        draft.reload(controller.currentSettings())
        refreshView()
    }
    renderPage()
    if (!surface.attach()) {
        dismissSettingsPage()
        Toast.makeText(activity, "无法打开 AM++ 设置页面", Toast.LENGTH_SHORT).show()
    } else {
        pageMotion.enter(1)
    }
}
