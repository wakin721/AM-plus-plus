package dev.amenhancer.module.hook

import kotlin.math.pow

/** Converts Android's media-volume state into a safe linear PCM gain. */
internal object UsbDirectVolumePolicy {
    fun streamGain(
        volumeIndex: Int,
        maxVolumeIndex: Int,
        muted: Boolean,
        volumeDb: Float?,
    ): Float {
        if (muted || volumeIndex <= 0 || maxVolumeIndex <= 0) return 0f
        if (volumeIndex >= maxVolumeIndex) return 1f

        val db = volumeDb?.takeIf(Float::isFinite)
        if (db != null && db < 0f) {
            return 10.0.pow(db.toDouble() / 20.0).toFloat().coerceIn(0f, 1f)
        }

        // Some USB HALs expose every non-zero step as 0 dB. Keep the volume
        // keys useful on those devices instead of treating each step as full scale.
        return (volumeIndex.toFloat() / maxVolumeIndex.toFloat()).coerceIn(0f, 1f)
    }

    fun effectiveGain(streamGain: Float, trackGain: Float): Float =
        (streamGain.coerceIn(0f, 1f) * trackGain.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/**
 * Thread-safe gain cache shared by the control path and the PCM write path.
 * Android volume queries happen only when [refresh] is called; [effectiveGain]
 * is deliberately a local, allocation-free hot-path read.
 */
internal class UsbDirectVolumeCache(initialStreamGain: Float) {
    @Volatile
    private var streamGain = sanitize(initialStreamGain)

    fun refresh(query: () -> Float) {
        streamGain = sanitize(query())
    }

    fun effectiveGain(trackGain: Float): Float =
        UsbDirectVolumePolicy.effectiveGain(streamGain, trackGain)

    private fun sanitize(gain: Float): Float =
        gain.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
}
