package dev.amenhancer.module.hook

/** Keeps transport cancellation distinct from a genuine active-session failure. */
internal object UsbDirectWriteFailurePolicy {
    fun shouldResumeOriginalTrack(closedOwnedSession: Boolean): Boolean = closedOwnedSession
}
