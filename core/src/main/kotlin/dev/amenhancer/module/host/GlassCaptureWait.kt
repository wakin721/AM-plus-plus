package dev.amenhancer.module.host

/** A missing resumed backdrop must never keep canceling the whole window's draws. */
class GlassCaptureWait(private val timeoutMs: Long = 750L) {
    private var started: Long? = null

    fun timedOut(now: Long, waiting: Boolean): Boolean {
        if (!waiting) {
            started = null
            return false
        }
        val start = started ?: now.also { started = it }
        return now - start >= timeoutMs
    }
}
