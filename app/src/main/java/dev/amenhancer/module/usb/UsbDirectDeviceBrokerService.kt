package dev.amenhancer.module.usb

import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.UsbDirectIpc

/**
 * Owns Android USB permission and lends a dup'd usbfs FD plus immutable UAC
 * metadata to the hooked Apple Music process. It deliberately retains no
 * claimed interface or streaming session; native Apple Music owns that entire
 * lifecycle.
 */
class UsbDirectDeviceBrokerService : Service() {
    private val handler = Handler(Looper.getMainLooper()) { message ->
        if (!isAuthorizedUid(message.sendingUid)) {
            replyError(message, "USB Direct broker rejected caller uid=${message.sendingUid}")
            return@Handler true
        }
        when (message.what) {
            UsbDirectIpc.WHAT_ACQUIRE -> acquire(message)
            UsbDirectIpc.WHAT_RELEASE -> replyOk(message, null)
            else -> replyError(message, "Unknown USB Direct broker command")
        }
        true
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun acquire(message: Message) {
        val sampleRate = message.data.getInt(UsbDirectIpc.KEY_SAMPLE_RATE)
        val encoding = message.data.getInt(UsbDirectIpc.KEY_ENCODING)
        val channels = message.data.getInt(UsbDirectIpc.KEY_CHANNELS)
        if (sampleRate <= 0 || channels <= 0) {
            replyError(message, "Invalid AudioTrack format for USB Direct")
            return
        }

        val usbManager = getSystemService(UsbManager::class.java)
        if (usbManager == null) {
            replyError(message, "UsbManager unavailable")
            return
        }
        val device = usbManager.deviceList.values
            .firstOrNull { usbManager.hasPermission(it) && isAudioDevice(it) }
        if (device == null) {
            replyError(message, "USB permission required: open AM++ USB settings and authorize the DAC")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            replyError(message, "UsbManager.openDevice failed")
            return
        }

        val rawDescriptors = runCatching { connection.rawDescriptors }.getOrElse { error ->
            connection.close()
            replyError(message, "USB descriptor read failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        val preferredBits = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
            -> 32
            else -> {
                connection.close()
                replyError(message, "Unsupported AudioTrack encoding=$encoding")
                return
            }
        }
        val alternatives = UsbAudioDescriptorParser.parse(rawDescriptors)
        val alternative = UsbAudioDescriptorParser.select(
            alternatives,
            sampleRate = sampleRate,
            channels = channels,
            preferredBits = preferredBits,
        )
        if (alternative == null) {
            connection.close()
            val deviceLabel = device.productName?.takeIf(String::isNotBlank) ?: device.deviceName
            val reason = UsbAudioDescriptorParser.selectionFailureReason(
                alternatives,
                sampleRate,
                channels,
            )
            replyError(message, "$deviceLabel: $reason")
            return
        }
        if (
            alternative.isUac2 &&
            (alternative.audioControlInterface < 0 || alternative.clockSourceId <= 0)
        ) {
            connection.close()
            replyError(message, "Selected UAC2 stream is missing clock-source metadata")
            return
        }

        val fixedSampleRateMatch =
            !alternative.isUac2 &&
                alternative.sampleRates.size == 1 &&
                sampleRate in alternative.sampleRates

        val parcelFd = runCatching {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor)
        }.getOrElse { error ->
            connection.close()
            replyError(message, "USB FD duplication failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }

        val data = Bundle().apply {
            putInt(UsbDirectIpc.KEY_RESULT, UsbDirectIpc.RESULT_OK)
            putParcelable(UsbDirectIpc.KEY_FD, parcelFd)
            putInt(UsbDirectIpc.KEY_SAMPLE_RATE, sampleRate)
            putInt(UsbDirectIpc.KEY_ENCODING, encoding)
            putInt(UsbDirectIpc.KEY_CHANNELS, channels)
            putInt(UsbDirectIpc.KEY_INTERFACE_NUMBER, alternative.interfaceNumber)
            putInt(UsbDirectIpc.KEY_ALTERNATE_SETTING, alternative.alternateSetting)
            putInt(UsbDirectIpc.KEY_AUDIO_CONTROL_INTERFACE, alternative.audioControlInterface)
            putInt(UsbDirectIpc.KEY_CLOCK_SOURCE_ID, alternative.clockSourceId)
            putBoolean(UsbDirectIpc.KEY_FIXED_SAMPLE_RATE_MATCH, fixedSampleRateMatch)
            putInt(UsbDirectIpc.KEY_ENDPOINT_ADDRESS, alternative.endpointAddress)
            putInt(UsbDirectIpc.KEY_MAX_PACKET_SIZE, alternative.maxPacketSize)
            putInt(UsbDirectIpc.KEY_INTERVAL, alternative.interval)
            putInt(UsbDirectIpc.KEY_FEEDBACK_ENDPOINT_ADDRESS, alternative.feedbackEndpointAddress)
            putInt(UsbDirectIpc.KEY_FEEDBACK_MAX_PACKET_SIZE, alternative.feedbackMaxPacketSize)
            putInt(UsbDirectIpc.KEY_FEEDBACK_INTERVAL, alternative.feedbackInterval)
            putInt(UsbDirectIpc.KEY_SUBSLOT_BYTES, alternative.subslotBytes)
            putInt(UsbDirectIpc.KEY_BIT_RESOLUTION, alternative.bitResolution)
            putInt(UsbDirectIpc.KEY_PROTOCOL, alternative.protocol)
            putString(UsbDirectIpc.KEY_DEVICE_NAME, device.productName ?: device.deviceName)
            putInt(UsbDirectIpc.KEY_VENDOR_ID, device.vendorId)
            putInt(UsbDirectIpc.KEY_PRODUCT_ID, device.productId)
        }
        replyOk(message, data)

        // Messenger.send() has marshalled/duplicated the outgoing PFD by the
        // time it returns. The broker closes both local descriptors and keeps
        // no USB streaming ownership after replying.
        runCatching { parcelFd.close() }
        runCatching { connection.close() }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean = runCatching {
        device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
    }.getOrDefault(false)

    private fun isAuthorizedUid(uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        return packageManager.getPackagesForUid(uid)
            ?.any { it == ModuleConstants.TARGET_PACKAGE }
            ?: false
    }

    private fun replyOk(message: Message, payload: Bundle?) {
        val replyTo = message.replyTo ?: return
        val result = Message.obtain(null, UsbDirectIpc.WHAT_RESULT).apply {
            data = payload ?: Bundle().apply {
                putInt(UsbDirectIpc.KEY_RESULT, UsbDirectIpc.RESULT_OK)
            }
        }
        runCatching { replyTo.send(result) }
    }

    private fun replyError(message: Message, error: String) {
        val replyTo = message.replyTo ?: return
        val result = Message.obtain(null, UsbDirectIpc.WHAT_RESULT).apply {
            data = Bundle().apply {
                putInt(UsbDirectIpc.KEY_RESULT, UsbDirectIpc.RESULT_ERROR)
                putString(UsbDirectIpc.KEY_ERROR, error)
            }
        }
        runCatching { replyTo.send(result) }
    }
}
