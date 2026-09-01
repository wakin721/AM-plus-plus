package dev.amenhancer.module.ui

import android.app.Activity
import android.content.Context
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.R
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import dev.amenhancer.module.XposedServiceSnapshot
import dev.amenhancer.module.config.ConfigStore
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.ui.theme.AmppExpressiveTheme
import dev.amenhancer.module.ui.theme.AppAppearanceSettings
import dev.amenhancer.module.ui.theme.AppUiStyle
import dev.amenhancer.module.ui.theme.AppearancePreferences
import dev.amenhancer.module.usb.UsbDirectPermissionActivity

/** Dedicated USB output settings and live audio-path diagnostics page. */
class UsbBitPerfectSettingsActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var requester: UsbBitPerfectStatusRequester
    private lateinit var palette: Palette
    private lateinit var toggle: Switch
    private lateinit var directToggle: Switch
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var appleMusicValue: TextView
    private lateinit var mixerValue: TextView
    private lateinit var usbValue: TextView
    private var suppressToggleCallback = false
    private var expressiveUiActive = false
    private var expressiveSettings by mutableStateOf(ModuleSettings())
    private var expressiveSnapshot by mutableStateOf(XposedServiceSnapshot.waiting())
    private var expressiveStatus by mutableStateOf<UsbBitPerfectStatusDetails?>(null)
    private var expressiveChecking by mutableStateOf(false)
    private lateinit var appearancePreferences: AppearancePreferences
    private var activeAppearance = AppAppearanceSettings()

    private val serviceListener: (XposedServiceSnapshot) -> Unit = { snapshot ->
        runOnUiThread { updateToggles(snapshot) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearancePreferences.themedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearancePreferences = AppearancePreferences(this)
        activeAppearance = appearancePreferences.settings()
        store = ConfigStore(this)
        requester = UsbBitPerfectStatusRequester(this)
        palette = Palette.resolve(this)
        syncUsbAttachHandling()
        configureSystemBars()
        expressiveUiActive = activeAppearance.style == AppUiStyle.MATERIAL3
        if (!expressiveUiActive) {
            val root = buildScreen()
            setContentView(root)
            applySystemBarInsets(root)
            updateToggles(ModuleApplication.serviceSnapshot)
            return
        }
        updateToggles(ModuleApplication.serviceSnapshot)
        setContent {
            AmppExpressiveTheme(appearance = activeAppearance) {
                UsbAudioSettingsScreen(
                    settings = expressiveSettings,
                    snapshot = expressiveSnapshot,
                    status = expressiveStatus,
                    checking = expressiveChecking,
                    actions = UsbAudioSettingsActions(
                        navigateBack = ::finish,
                        setEnabled = { enabled ->
                            store.saveSettings(store.settings().copy(usbBitPerfectEnabled = enabled))
                            syncUsbAttachHandling()
                            updateToggles(ModuleApplication.serviceSnapshot)
                        },
                        setDirectEnabled = { enabled ->
                            store.saveSettings(store.settings().copy(usbDirectUacEnabled = enabled))
                            syncUsbAttachHandling()
                            if (enabled) {
                                UsbDirectPermissionActivity.requestCurrentDevice(this)
                            }
                            updateToggles(ModuleApplication.serviceSnapshot)
                        },
                        refresh = ::refreshStatus,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::appearancePreferences.isInitialized && appearancePreferences.settings() != activeAppearance) {
            recreate()
            return
        }
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
        clipToOutline = true

        addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(92)
            setPadding(dp(16), dp(14), dp(10), dp(14))
            addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "启用 USB 音频增强"
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
                contentDescription = "启用 USB 音频增强"
                setOnCheckedChangeListener { _, enabled ->
                    if (suppressToggleCallback) return@setOnCheckedChangeListener
                    store.saveSettings(store.settings().copy(usbBitPerfectEnabled = enabled))
                    syncUsbAttachHandling()
                    updateChildToggleEnabledState()
                    statusTitle.text = "等待重启 Apple Music"
                    statusMessage.text = if (enabled) {
                        "USB 输出 Hook 将在 Apple Music 下次启动时安装；开始播放后刷新即可核验链路。"
                    } else {
                        "关闭将在 Apple Music 下次启动后完全生效；USB attach 处理同时关闭。"
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
            minimumHeight = dp(124)
            setPadding(dp(16), dp(14), dp(10), dp(14))
            addView(LinearLayout(this@UsbBitPerfectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "实验性 USB 直通独占"
                    textSize = 17f
                    setTextColor(palette.onSurface)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "Android USB Host 授权 → claim AudioStreaming interface → native usbfs isochronous；支持 UAC1/UAC2 标准显式 feedback，隐式 feedback/厂商私有格式仍会回退"
                    textSize = 13.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(4), dp(8), 0)
                })
                addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
                    text = "开启会立即请求当前 DAC 的系统 USB 权限；重新插入 DAC 时也可出现系统原生 USB 打开/授权界面。"
                    textSize = 12.5f
                    setTextColor(palette.onSurfaceVariant)
                    setPadding(0, dp(5), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            directToggle = Switch(this@UsbBitPerfectSettingsActivity).apply {
                showText = false
                contentDescription = "实验性 USB 直通独占"
                setOnCheckedChangeListener { _, enabled ->
                    if (suppressToggleCallback) return@setOnCheckedChangeListener
                    store.saveSettings(store.settings().copy(usbDirectUacEnabled = enabled))
                    syncUsbAttachHandling()
                    statusTitle.text = "等待重启 Apple Music"
                    statusMessage.text = if (enabled) {
                        UsbDirectPermissionActivity.requestCurrentDevice(this@UsbBitPerfectSettingsActivity)
                        "已启用 USB Host 授权入口。授权 DAC 后重启 Apple Music；播放时将优先尝试 USB Direct。"
                    } else {
                        "已关闭 USB Direct attach 处理；重启 Apple Music 后使用原 AudioTrack/Android mixer。"
                    }
                }
            }
            addView(directToggle, LinearLayout.LayoutParams(dp(64), dp(48)))
            setOnClickListener {
                if (directToggle.isEnabled) directToggle.isChecked = !directToggle.isChecked
            }
        })

    }

    private fun audioPathCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(palette.surface, 20, palette.outline)
        clipToOutline = true
        setPadding(dp(16), dp(18), dp(16), dp(14))

        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "音频链路"
            textSize = 14f
            setTextColor(palette.primary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "优先 USB Direct UAC；失败后恢复原 AudioTrack/Android mixer。链路从 Apple Music 解码后的 AudioTrack PCM 开始，不代表原始 ALAC 元数据。"
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

        val mixer = pathNode("输出引擎", "等待实时状态")
        mixerValue = mixer.value
        addView(mixer.root)
        addView(pathArrow())

        val usb = pathNode("USB DAC", "等待 USB 音频设备")
        usbValue = usb.value
        addView(usb.root)

        statusMessage = TextView(this@UsbBitPerfectSettingsActivity).apply {
            text = "正在向 Apple Music 进程读取实时 AudioTrack / USB Direct 状态"
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
        if (expressiveUiActive) {
            expressiveSnapshot = snapshot
            expressiveSettings = store.settings()
            syncUsbAttachHandling()
            return
        }
        if (!::toggle.isInitialized || !::directToggle.isInitialized) return
        val settings = store.settings()
        suppressToggleCallback = true
        toggle.isChecked = settings.usbBitPerfectEnabled
        toggle.isEnabled = snapshot.isRemoteAvailable
        toggle.alpha = if (toggle.isEnabled) 1f else 0.58f
        directToggle.isChecked = settings.usbDirectUacEnabled
        suppressToggleCallback = false
        updateChildToggleEnabledState()
        syncUsbAttachHandling()
    }

    private fun updateChildToggleEnabledState() {
        if (!::directToggle.isInitialized) return
        val parentEnabled = store.settings().usbBitPerfectEnabled && ModuleApplication.serviceSnapshot.isRemoteAvailable
        directToggle.isEnabled = parentEnabled
        directToggle.alpha = if (parentEnabled) 1f else 0.58f
    }

    private fun syncUsbAttachHandling() {
        val settings = store.settings()
        UsbDirectPermissionActivity.setAttachHandlingEnabled(
            this,
            settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled,
        )
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
        if (expressiveUiActive) {
            expressiveChecking = true
            requester.request { status ->
                if (isFinishing || isDestroyed) return@request
                renderStatus(status, enabled)
            }
            return
        }
        statusTitle.text = "正在检查…"
        statusMessage.text = "正在向 Apple Music 进程读取实时 AudioTrack / USB Direct 状态"
        requester.request { status ->
            if (isFinishing || isDestroyed) return@request
            renderStatus(status, enabled)
        }
    }

    private fun renderStatus(status: UsbBitPerfectStatusDetails?, enabled: Boolean) {
        if (expressiveUiActive) {
            expressiveStatus = status
            expressiveChecking = false
            expressiveSettings = store.settings()
            return
        }
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
            UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED -> "USB 直通待命"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED -> "需要 USB 授权"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_ACQUIRING -> "正在取得 USB 独占"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_CONFIGURED -> "USB 直通已建立"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE -> "USB 直通独占已激活"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK -> "USB 直通失败，已回退"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_UNSUPPORTED_DEVICE -> "当前 DAC 暂不支持直通"
            UsbBitPerfectStatusProtocol.STATE_ACTIVE -> "Bit-Perfect 已激活"
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
        statusTitle.text = if (!enabled && (
                status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE ||
                    status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE
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
            UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED,
            UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED,
            -> "等待媒体 AudioTrack"
            else -> "未报告 AudioTrack 格式"
        }

        val mixerFormat = formatAudio(
            status.mixerSampleRate,
            status.mixerEncoding,
            status.mixerChannels,
        )
        mixerValue.text = when {
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE ->
                "$mixerFormat · USB DIRECT · usbfs ISO PCM"
            mixerFormat != null && status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_CONFIGURED ->
                "$mixerFormat · USB DIRECT · interface 已 claim"
            status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ACQUIRING ->
                "USB Host → claim interface → 配置 UAC"
            status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED ->
                "USB Host 权限未授权 · 请在 AM++ 重新请求"
            status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_UNSUPPORTED_DEVICE ->
                "USB Direct 不支持当前 UAC endpoint/feedback 条件"
            status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_FALLBACK ->
                "USB Direct 未建立 · 已恢复 Android 系统输出"
            status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ARMED ->
                "USB DIRECT · 等待 Java PCM"
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
            UsbBitPerfectStatusProtocol.STATE_DIRECT_PERMISSION_REQUIRED -> "等待系统 USB Host 授权"
            UsbBitPerfectStatusProtocol.STATE_DIRECT_ACQUIRING -> "正在打开已授权 USB DAC"
            UsbBitPerfectStatusProtocol.STATE_NO_USB_DEVICE -> "未检测到 USB 音频输出"
            UsbBitPerfectStatusProtocol.STATE_WAITING_ROUTE -> "等待系统确定 USB 路由"
            else -> "未报告 USB 设备名称"
        }

        val details = mutableListOf<String>()
        if (!enabled && (
                status.state == UsbBitPerfectStatusProtocol.STATE_ACTIVE ||
                    status.state == UsbBitPerfectStatusProtocol.STATE_DIRECT_ACTIVE
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
