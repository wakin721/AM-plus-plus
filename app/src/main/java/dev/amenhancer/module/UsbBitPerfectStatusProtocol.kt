package dev.amenhancer.module

/** Private live-status query contract between AM++ settings and the Apple Music process. */
internal object UsbBitPerfectStatusProtocol {
    const val REQUEST_ACTION = "dev.amenhancer.module.action.REQUEST_USB_BIT_PERFECT_STATUS"
    const val REQUEST_PERMISSION = "dev.amenhancer.module.permission.REQUEST_USB_BIT_PERFECT_STATUS"
    const val EXTRA_REQUEST_TOKEN = "dev.amenhancer.module.extra.USB_BIT_PERFECT_REQUEST_TOKEN"
    const val EXTRA_RESULT_RECEIVER = "dev.amenhancer.module.extra.USB_BIT_PERFECT_RESULT_RECEIVER"
    const val EXTRA_STATE = "dev.amenhancer.module.extra.USB_BIT_PERFECT_STATE"
    const val EXTRA_DEVICE_NAME = "dev.amenhancer.module.extra.USB_BIT_PERFECT_DEVICE_NAME"
    const val EXTRA_TRACK_SAMPLE_RATE = "dev.amenhancer.module.extra.USB_BIT_PERFECT_TRACK_SAMPLE_RATE"
    const val EXTRA_TRACK_ENCODING = "dev.amenhancer.module.extra.USB_BIT_PERFECT_TRACK_ENCODING"
    const val EXTRA_TRACK_CHANNELS = "dev.amenhancer.module.extra.USB_BIT_PERFECT_TRACK_CHANNELS"
    const val EXTRA_MIXER_SAMPLE_RATE = "dev.amenhancer.module.extra.USB_BIT_PERFECT_MIXER_SAMPLE_RATE"
    const val EXTRA_MIXER_ENCODING = "dev.amenhancer.module.extra.USB_BIT_PERFECT_MIXER_ENCODING"
    const val EXTRA_MIXER_CHANNELS = "dev.amenhancer.module.extra.USB_BIT_PERFECT_MIXER_CHANNELS"
    const val EXTRA_MESSAGE = "dev.amenhancer.module.extra.USB_BIT_PERFECT_MESSAGE"

    const val STATE_WAITING_PLAYBACK = "waiting_playback"
    const val STATE_WAITING_ROUTE = "waiting_route"
    const val STATE_NO_USB_DEVICE = "no_usb_device"
    const val STATE_NON_USB_ROUTE = "non_usb_route"
    const val STATE_FORMAT_UNSUPPORTED = "format_unsupported"
    const val STATE_REQUEST_FAILED = "request_failed"
    const val STATE_CONFIGURED = "configured"
    const val STATE_ACTIVE = "active"
    const val STATE_NOT_ACTIVE = "not_active"
    const val STATE_UNSUPPORTED_ANDROID = "unsupported_android"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_AVAILABLE = 1
}

internal data class UsbBitPerfectStatusDetails(
    val state: String,
    val deviceName: String? = null,
    val trackSampleRate: Int = 0,
    val trackEncoding: Int = 0,
    val trackChannels: Int = 0,
    val mixerSampleRate: Int = 0,
    val mixerEncoding: Int = 0,
    val mixerChannels: Int = 0,
    val message: String? = null,
)
