package dev.amenhancer.module.hook

internal enum class UsbDirectTrackHandoffAction {
    SUSPEND,
    FLUSH,
    CLOSE,
}

internal object UsbDirectTrackHandoffPolicy {
    fun actionFor(operation: String): UsbDirectTrackHandoffAction = when (operation) {
        "pause" -> UsbDirectTrackHandoffAction.SUSPEND
        "flush" -> UsbDirectTrackHandoffAction.FLUSH
        else -> UsbDirectTrackHandoffAction.CLOSE
    }

    fun isIdleOwner(
        ownerStartedRealtimeNanos: Long,
        lastWriteRealtimeNanos: Long,
        nowRealtimeNanos: Long,
        idleThresholdNanos: Long,
        startupGraceNanos: Long,
    ): Boolean {
        val referenceNanos: Long
        val thresholdNanos: Long
        if (lastWriteRealtimeNanos > 0L) {
            referenceNanos = lastWriteRealtimeNanos
            thresholdNanos = idleThresholdNanos
        } else {
            if (ownerStartedRealtimeNanos <= 0L) return false
            referenceNanos = ownerStartedRealtimeNanos
            thresholdNanos = startupGraceNanos
        }
        return nowRealtimeNanos >= referenceNanos &&
            nowRealtimeNanos - referenceNanos >= thresholdNanos
    }

    fun shouldHandoff(
        sameTrack: Boolean,
        suspended: Boolean,
        existingTrackAlive: Boolean,
        existingTrackIdle: Boolean,
    ): Boolean = !sameTrack && (suspended || !existingTrackAlive || existingTrackIdle)
}
