package dev.amenhancer.module.hook

import android.content.Context
import android.os.PowerManager

/** USB bypasses AudioFlinger's wake lock; hold CPU only while this session plays. */
internal class UsbDirectPlaybackPower(context: Context) {
    private val wakeLock = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AM++:UsbDirectPlayback")
            ?.apply { setReferenceCounted(false) }
    }.onFailure { ModernXposedRuntime.log("usb_direct: cannot create playback wake lock", it) }.getOrNull()
    private var refreshAtNanos = 0L

    fun keepAwake() {
        val now = System.nanoTime()
        if (now < refreshAtNanos) return
        runCatching { wakeLock?.acquire(30_000L) }
            .onFailure { ModernXposedRuntime.log("usb_direct: playback wake lock unavailable", it) }
        // Refresh on PCM writes; a stalled producer cannot keep the CPU awake indefinitely.
        refreshAtNanos = now + 10_000_000_000L
    }

    fun release() {
        refreshAtNanos = 0L
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            .onFailure { ModernXposedRuntime.log("usb_direct: playback wake lock release failed", it) }
    }
}
