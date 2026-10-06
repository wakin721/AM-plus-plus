package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleTtmlTranslationEditorTest {
    @Test
    fun extractsVisibleTextAndKeepsStableAppleLineKeys() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <head><metadata/></head><body><div>
                <p itunes:key="L1"><span begin="0.0" end="1.0">Hello &amp; </span><span begin="1.0" end="2.0">you</span></p>
                <p itunes:key="L2"><span begin="2.0" end="3.0">World</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val lines = AppleTtmlTranslationEditor.extractLines(ttml)

        assertEquals(listOf("L1", "L2"), lines.map { it.id })
        assertEquals("Hello & you", lines[0].text)
        assertEquals("World", lines[1].text)
    }

    @Test
    fun writesCompleteNativeTranslationTrackWithPlaceholderForMissingLine() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <head><metadata><x/></metadata></head><body><div>
                <p itunes:key="L1"><span>one</span></p>
                <p itunes:key="L2"><span>two</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val result = AppleTtmlTranslationEditor.withTranslations(
            ttml,
            mapOf("L1" to "一"),
            "zh-Hans",
        )

        assertNotNull(result)
        result!!
        assertTrue(result.contains("<translation type=\"subtitle\" xml:lang=\"zh-Hans\">"))
        assertTrue(result.contains("<text for=\"L1\">一</text>"))
        assertTrue(result.contains("<text for=\"L2\"> </text>"))
        assertTrue(result.contains("<x/>"))
    }

    @Test
    fun replacesExistingTranslationsWithoutTouchingBodyTiming() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><translations><translation type="subtitle" xml:lang="en"><text for="L1">old</text></translation></translations><songwriters/></iTunesMetadata></metadata></head>
              <body><div><p begin="1.000" end="2.000" itunes:key="L1"><span begin="1.000" end="2.000">one</span></p></div></body>
            </tt>
        """.trimIndent()

        val result = AppleTtmlTranslationEditor.withTranslations(ttml, mapOf("L1" to "新"))

        assertNotNull(result)
        result!!
        assertFalse(result.contains(">old<"))
        assertTrue(result.contains("<text for=\"L1\">新</text>"))
        assertTrue(result.contains("<songwriters/>"))
        assertTrue(result.contains("begin=\"1.000\" end=\"2.000\""))
    }
}
