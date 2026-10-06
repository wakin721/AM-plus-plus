package dev.amenhancer.module.hook

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import android.os.Process
import dev.amenhancer.module.BuildConfig

/** JNI wrapper for the usbfs isochronous UAC output engine. */
internal object UsbDirectUacBridge {
    private const val FORMAT_I16 = 1
    private const val FORMAT_FLOAT = 2
    private const val FORMAT_I24 = 3
    private const val FORMAT_I32 = 4

    sealed interface OpenResult {
        data class Opened(val handle: Long) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    @Volatile
    private var loadFailure: String? = null

    @Volatile private var loaded = false
    private val loadLock = Any()

    private fun ensureLoaded(context: Context): Boolean = synchronized(loadLock) {
        if (loaded) return true
        val moduleInfo = runCatching {
            ModernXposedRuntime.activeModule()?.getModuleApplicationInfo()
        }.getOrNull() ?: runCatching {
            context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        }.getOrNull()
        val result = UsbDirectNativeLibraryLoader.load(
            nativeLibraryDir = moduleInfo?.nativeLibraryDir.orEmpty(),
            moduleApkPaths = listOfNotNull(moduleInfo?.sourceDir, moduleInfo?.publicSourceDir) +
                moduleInfo?.splitSourceDirs.orEmpty(),
            processAbis = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).toList(),
            cacheDir = context.codeCacheDir,
        )
        loaded = result.loaded
        loadFailure = if (loaded) null else "native USB Direct bridge load failed: ${result.message}"
        if (!loaded) ModernXposedRuntime.log("usb_direct: ${result.message}", result.cause)
        loaded
    }

    fun open(
        context: Context,
        lease: UsbDirectDeviceClient.Lease,
        pcmBufferMs: Int,
        transferBufferMs: Int,
    ): OpenResult {
        val inputFormatCode = formatCode(lease.encoding)
            ?: return OpenResult.Failed("当前 AudioTrack PCM encoding 不受 USB Direct 原型支持")
        if (!ensureLoaded(context)) return OpenResult.Failed(loadFailure ?: "native USB Direct bridge unavailable")
        val handle = runCatching {
            nativeOpen(
                lease.fd.fd,
                lease.sampleRate,
                inputFormatCode,
                lease.channels,
                lease.interfaceNumber,
                lease.alternateSetting,
                lease.audioControlInterface,
                lease.clockSourceId,
                lease.fixedSampleRateMatch,
                lease.protocol,
                lease.endpointAddress,
                lease.maxPacketSize,
                lease.interval,
                lease.feedbackEndpointAddress,
                lease.feedbackMaxPacketSize,
                lease.feedbackInterval,
                lease.subslotBytes,
                lease.bitResolution,
                pcmBufferMs,
                transferBufferMs,
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
        gainLeft: Float,
        gainRight: Float,
    ): Int = runCatching {
        nativeWriteFloats(handle, data, offset, size, blocking, gainLeft, gainRight)
    }.getOrElse { -1 }

    fun writeShorts(
        handle: Long,
        data: ShortArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
        gainLeft: Float,
        gainRight: Float,
    ): Int = runCatching {
        nativeWriteShorts(handle, data, offset, size, blocking, gainLeft, gainRight)
    }.getOrElse { -1 }

    fun writeBytes(
        handle: Long,
        data: ByteArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
        gainLeft: Float,
        gainRight: Float,
    ): Int = runCatching {
        nativeWriteBytes(handle, data, offset, size, blocking, gainLeft, gainRight)
    }.getOrElse { -1 }

    fun suspend(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching { nativeSuspend(handle) }
            .onFailure { error -> ModernXposedRuntime.log("usb_direct: native suspend failed", error) }
    }

    fun resume(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching { nativeResume(handle) }
            .onFailure { error -> ModernXposedRuntime.log("usb_direct: native resume failed", error) }
    }

    fun flush(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching { nativeFlush(handle) }
            .onFailure { error -> ModernXposedRuntime.log("usb_direct: native flush failed", error) }
    }

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

    fun supportsEncoding(encoding: Int): Boolean = formatCode(encoding) != null

    fun playbackPosition(handle: Long): LongArray? =
        runCatching { nativePlaybackPosition(handle) }.getOrNull()

    private fun formatCode(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> FORMAT_I16
        AudioFormat.ENCODING_PCM_FLOAT -> FORMAT_FLOAT
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> FORMAT_I24
        AudioFormat.ENCODING_PCM_32BIT -> FORMAT_I32
        else -> null
    }

    @JvmStatic
    private external fun nativeOpen(
        fd: Int,
        sampleRate: Int,
        inputFormatCode: Int,
        channels: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        audioControlInterface: Int,
        clockSourceId: Int,
        fixedSampleRateMatch: Boolean,
        protocol: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        interval: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        feedbackInterval: Int,
        targetSubslotBytes: Int,
        targetBitResolution: Int,
        pcmBufferMs: Int,
        transferBufferMs: Int,
    ): Long

    @JvmStatic
    private external fun nativeWriteFloats(
        handle: Long,
        data: FloatArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
        gainLeft: Float,
        gainRight: Float,
    ): Int

    @JvmStatic
    private external fun nativeWriteShorts(
        handle: Long,
        data: ShortArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
        gainLeft: Float,
        gainRight: Float,
    ): Int

    @JvmStatic
    private external fun nativeWriteBytes(
        handle: Long,
        data: ByteArray,
        offset: Int,
        size: Int,
        blocking: Boolean,
        gainLeft: Float,
        gainRight: Float,
    ): Int

    @JvmStatic
    private external fun nativeSuspend(handle: Long)

    @JvmStatic
    private external fun nativeResume(handle: Long)

    @JvmStatic
    private external fun nativeFlush(handle: Long)

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeLastError(): String

    @JvmStatic
    private external fun nativePlaybackPosition(handle: Long): LongArray?
}
