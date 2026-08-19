package dev.amenhancer.module.hook

import android.media.AudioFormat

/** Thin JNI wrapper around Android's native AAudio API. */
internal object UsbExclusiveAaudioBridge {
    private const val FORMAT_I16 = 1
    private const val FORMAT_FLOAT = 2
    private const val FORMAT_I24 = 3
    private const val FORMAT_I32 = 4

    @Volatile
    private var loadFailure: String? = null

    private val loaded: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary("ampp_audio")
            true
        }.onFailure { error ->
            loadFailure = "native AAudio bridge load failed: ${error.message ?: error.javaClass.simpleName}"
            ModernXposedRuntime.log("usb_exclusive: native bridge load failed", error)
        }.getOrDefault(false)
    }

    sealed interface OpenResult {
        data class Opened(val handle: Long) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    fun open(deviceId: Int, format: AudioFormat): OpenResult {
        val formatCode = formatCode(format.encoding)
            ?: return OpenResult.Failed("当前 PCM encoding 不受实验性 AAudio 独占支持")
        if (!loaded) {
            return OpenResult.Failed(loadFailure ?: "native AAudio bridge unavailable")
        }
        val handle = runCatching {
            nativeOpen(
                deviceId,
                format.sampleRate,
                formatCode,
                format.channelCount,
            )
        }.getOrElse { error ->
            return OpenResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        return if (handle != 0L) {
            OpenResult.Opened(handle)
        } else {
            OpenResult.Failed(lastNativeError("AAudio 独占流打开失败"))
        }
    }

    fun writeBytes(
        handle: Long,
        data: ByteArray,
        offsetBytes: Int,
        sizeBytes: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteBytes(handle, data, offsetBytes, sizeBytes, blocking)
    }.getOrElse { error ->
        ModernXposedRuntime.log("usb_exclusive: byte write bridge failed", error)
        -1
    }

    fun writeFloats(
        handle: Long,
        data: FloatArray,
        offsetFloats: Int,
        sizeFloats: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteFloats(handle, data, offsetFloats, sizeFloats, blocking)
    }.getOrElse { error ->
        ModernXposedRuntime.log("usb_exclusive: float write bridge failed", error)
        -1
    }

    fun writeShorts(
        handle: Long,
        data: ShortArray,
        offsetShorts: Int,
        sizeShorts: Int,
        blocking: Boolean,
    ): Int = runCatching {
        nativeWriteShorts(handle, data, offsetShorts, sizeShorts, blocking)
    }.getOrElse { error ->
        ModernXposedRuntime.log("usb_exclusive: short write bridge failed", error)
        -1
    }

    fun close(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching { nativeClose(handle) }
            .onFailure { error -> ModernXposedRuntime.log("usb_exclusive: native close failed", error) }
    }

    fun lastError(fallback: String = "AAudio operation failed"): String =
        if (!loaded) {
            loadFailure ?: fallback
        } else {
            lastNativeError(fallback)
        }

    fun supportsEncoding(encoding: Int): Boolean = formatCode(encoding) != null

    fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> null
    }

    private fun formatCode(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> FORMAT_I16
        AudioFormat.ENCODING_PCM_FLOAT -> FORMAT_FLOAT
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> FORMAT_I24
        AudioFormat.ENCODING_PCM_32BIT -> FORMAT_I32
        else -> null
    }

    private fun lastNativeError(fallback: String): String = runCatching { nativeLastError() }
        .getOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: fallback

    @JvmStatic
    private external fun nativeOpen(
        deviceId: Int,
        sampleRate: Int,
        formatCode: Int,
        channels: Int,
    ): Long

    @JvmStatic
    private external fun nativeWriteBytes(
        handle: Long,
        data: ByteArray,
        offsetBytes: Int,
        sizeBytes: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeWriteFloats(
        handle: Long,
        data: FloatArray,
        offsetFloats: Int,
        sizeFloats: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeWriteShorts(
        handle: Long,
        data: ShortArray,
        offsetShorts: Int,
        sizeShorts: Int,
        blocking: Boolean,
    ): Int

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeLastError(): String
}
