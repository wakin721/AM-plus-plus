package dev.amenhancer.module.usb

import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
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
import android.util.Log
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.UsbDirectIpc

/**
 * Owns the Android USB permission and lends a dup'd usbfs FD to the hooked
 * Apple Music process. The exported service performs an explicit calling-UID
 * check on every Messenger command; no other application may acquire the FD.
 */
class UsbDirectDeviceBrokerService : Service() {
    private var session: ClaimedSession? = null

    private val handler = Handler(Looper.getMainLooper()) { message ->
        if (!isAuthorizedUid(message.sendingUid)) {
            replyError(message, "USB Direct broker rejected caller uid=${message.sendingUid}")
            return@Handler true
        }
        when (message.what) {
            UsbDirectIpc.WHAT_ACQUIRE -> acquire(message)
            UsbDirectIpc.WHAT_RELEASE -> {
                releaseSession()
                replyOk(message, null)
            }
            else -> replyError(message, "Unknown USB Direct broker command")
        }
        true
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        releaseSession()
        return false
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

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

        releaseSession()
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
        val usbInterface = findInterface(device, alternative)
        if (usbInterface == null) {
            connection.close()
            replyError(message, "Selected UAC alternate setting is missing from UsbDevice")
            return
        }
        val controlInterface = if (alternative.isUac2) {
            findAudioControlInterface(device, alternative.audioControlInterface)
        } else {
            null
        }
        if (alternative.isUac2 && controlInterface == null) {
            connection.close()
            replyError(
                message,
                "UAC2 AudioControl interface ${alternative.audioControlInterface} is missing from UsbDevice",
            )
            return
        }
        val claimResult = UsbInterfaceClaimTransaction.acquire(
            controlInterface = controlInterface,
            streamingInterface = usbInterface,
            isSameInterface = { first, second -> first.id == second.id },
            claimInterface = { usbInterface -> connection.claimInterface(usbInterface, true) },
            releaseInterface = { claimedInterface ->
                runCatching { connection.releaseInterface(claimedInterface) }
            },
        )
        val claims = when (claimResult) {
            is UsbInterfaceClaimResult.Acquired -> claimResult.claims
            is UsbInterfaceClaimResult.Failed -> {
                connection.close()
                replyError(
                    message,
                    "claimInterface(force=true) failed for interface ${claimResult.failedInterface.id}",
                )
                return
            }
        }
        Log.i(
            TAG,
            "Claimed UAC interfaces control=${controlInterface?.id ?: "none"} " +
                "streaming=${usbInterface.id} alt=${usbInterface.alternateSetting}",
        )
        if (!connection.setInterface(usbInterface)) {
            releaseClaims(connection, claims)
            connection.close()
            replyError(message, "setInterface alt=${usbInterface.alternateSetting} failed")
            return
        }
        val sampleRateConfigured = configureSampleRate(connection, alternative, sampleRate)
        if (!sampleRateConfigured) {
            Log.w(
                TAG,
                "UAC sample-rate control rejected ${sampleRate}Hz " +
                    "control=${controlInterface?.id ?: "none"}",
            )
            releaseClaims(connection, claims)
            connection.close()
            replyError(message, "UAC sample-rate control rejected ${sampleRate}Hz")
            return
        }

        val parcelFd = runCatching {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor)
        }.getOrElse { error ->
            releaseClaims(connection, claims)
            connection.close()
            replyError(message, "USB FD duplication failed: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        session = ClaimedSession(connection, claims, device)

        val data = Bundle().apply {
            putInt(UsbDirectIpc.KEY_RESULT, UsbDirectIpc.RESULT_OK)
            putParcelable(UsbDirectIpc.KEY_FD, parcelFd)
            putInt(UsbDirectIpc.KEY_SAMPLE_RATE, sampleRate)
            putInt(UsbDirectIpc.KEY_ENCODING, encoding)
            putInt(UsbDirectIpc.KEY_CHANNELS, channels)
            putInt(UsbDirectIpc.KEY_INTERFACE_NUMBER, alternative.interfaceNumber)
            putInt(UsbDirectIpc.KEY_ALTERNATE_SETTING, alternative.alternateSetting)
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
        runCatching { parcelFd.close() }
    }

    private fun configureSampleRate(
        connection: UsbDeviceConnection,
        alternative: UsbAudioDescriptorParser.StreamingAlt,
        sampleRate: Int,
    ): Boolean {
        return if (alternative.isUac2) {
            UsbUacSampleRateControl.configureUac2(
                sampleRate = sampleRate,
                clockSourceId = alternative.clockSourceId,
                audioControlInterface = alternative.audioControlInterface,
                controlTransfer = UsbControlTransfer(connection::controlTransfer),
            )
        } else {
            val payload = byteArrayOf(
                (sampleRate and 0xff).toByte(),
                ((sampleRate ushr 8) and 0xff).toByte(),
                ((sampleRate ushr 16) and 0xff).toByte(),
            )
            val transferred = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or USB_RECIPIENT_ENDPOINT,
                UAC_CUR,
                SAMPLING_FREQ_CONTROL shl 8,
                alternative.endpointAddress,
                payload,
                payload.size,
                CONTROL_TIMEOUT_MS,
            )
            transferred == payload.size ||
                (alternative.sampleRates.size == 1 && sampleRate in alternative.sampleRates)
        }
    }

    private fun findInterface(
        device: UsbDevice,
        alternative: UsbAudioDescriptorParser.StreamingAlt,
    ): UsbInterface? = (0 until device.interfaceCount)
        .map(device::getInterface)
        .firstOrNull {
            it.id == alternative.interfaceNumber &&
                it.alternateSetting == alternative.alternateSetting
        }

    private fun findAudioControlInterface(
        device: UsbDevice,
        interfaceNumber: Int,
    ): UsbInterface? = (0 until device.interfaceCount)
        .map(device::getInterface)
        .firstOrNull {
            it.id == interfaceNumber &&
                it.alternateSetting == 0 &&
                it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                it.interfaceSubclass == AUDIO_CONTROL_SUBCLASS
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

    private fun releaseSession() {
        val active = session ?: return
        session = null
        releaseClaims(active.connection, active.claims)
        runCatching { active.connection.close() }
    }

    private fun releaseClaims(
        connection: UsbDeviceConnection,
        claims: UsbClaimedInterfaces<UsbInterface>,
    ) {
        claims.releaseWith { usbInterface ->
            runCatching { connection.releaseInterface(usbInterface) }
        }
    }

    private data class ClaimedSession(
        val connection: UsbDeviceConnection,
        val claims: UsbClaimedInterfaces<UsbInterface>,
        val device: UsbDevice,
    )

    companion object {
        private const val TAG = "UsbDirectBroker"
        private const val AUDIO_CONTROL_SUBCLASS = 0x01
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val UAC_CUR = 0x01
        private const val SAMPLING_FREQ_CONTROL = 0x01
        private const val USB_RECIPIENT_ENDPOINT = 0x02
    }
}
