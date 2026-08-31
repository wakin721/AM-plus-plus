package dev.amenhancer.module

/** Messenger contract used to lend a permission-backed USB device FD to the hooked Apple Music process. */
internal object UsbDirectIpc {
    const val SERVICE_CLASS = "dev.amenhancer.module.usb.UsbDirectDeviceBrokerService"

    const val WHAT_ACQUIRE = 1
    const val WHAT_RELEASE = 2
    const val WHAT_RESULT = 3

    const val RESULT_OK = 1
    const val RESULT_ERROR = 0

    const val KEY_RESULT = "result"
    const val KEY_ERROR = "error"
    const val KEY_FD = "fd"
    const val KEY_SAMPLE_RATE = "sample_rate"
    const val KEY_ENCODING = "encoding"
    const val KEY_CHANNELS = "channels"
    const val KEY_INTERFACE_NUMBER = "interface_number"
    const val KEY_ALTERNATE_SETTING = "alternate_setting"
    const val KEY_ENDPOINT_ADDRESS = "endpoint_address"
    const val KEY_MAX_PACKET_SIZE = "max_packet_size"
    const val KEY_INTERVAL = "interval"
    const val KEY_FEEDBACK_ENDPOINT_ADDRESS = "feedback_endpoint_address"
    const val KEY_FEEDBACK_MAX_PACKET_SIZE = "feedback_max_packet_size"
    const val KEY_FEEDBACK_INTERVAL = "feedback_interval"
    const val KEY_SUBSLOT_BYTES = "subslot_bytes"
    const val KEY_BIT_RESOLUTION = "bit_resolution"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_VENDOR_ID = "vendor_id"
    const val KEY_PRODUCT_ID = "product_id"
}
