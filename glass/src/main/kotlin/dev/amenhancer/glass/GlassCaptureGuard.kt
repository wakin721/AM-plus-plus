package dev.amenhancer.glass

/** Exclude consumers while recording the underlying Android scene, avoiding feedback. */
object GlassCaptureGuard {
    private val depth = ThreadLocal.withInitial { 0 }
    val active: Boolean get() = depth.get() > 0
    fun <T> capture(block: () -> T): T {
        val previous = depth.get()
        depth.set(previous + 1)
        return try { block() } finally { depth.set(previous) }
    }
}
