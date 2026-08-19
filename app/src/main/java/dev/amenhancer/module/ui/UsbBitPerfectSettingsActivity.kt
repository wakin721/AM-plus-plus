package dev.amenhancer.module.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
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
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.R
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore

/** Dedicated USB output settings and live audio-path diagnostics page. */
class UsbBitPerfectSettingsActivity : Activity() {
    private lateinit var store: ConfigStore
    private lateinit var requester: UsbBitPerfectStatusRequester
    private lateinit var palette: Palette
    private lateinit var toggle: Switch
    private lateinit var exclusiveToggle: Switch
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var appleMusicValue: TextView
    private lateinit var mixerValue: TextView
    private lateinit var usbValue: TextView
    private var suppressToggleCallback = false

    private val serviceListener: (XposedServiceSnapshot) -> Unit = { snapshot ->
        runOnUiThread { updateToggles(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        requester = UsbBitPerfectStatusRequester(this)
        palette = Palette.resolve(this)
        configureSystemBars()
        setContentView(buildScreen().also(::applySystemBarInsets))
        updateToggles(ModuleApplication.serviceSnapshot)
    }

    override fun onResume() {
        super.onResume()
        ModuleApplication.addServiceListener(serviceListener)
        updateToggles(ModuleApplication.serviceSnapshot)
        refreshStatus()
    }

    override fun onPause() {
        ModuleApplication.removeServiceListener(serviceListener)
        super.onPause()
    }

    override fun onDestroy() {
        requester.cancel()
        super.onDestroy()
    }

    private fun buildScreen(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        addView(buildTopBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        ))
        addView(divider())
        addView(ScrollView(this@UsbBitPerfectSettingsActivity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(32))
                addView(toggleCard())
                addView(spacer(20))
                addView(audioPathCard())
            }, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildTopBar(): View = FrameLayout(this).apply {
        setPadding(dp(12), 0, dp(24), 0)
        addView(ImageView(this@UsbBitPerfectSettingsActivity).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            contentDescription = "返回"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "USB 音频输出"
            textSize = 20f
            setTextColor(palette.onSurface)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
        ).apply { marginStart = dp(64) })
    }

    private fun toggleCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(palette.surface, 20, palette.outline)
        elevation = dp(2).toFloat()
        clipToOutline = true

        addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(92)
            setPadding(dp(16), dp(14), dp(10), dp(14))
            addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "启用 USB Bit-Perfect"
                    textSize = 17f
                    setTextColor(palette.onSurface)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "Android 14+ · USB DAC · 修改后需重启 Apple Music"
                    textSize = 13.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toggle = Switch(this@UsbBitPerfectSettingsActivity).apply {
                showText = false
                contentDescription = "启用 USB Bit-Perfect"
                setOnCheckedChangeListener { _, enabled ->
                    if (suppressToggleCallback) return@setOnCheckedChangeListener
                    store.saveSettings(store.settings().copy(usbBitPerfectEnabled = enabled))
                    if (::exclusiveToggle.isInitialized) {
                        exclusiveToggle.isEnabled = enabled && ModuleApplication.serviceSnapshot.isRemoteAvailable
                        exclusiveToggle.alpha = if (exclusiveToggle.isEnabled) 1f else 0.58f
                    }
                    statusTitle.text = "等待重启 Apple Music"
                    statusMessage.text = if (enabled) {
                        "USB 输出功能将在 Apple Music 下次启动时安装；开始播放后刷新即可核验链路。"
                    } else {
                        "关闭将在 Apple Music 下次启动后完全生效。"
                    }
                    mixerValue.text = "等待 Apple Music 重启"
                }
            }
            addView(toggle, LinearLayout.LayoutParams(dp(64), dp(48)))
            setOnClickListener { if (toggle.isEnabled) toggle.isChecked = !toggle.isChecked }
        })

        addView(divider())

        addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(108)
            setPadding(dp(16), dp(14), dp(10), dp(14))
            addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "实验性 AAudio 独占输出"
                    textSize = 17f
                    setTextColor(palette.onSurface)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "尝试接管 Java AudioTrack.write PCM 并请求 EXCLUSIVE；任何环节失败都会恢复原输出"
                    textSize = 13.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            exclusiveToggle = Switch(this@UsbBitPerfectSettingsActivity).apply {
                showText = false
                contentDescription = "实验性 AAudio 独占输出"
                setOnCheckedChangeListener { _, enabled ->
                    if (suppressToggleCallback) return@setOnCheckedChangeListener
                    store.saveSettings(store.settings().copy(usbExclusiveAaudioEnabled = enabled))
                    statusTitle.text = "等待重启 Apple Music"
                    statusMessage.text = if (enabled) {
                        "重启 Apple Music 后，AM++ 会先确认可接管 PCM 写入，再尝试 AAudio EXCLUSIVE。"
                    } else {
                        "重启 Apple Music 后将只使用 Bit-Perfect/系统输出路径。"
                    }
                }
            }
            addView(exclusiveToggle, LinearLayout.LayoutParams(dp(64), dp(48)))
            setOnClickListener {
                if (exclusiveToggle.isEnabled) exclusiveToggle.isChecked = !exclusiveToggle.isChecked
            }
        })
    }

    private fun audioPathCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(palette.surface, 20, palette.outline)
        elevation = dp(2).toFloat()
        clipToOutline = true
        setPadding(dp(16), dp(18), dp(16), dp(14))

        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "音频链路"
            textSize = 14f
            setTextColor(palette.primary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "独占模式尝试接管 AudioTrack.write 的解码后 PCM；这里只显示解码后数据，不代表原始 ALAC 元数据。"
            textSize = 13f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(4), 0, dp(12))
        })

        statusTitle = TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "正在检查…"
            textSize = 18f
            setTextColor(palette.onSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        addView(statusTitle)

        val apple = pathNode("Apple Music AudioTrack", "等待播放")
        appleMusicValue = apple.value
        addView(apple.root)
        addView(pathArrow())

        val mixer = pathNode("Android 输出路径", "等待实时状态")
        mixerValue = mixer.value
        addView(mixer.root)
        addView(pathArrow())

        val usb = pathNode("USB DAC", "等待 USB 音频设备")
        usbValue = usb.value
        addView(usb.root)

        statusMessage = TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "正在向 Apple Music 进程读取实时 AudioTrack / AAudio / USB 状态"
            textSize = 13.5f
            setTextColor(palette.onSurfaceVariant)
            setPadding(0, dp(14), 0, 0)
        }
        addView(statusMessage)

        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "刷新状态"
            textSize = 14f
            setTextColor(palette.primary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "刷新 USB 音频输出状态"
            setOnClickListener { refreshStatus() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.END
            topMargin = dp(4)
        })
    }

    private fun pathNode(title: String, initial: String): PathNode = LinearLayout(this).let { root ->
        root.orientation = LinearLayout.VERTICAL
        root.background = roundedDrawable(palette.background, 16, palette.outline)
        root.setPadding(dp(14), dp(12), dp(14), dp(12))
        root.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(palette.primary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        val value = TextView(this).apply {
            text = initial
            textSize = 15f
            setTextColor(palette.onSurface)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(value)
        PathNode(root, value)
    }

    private fun pathArrow(): View = TextView(this).apply {
        text = "↓"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(palette.primary)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun updateToggles(snapshot: XposedServiceSnapshot) {
        if (!::toggle.isInitialized || !::exclusiveToggle.isInitialized) return
        val settings = store.settings()
        suppressToggleCallback = true
        toggle.isChecked = settings.usbBitPerfectEnabled
        toggle.isEnabled = snapshot.isRemoteAvailable
        toggle.alpha = if (toggle.isEnabled) 1f else 0.58f
        exclusiveToggle.isChecked = settings.usbExclusiveAaudioEnabled
        exclusiveToggle.isEnabled = snapshot.isRemoteAvailable && settings.usbBitPerfectEnabled
        exclusiveToggle.alpha = if (exclusiveToggle.isEnabled) 1f else 0.58f
        suppressToggleCallback = false
    }

    private fun refreshStatus() {
        val enabled = store.settings().usbBitPerfectEnabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            renderStatus(
                UsbBitPerfectStatusDetails(
                    state = UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID,
                    message = "需要 Android 14 或更高版本",
                ),
                enabled,
            )
            return
        }
        statusTitle.text = "正在检查…"
        statusMessage.text = "正在向 Apple Music 进程读取实时 AudioTrack / AAudio / USB 状态"
        requester.request { status ->
            if (isFinishing || isDestroyed) return@request
            renderStatus(status, enabled)
        }
    }

    private fun renderStatus(status: UsbBitPerfectStatusDetails?, enabled: Boolean) {
        if (status == null) {
            statusTitle.text = if (enabled) "无法查询实时状态" else "已关闭"
            appleMusicValue.text = "等待 Apple Music"
            mixerValue.text = if (enabled) "等待 Apple Music 重启并开始播放" else "功能已关闭"
            usbValue.text = "未取得实时设备状态"
            statusMessage.text = if (enabled) {
                "请启动或重启 Apple Music，并开始播放后再次刷新。"
            } else {
                "开启后重启 Apple Music；播放时可在这里核验实际音频链路。"
            }
            return
        }

        val stateTitle = when (status.state) {
            UsbBitPerfectStatusProtocol.STATE_ACTIVE -> "Bit-Perfect 已激活"
            UsbBitPerfectStatusProtocol.STATE_CONFIGURED -> "已配置，等待路由"
            UsbBitPerfectStatusProtocol.STATE_WAITING_PLAYBACK -> "等待播放"
            UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE -> "等待 USB 路由"
            UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "未连接 USB DAC"
            UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE -> "当前未走 USB"
            UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED -> "格式不匹配"
            UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED -> "请求失败"
            UsbBitPerfectStatusProtocol.STATE_UNSUPPORTED_ANDROID -> "系统不支持"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED -> "独占模式待命"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_READY -> "已检测可接管 PCM"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED -> "AAudio 独占流已建立"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE -> "AAudio 独占已激活"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_FALLBACK -> "独占失败，已回退"
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_UNSUPPORTED_PATH -> "无法安全接管"
            else -> "未激活"
        }
        statusTitle.text = if (!enabled && (
                status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE ||
                    status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE
            )
        ) {
            "仍在运行（需重启）"
        } else {
            stateTitle
        }

        appleMusicValue.text = formatAudio(
            status.trackSampleRate,
            status.trackEncoding,
            status.trackChannels,
        ) ?: when (status.state) {
            UsbBitPerfectStatusProtocol.STATE_WAITING_PLAYBACK,
            UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED,
            -> "等待媒体 AudioTrack"
            else -> "未报告 AudioTrack 格式"
        }

        val mixerFormat = formatAudio(
            status.mixerSampleRate,
            status.mixerEncoding,
            status.mixerChannels,
        )
        mixerValue.text = when {
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE ->
                "$mixerFormat · AAUDIO EXCLUSIVE · PCM 已接管"
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_CONFIGURED ->
                "$mixerFormat · AAUDIO EXCLUSIVE · 等待 PCM"
            status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_READY ->
                "AAudio EXCLUSIVE · 已确认可接管 Java PCM"
            status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ARMED ->
                "AAudio EXCLUSIVE · 等待观察 Java AudioTrack.write"
            status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_FALLBACK ->
                "AAudio EXCLUSIVE 未建立 · 已回退原输出"
            status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_UNSUPPORTED_PATH ->
                "实验性独占不支持当前 PCM 写入路径"
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE ->
                "$mixerFormat · BIT_PERFECT 已核验"
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_CONFIGURED ->
                "$mixerFormat · BIT_PERFECT 已配置"
            mixerFormat != null -> "$mixerFormat · $stateTitle"
            status.state == UsbBitPerfectStatusProtocol.STATE_FORMAT_UNSUPPORTED ->
                "无与 AudioTrack 完全匹配的 Bit-Perfect mixer"
            status.state == UsbBitPerfectStatusProtocol.STATE_REQUEST_FAILED ->
                "Bit-Perfect preferred mixer 请求失败"
            status.state == UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "等待 USB DAC"
            status.state == UsbBitPerfectStatusProtocol.STATE_NON_USB_ROUTE -> "当前路由未进入 USB"
            else -> "等待输出路径"
        }

        usbValue.text = status.deviceName ?: when (status.state) {
            UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "未检测到 USB 音频输出"
            UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE -> "等待系统确定 USB 路由"
            else -> "未报告 USB 设备名称"
        }

        val details = mutableListOf<String>()
        if (!enabled && (
                status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE ||
                    status.state == UsbBitPerfectStatusProtocol.STATE_EXCLUSIVE_ACTIVE
            )
        ) {
            details += "当前 Apple Music 进程仍在使用已安装的 USB 输出 Hook；重启后将按关闭设置生效。"
        }
        status.message?.let(details::add)
        statusMessage.text = details.joinToString("\n").ifBlank {
            if (enabled) "当前没有更多运行时说明。" else "功能当前已关闭。"
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

    private fun configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        var flags = window.decorView.systemUiVisibility
        flags = if (palette.isDark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        root.setOnApplyWindowInsetsListener { view, insets ->
            val statusBars = insets.getInsets(WindowInsets.Type.statusBars())
            val navigationBars = insets.getInsets(WindowInsets.Type.navigationBars())
            view.setPadding(0, statusBars.top, 0, navigationBars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun roundedDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(palette.outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PathNode(
        val root: LinearLayout,
        val value: TextView,
    )

    private data class Palette(
        val background: Int,
        val surface: Int,
        val outline: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val isDark: Boolean,
    ) {
        companion object {
            fun resolve(activity: Activity): Palette {
                val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (dark) {
                    Palette(
                        background = Color.rgb(20, 16, 18),
                        surface = Color.rgb(34, 27, 30),
                        outline = Color.rgb(77, 62, 67),
                        onSurface = Color.rgb(248, 239, 242),
                        onSurfaceVariant = Color.rgb(213, 195, 201),
                        primary = Color.rgb(255, 139, 176),
                        isDark = true,
                    )
                } else {
                    Palette(
                        background = Color.rgb(255, 248, 250),
                        surface = Color.WHITE,
                        outline = Color.rgb(235, 221, 226),
                        onSurface = Color.rgb(34, 27, 30),
                        onSurfaceVariant = Color.rgb(113, 99, 104),
                        primary = Color.rgb(210, 56, 108),
                        isDark = false,
                    )
                }
            }
        }
    }
}
