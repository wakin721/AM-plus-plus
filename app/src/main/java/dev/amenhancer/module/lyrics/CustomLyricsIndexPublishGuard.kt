package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsIndexState

/**
 * Revalidates a long-running update's index baseline and publishes while the
 * caller's mutation lock is held, closing the gap between check and commit.
 */
internal fun publishCustomLyricsManifestIfUnchanged(
    lock: Any,
    expected: CustomLyricsIndexState,
    readCurrent: () -> CustomLyricsIndexState,
    publish: (CustomLyricsIndexState) -> Boolean,
): Boolean = synchronized(lock) {
    val current = readCurrent()
    if (current != expected) return@synchronized false
    publish(current)
}
