package dev.amenhancer.module.usb

import android.hardware.usb.UsbConstants

internal fun interface UsbControlTransfer {
    fun transfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeout: Int,
    ): Int
}

internal object UsbUacSampleRateControl {
    fun configureUac2(
        sampleRate: Int,
        clockSourceId: Int,
        audioControlInterface: Int,
        controlTransfer: UsbControlTransfer,
    ): Boolean {
        if (sampleRate <= 0 || clockSourceId <= 0 || audioControlInterface < 0) return false
        val currentRate = readCurrentRate(
            clockSourceId = clockSourceId,
            audioControlInterface = audioControlInterface,
            controlTransfer = controlTransfer,
        )
        if (currentRate == sampleRate) return true
        if (!writeTargetRate(sampleRate, clockSourceId, audioControlInterface, controlTransfer)) {
            return false
        }
        if (currentRate == null) return true
        return readCurrentRate(clockSourceId, audioControlInterface, controlTransfer) == sampleRate
    }

    private fun writeTargetRate(
        sampleRate: Int,
        clockSourceId: Int,
        audioControlInterface: Int,
        controlTransfer: UsbControlTransfer,
    ): Boolean {
        val payload = ByteArray(UAC2_SAMPLE_RATE_BYTES).apply {
            this[0] = (sampleRate and 0xff).toByte()
            this[1] = ((sampleRate ushr 8) and 0xff).toByte()
            this[2] = ((sampleRate ushr 16) and 0xff).toByte()
            this[3] = ((sampleRate ushr 24) and 0xff).toByte()
        }
        return controlTransfer.transfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE,
            UAC_CUR,
            SAMPLING_FREQ_CONTROL shl 8,
            (clockSourceId shl 8) or (audioControlInterface and 0xff),
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS,
        ) == payload.size
    }

    private fun readCurrentRate(
        clockSourceId: Int,
        audioControlInterface: Int,
        controlTransfer: UsbControlTransfer,
    ): Int? {
        val payload = ByteArray(UAC2_SAMPLE_RATE_BYTES)
        val transferred = controlTransfer.transfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_INTERFACE,
            UAC_CUR,
            SAMPLING_FREQ_CONTROL shl 8,
            (clockSourceId shl 8) or (audioControlInterface and 0xff),
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS,
        )
        if (transferred != payload.size) return null
        return payload.u32le()
    }

    private fun ByteArray.u32le(): Int =
        (this[0].toInt() and 0xff) or
            ((this[1].toInt() and 0xff) shl 8) or
            ((this[2].toInt() and 0xff) shl 16) or
            ((this[3].toInt() and 0xff) shl 24)

    private const val CONTROL_TIMEOUT_MS = 1_000
    private const val UAC_CUR = 0x01
    private const val SAMPLING_FREQ_CONTROL = 0x01
    private const val USB_RECIPIENT_INTERFACE = 0x01
    private const val UAC2_SAMPLE_RATE_BYTES = 4
}
