package dev.amenhancer.module.hook

import android.media.AudioFormat

/** JNI wrapper for the usbfs isochronous UAC output engine. */
internal object UsbDirectUacBridge {
    sealed interface OpenResult {
        data class Opened(val handle: Long) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    @Volatile
    private var loadFailure: String? = null

    private val loaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary("ampp_audio")
            true
        }.onFailure { error ->
            loadFailure = "native USB Direct bridge load failed: ${error.message ?: error.javaClass.simpleName}"
            ModernXposedRuntime.log("usb_direct: native bridge load failed", error)
        }.getOrDefault(false)
    }

    fun open(lease: UsbDirectDeviceClient.Lease): OpenResult {
        if (!supportsEncoding(lease.encoding)) {
            return OpenResult.Failed("当前 AudioTrack PCM encoding 不受 USB Direct 原型支持")
        }
        if (!loaded) return OpenResult.Failed(loadFailure ?: "native USB Direct bridge unavailable")
        val handle = runCatching {
            nativeOpen(
                lease.fd.fd,
                lease.sampleRate,
                lease.encoding,
                lease.channels,
                lease.endpointAddress,
                lease.maxPacketSize,
                lease.interval,
                lease.subslotBytes,
                lease.bitResolution,
            )
        }.getOrElse { error ->
            return OpenResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return if (handle != 0L) {
            OpenResult.Opened(handle)
        } else {
            OpenResult.Failed(lastError("usbfs isochronous engine open failed"))
        }
    }

    fun writeFloats(
        handle: Long,
        data: FloatArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteFloats(handle, data, offset, size, blocking)
    }.getOrElse { -1 }

    fun writeShorts(
        handle: Long,
        data: ShortArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteShorts(handle, data, offset, size, blocking)
    }.getOrElse { -1 }

    fun writeBytes(
        handle: Long,
        data: ByteArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteBytes(handle, data, offset, size, blocking)
    }.getOrElse { -1 }

    fun close(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching { nativeClose(handle) }
            .onFailure { error -> ModernXposedRuntime.log("usb_direct: native close failed", error) }
    }

    fun lastError(fallback: String): String = if (!loaded) {
        loadFailure ?: fallback
    } else {
        runCatching { nativeLastError() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: fallback
    }

    fun supportsEncoding(encoding: Int): Boolean = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        -> true
        else -> false
    }

    @JvmStatic
    private external fun nativeOpen(
        fd: Int,
        sampleRate: Int,
        inputEncoding: Int,
        channels: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        interval: Int,
        targetSubslotBytes: Int,
        targetBitResolution: Int,
    ): Long

    @JvmStatic
    private external fun nativeWriteFloats(
        handle: Long,
        data: FloatArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeWriteShorts(
        handle: Long,
        data: ShortArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeWriteBytes(
        handle: Long,
        data: ByteArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeLastError(): String
}
