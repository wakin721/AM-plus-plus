package dev.amenhancer.module.ui

import android.app.Activity
import android.media.AudioFormat
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.UsbBitPerfectStatusDetails
import dev.amenhancer.module.UsbBitPerfectStatusProtocol
import dev.amenhancer.module.model.ModuleSettings

internal fun EmbeddedSettingsHost.renderEmbeddedUsbPage(
    activity: Activity,
    parent: LinearLayout,
    settings: ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    fun requestPermission() {
        if (!controller.requestUsbPermission()) {
            Toast.makeText(activity, "无法请求 USB 授权，请检查独占开关与模块连接", Toast.LENGTH_LONG).show()
        }
    }
    parent.addView(embeddedCard(activity, "输出") {
        addView(embeddedSettingRow(activity, "启用 USB 音频增强",
            "Android 14 及以上支持 Bit-Perfect；更改后需重启 Apple Music",
            settings.usbBitPerfectEnabled) {
            onSettingsChanged(settings.copy(usbBitPerfectEnabled = it))
        })
        addView(embeddedDivider(activity))
        addView(embeddedSettingRow(activity, "实验性 USB 直通独占",
            "优先 USB Direct UAC，失败时保留 Android 系统输出；更改后需重启 Apple Music",
            settings.usbDirectUacEnabled, enabled = settings.usbBitPerfectEnabled) { enabled ->
            onSettingsChanged(settings.copy(usbDirectUacEnabled = enabled))
            if (enabled) requestPermission()
        })
        addView(embeddedDivider(activity))
        addView(embeddedNavigationRow(activity, "授权 USB DAC", "连接 DAC 后请求系统 USB 访问权限",
            clickable = settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled,
            onClick = ::requestPermission))
    })
    parent.addView(embeddedSectionFooter(activity,
        "当前直通支持兼容的 UAC1/UAC2 PCM DAC；支持隐式或显式反馈端点。授权后完全停止并重新打开 Apple Music。"))
    parent.addView(embeddedSpacer(activity, 12))
    val buffersEnabled = settings.usbBitPerfectEnabled && settings.usbDirectUacEnabled
    parent.addView(embeddedCard(activity, "缓冲区") {
        val pcmValues = (ModuleSettings.MIN_USB_DIRECT_PCM_BUFFER_MS..ModuleSettings.MAX_USB_DIRECT_PCM_BUFFER_MS
            step ModuleSettings.USB_DIRECT_PCM_BUFFER_STEP_MS).toList()
        addView(embeddedNavigationRow(activity, "音频缓冲区", "${settings.usbDirectPcmBufferMs} ms",
            clickable = buffersEnabled, onClick = {
                showAppleMusicChoiceDialog(activity, "音频缓冲区", pcmValues.map { "$it ms" },
                    pcmValues.indexOf(settings.usbDirectPcmBufferMs)) { index ->
                    onSettingsChanged(settings.copy(usbDirectPcmBufferMs = pcmValues[index]))
                }
            }))
        addView(embeddedDivider(activity))
        val transferValues = ModuleSettings.USB_DIRECT_TRANSFER_BUFFER_PRESETS_MS.sorted()
        fun transferLabel(value: Int) = if (value == 0) "自动" else "$value ms"
        addView(embeddedNavigationRow(activity, "USB 传输缓冲", transferLabel(settings.usbDirectTransferBufferMs),
            clickable = buffersEnabled, onClick = {
                showAppleMusicChoiceDialog(activity, "USB 传输缓冲", transferValues.map(::transferLabel),
                    transferValues.indexOf(settings.usbDirectTransferBufferMs)) { index ->
                    onSettingsChanged(settings.copy(usbDirectTransferBufferMs = transferValues[index]))
                }
            }))
    })
    parent.addView(embeddedSectionFooter(activity, "较大的音频缓冲有助于减少断音；更改缓冲参数后需重启 Apple Music。"))
    parent.addView(embeddedSpacer(activity, 12))

    fun valueView() = TextView(activity).apply {
        textSize = 14f
        setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
        setPadding(dp(activity, 20), dp(activity, 6), dp(activity, 20), dp(activity, 16))
    }
    val statusTitle = valueView().apply { textSize = 17f; setTextColor(EmbeddedSettingsPalette.onSurface) }
    val appleMusicValue = valueView()
    val mixerValue = valueView()
    val usbValue = valueView()
    val statusMessage = valueView()
    val status = runCatching { controller.usbStatus() }.getOrNull()
    renderUsbStatus(status, settings.usbBitPerfectEnabled, statusTitle, appleMusicValue, mixerValue, usbValue, statusMessage)
    parent.addView(embeddedCard(activity, "音频链路") {
        addView(statusTitle)
        fun pathNode(label: String, value: TextView) {
            addView(embeddedDivider(activity))
            addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(value)
        }
        pathNode("Apple Music AudioTrack", appleMusicValue)
        pathNode("输出引擎", mixerValue)
        pathNode("USB DAC", usbValue)
        addView(embeddedDivider(activity))
        addView(statusMessage)
        addView(embeddedNavigationRow(activity, "刷新状态", "读取当前播放与 DAC 输出状态",
            onClick = { pageRefresh?.invoke() }))
    })
}

private fun renderUsbStatus(status: UsbBitPerfectStatusDetails?, enabled: Boolean,
    statusTitle: TextView, appleMusicValue: TextView, mixerValue: TextView,
    usbValue: TextView, statusMessage: TextView) {
        if (status == null) {
            statusTitle.text = if (enabled) "无法查询实时状态" else "已关闭"
            appleMusicValue.text = "等待 Apple Music"
            mixerValue.text = if (enabled) "等待重启 Apple Music 并开始播放" else "功能已关闭"
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
                "USB Host 权限未授权 · 请点击上方授权 USB DAC"
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

