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

    fun shouldHandoff(
        sameTrack: Boolean,
        suspended: Boolean,
        existingTrackAlive: Boolean,
    ): Boolean = !sameTrack && (suspended || !existingTrackAlive)
}
