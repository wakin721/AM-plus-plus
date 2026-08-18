package dev.amenhancer.module.ui

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import dev.amenhancer.module.config.ConfigStore
import java.util.WeakHashMap

internal object UsbBitPerfectSettingsInjector : Application.ActivityLifecycleCallbacks {
    private val listeners = WeakHashMap<SettingsActivity, ViewTreeObserver.OnGlobalLayoutListener>()
    private val requesters = WeakHashMap<SettingsActivity, UsbBitPerfectStatusRequester>()
    private val statusViews = WeakHashMap<SettingsActivity, StatusViews>()

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val settings = activity as? SettingsActivity ?: return
        requesters[settings] = UsbBitPerfectStatusRequester(settings)
        val listener = ViewTreeObserver.OnGlobalLayoutListener { injectIfNeeded(settings) }
        listeners[settings] = listener
        settings.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun onActivityResumed(activity: Activity) {
        val settings = activity as? SettingsActivity ?: return
        refreshStatus(settings)
    }

    override fun onActivityDestroyed(activity: Activity) {
        val settings = activity as? SettingsActivity ?: return
        requesters.remove(settings)?.cancel()
        statusViews.remove(settings)
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
        val store = ConfigStore(activity)
        val writable = ModuleApplication.serviceSnapshot.isRemoteAvailable

        val statusTitle = TextView(activity).apply {
            text = "正在检查…"
            textSize = 16f
            setTextColor(onSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val statusDetails = TextView(activity).apply {
            text = "正在读取 Apple Music 实时输出状态"
            textSize = 13.5f
            setTextColor(onVariant)
            setPadding(0, dp(4), 0, 0)
        }

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

            val toggle = Switch(activity).apply {
                isChecked = store.settings().usbBitPerfectEnabled
                isEnabled = writable
                showText = false
                contentDescription = "USB Bit-Perfect"
                setOnCheckedChangeListener { _, enabled ->
                    store.saveSettings(store.settings().copy(usbBitPerfectEnabled = enabled))
                    statusTitle.text = "等待重启 Apple Music"
                    statusDetails.text = if (enabled) {
                        "功能将在重启 Apple Music 后安装；开始播放后可核验是否真正激活"
                    } else {
                        "关闭将在重启 Apple Music 后完全生效"
                    }
                }
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(88)
                alpha = if (writable) 1f else 0.58f
                setPadding(dp(16), dp(10), dp(10), dp(14))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = "USB Bit-Perfect"
                        textSize = 17f
                        setTextColor(onSurface)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    addView(TextView(activity).apply {
                        text = "Android 14+ · USB DAC · 精确格式匹配时绕过系统 SRC/混音；需重开 Apple Music"
                        textSize = 13.5f
                        setTextColor(onVariant)
                        setPadding(0, dp(4), dp(8), 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toggle, LinearLayout.LayoutParams(dp(64), dp(48)))
                setOnClickListener { if (toggle.isEnabled) toggle.isChecked = !toggle.isChecked }
            })

            addView(View(activity).apply {
                setBackgroundColor(outline)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(10))
                addView(TextView(activity).apply {
                    text = "运行状态"
                    textSize = 13f
                    setTextColor(primary)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(statusTitle.apply { setPadding(0, dp(5), 0, 0) })
                addView(statusDetails)
                addView(TextView(activity).apply {
                    text = "刷新状态"
                    textSize = 14f
                    setTextColor(primary)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "刷新 USB Bit-Perfect 状态"
                    setOnClickListener { refreshStatus(activity) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.END
                    topMargin = dp(4)
                })
            })
        })

        statusViews[activity] = StatusViews(statusTitle, statusDetails)
        refreshStatus(activity)
    }

    private fun refreshStatus(activity: SettingsActivity) {
        val views = statusViews[activity] ?: return
        val enabled = ConfigStore(activity).settings().usbBitPerfectEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            renderStatus(
                activity,
                views,
                UsbBitPerfectStatusDetails(
                    state = UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID,
                    message = "需要 Android 14 或更高版本",
                ),
                enabled,
            )
            return
        }
        views.title.text = "正在检查…"
        views.details.text = "正在向 Apple Music 进程读取实时 AudioTrack / USB mixer 状态"
        val requester = requesters[activity] ?: UsbBitPerfectStatusRequester(activity).also {
            requesters[activity] = it
        }
        requester.request { status ->
            if (activity.isFinishing || activity.isDestroyed) return@request
            renderStatus(activity, views, status, enabled)
        }
    }

    private fun renderStatus(
        activity: SettingsActivity,
        views: StatusViews,
        status: UsbBitPerfectStatusDetails?,
        enabled: Boolean,
    ) {
        if (status == null) {
            views.title.text = if (enabled) "无法查询实时状态" else "已关闭"
            views.details.text = if (enabled) {
                "请启动或重启 Apple Music，并开始播放后再次刷新"
            } else {
                "开启后重启 Apple Music；播放时可在这里核验是否真正激活"
            }
            return
        }

        val title = when (status.state) {
            UsbBitPerfectStatusProtocol.STATE_ACTIVE -> "已激活"
            UsbBitPerfectStatusProtocol.STATE_CONFIGURED -> "已配置，等待路由"
            UsbBitPerfectStatusProtocol.STATE_WAITING_PLAYBACK -> "等待播放"
            UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE -> "等待 USB 路由"
            UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "未连接 USB DAC"
            UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE -> "当前未走 USB"
            UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED -> "格式不匹配"
            UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED -> "请求失败"
            UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID -> "系统不支持"
            else -> "未激活"
        }
        views.title.text = if (!enabled && status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE) {
            "仍在运行（需重启）"
        } else {
            title
        }

        val lines = mutableListOf<String>()
        status.deviceName?.let { lines += "设备：$it" }
        formatAudio(
            status.trackSampleRate,
            status.trackEncoding,
            status.trackChannels,
        )?.let { lines += "Apple Music：$it" }
        formatAudio(
            status.mixerSampleRate,
            status.mixerEncoding,
            status.mixerChannels,
        )?.let { lines += "USB Mixer：$it" }
        if (!enabled && status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE) {
            lines += "当前 Apple Music 进程仍在使用 Bit-Perfect；重启后将按关闭设置生效"
        }
        status.message?.let { lines += it }
        views.details.text = lines.joinToString("\n").ifBlank {
            if (enabled) "没有更多运行时详情" else "功能当前已关闭"
        }
    }

    private fun formatAudio(sampleRate: Int, encoding: Int, channels: Int): String? {
        if (sampleRate <= 0 && encoding <= 0 && channels <= 0) return null
        val encodingName = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM Float"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
            else -> "encoding $encoding"
        }
        return "$sampleRate Hz / $encodingName / ${channels}ch"
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
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private data class StatusViews(
        val title: TextView,
        val details: TextView,
    )

    private const val CARD_TAG = "ampp_usb_bit_perfect_card"
}
