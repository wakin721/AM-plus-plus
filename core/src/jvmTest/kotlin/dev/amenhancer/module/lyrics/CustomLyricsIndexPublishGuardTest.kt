package dev.amenhancer.module.lyrics

import dev.amenhancer.module.config.CustomLyricsIndexPointer
import dev.amenhancer.module.config.CustomLyricsIndexState
import dev.amenhancer.module.model.CustomLyricsManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLyricsIndexPublishGuardTest {

    @Test
    fun `concurrent edit after preliminary check prevents publication`() {
        val baseline = state("index_old", 1L)
        var current = baseline
        var published = false

        // Models the transaction's fast pre-publication check succeeding.
        assertTrue(current == baseline)
        // A manual edit lands before the updater enters its publication lock.
        current = state("index_newer", 2L)

        val committed = publishCustomLyricsManifestIfUnchanged(
            lock = Any(),
            expected = baseline,
            readCurrent = { current },
            publish = {
                published = true
                true
            },
        )

        assertFalse(committed)
        assertFalse(published)
    }

    private fun state(fileId: String, generation: Long) = CustomLyricsIndexState(
        pointer = CustomLyricsIndexPointer(
            fileId = fileId,
            generation = generation,
            sha256 = "a".repeat(64),
            sizeBytes = 1L,
        ),
        manifest = CustomLyricsManifest.empty(),
    )
}
