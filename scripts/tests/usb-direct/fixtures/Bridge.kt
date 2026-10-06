package dev.amenhancer.module.hook

object ModernXposedRuntime { fun log(message: String, error: Throwable? = null) {} }
object UsbDirectSystemVolumeObserver { fun syncPolling() {} }

/** Native boundary substitute; the production controller and IPC client are compiled unchanged. */
internal object UsbDirectUacBridge {
    sealed interface OpenResult {
        data class Opened(val handle: Long) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }
    class Output {
        var suspended = false
        var capacity = 8
        val queued = mutableListOf<Int>()
        var renderedFrames = 0L
        var renderedAtNanos = 0L
    }
    var duringOpen: (() -> Unit)? = null
    var failOpen = false
    var failWrite = false
    var nextHandle = 0L
    val outputs = mutableMapOf<Long, Output>()
    val closed = mutableListOf<Long>()
    fun reset() {
        duringOpen = null; failOpen = false; failWrite = false
        outputs.clear(); closed.clear()
    }
    fun open(context: android.content.Context, lease: UsbDirectDeviceClient.Lease, pcmBufferMs: Int, transferBufferMs: Int): OpenResult {
        val handle = ++nextHandle
        outputs[handle] = Output()
        duringOpen?.invoke()
        if (failOpen) { outputs.remove(handle); return OpenResult.Failed("test open failure") }
        return OpenResult.Opened(handle)
    }
    fun supportsEncoding(encoding: Int) = true
    fun suspend(handle: Long) { outputs.getValue(handle).suspended = true }
    fun resume(handle: Long) { outputs.getValue(handle).suspended = false }
    fun flush(handle: Long) { outputs.getValue(handle).apply {
        queued.clear(); renderedFrames = 0L; renderedAtNanos = 0L
    } }
    fun playbackPosition(handle: Long): LongArray? = outputs[handle]?.let {
        longArrayOf(it.renderedFrames, it.renderedAtNanos)
    }
    fun close(handle: Long) { outputs.remove(handle); closed.add(handle) }
    fun lastError(reason: String) = reason
    private fun enqueue(handle: Long, data: List<Int>): Int {
        if (failWrite) return -1
        val output = outputs.getValue(handle)
        val accepted = minOf(data.size, output.capacity - output.queued.size)
        output.queued.addAll(data.take(accepted))
        return accepted
    }
    fun writeFloats(handle: Long, data: FloatArray, offset: Int, size: Int, blocking: Boolean,
        gainLeft: Float, gainRight: Float) = enqueue(handle, data.slice(offset until offset + size).map { it.toInt() })
    fun writeShorts(handle: Long, data: ShortArray, offset: Int, size: Int, blocking: Boolean,
        gainLeft: Float, gainRight: Float) = enqueue(handle, data.slice(offset until offset + size).map { it.toInt() })
    fun writeBytes(handle: Long, data: ByteArray, offset: Int, size: Int, blocking: Boolean,
        gainLeft: Float, gainRight: Float) = enqueue(handle, data.slice(offset until offset + size).map { it.toInt() })
}
