package dev.amenhancer.module.hook

internal enum class UsbDirectTrackHandoffAction {
    SUSPEND,
    CLOSE,
}

internal object UsbDirectTrackHandoffPolicy {
    fun actionFor(operation: String): UsbDirectTrackHandoffAction = when (operation) {
        "pause", "flush" -> UsbDirectTrackHandoffAction.SUSPEND
        else -> UsbDirectTrackHandoffAction.CLOSE
    }

    fun isIdleOwner(
        lastWriteRealtimeNanos: Long,
        nowRealtimeNanos: Long,
        idleThresholdNanos: Long,
    ): Boolean =
        lastWriteRealtimeNanos > 0L &&
            nowRealtimeNanos >= lastWriteRealtimeNanos &&
            nowRealtimeNanos - lastWriteRealtimeNanos >= idleThresholdNanos

    fun shouldHandoff(
        sameTrack: Boolean,
        suspended: Boolean,
        existingTrackAlive: Boolean,
        existingTrackIdle: Boolean,
    ): Boolean = !sameTrack && (suspended || !existingTrackAlive || existingTrackIdle)
}
