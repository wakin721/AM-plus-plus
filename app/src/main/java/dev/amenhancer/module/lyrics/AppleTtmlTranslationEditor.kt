package dev.amenhancer.module.lyrics

import dev.amenhancer.module.translation.TranslationLine

/**
 * Reads line text from Apple-compatible TTML and replaces/creates its native
 * translation track without touching lyric timing or word spans.
 */
object AppleTtmlTranslationEditor {
    private const val ITUNES_NAMESPACE = "http://music.apple.com/lyric-ttml-internal"
    private const val ABSENT_TEXT = " "

    private val LINE = Regex("""<p\b([^>]*)>(.*?)</p\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val KEY = Regex("""\bitunes:key\s*=\s*(?:"([^"]+)"|'([^']+)')""", RegexOption.IGNORE_CASE)
    private val AUXILIARY_SPAN = Regex(
        """<span\b(?=[^>]*\bttm:role\s*=\s*(?:"(?:x-translation|x-roman)"|'(?:x-translation|x-roman)'))[^>]*>.*?</span\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG = Regex("""<[^>]+>""")
    private val TRANSLATIONS = Regex(
        """<translations\b[^>]*>.*?</translations\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ITUNES_METADATA = Regex(
        """(<iTunesMetadata\b[^>]*>)(.*?)(</iTunesMetadata\s*>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val METADATA_CLOSE = Regex("""</metadata\s*>""", RegexOption.IGNORE_CASE)

    fun extractLines(ttml: String): List<TranslationLine> {
        if (!TtmlInputPolicy.isAcceptable(ttml)) return emptyList()
        val seen = hashSetOf<String>()
        return LINE.findAll(ttml).mapNotNull { match ->
            val attributes = match.groupValues[1]
            val keyMatch = KEY.find(attributes) ?: return@mapNotNull null
            val key = keyMatch.groupValues[1].ifEmpty { keyMatch.groupValues[2] }.trim()
            if (key.isEmpty() || !seen.add(key)) return@mapNotNull null
            val body = AUXILIARY_SPAN.replace(match.groupValues[2], "")
            val text = decodeXml(TAG.replace(body, "")).replace(WHITESPACE, " ").trim()
            text.takeIf { it.isNotEmpty() }?.let { TranslationLine(key, it) }
        }.take(DeepSeekLineLimit.MAX_LINES).toList()
    }

    fun withTranslations(
        ttml: String,
        translations: Map<String, String>,
        language: String = "zh-Hans",
    ): String? {
        if (!TtmlInputPolicy.isAcceptable(ttml) || translations.isEmpty()) return null
        val allKeys = LINE.findAll(ttml).mapNotNull { match ->
            val keyMatch = KEY.find(match.groupValues[1]) ?: return@mapNotNull null
            keyMatch.groupValues[1].ifEmpty { keyMatch.groupValues[2] }.trim().takeIf(String::isNotEmpty)
        }.toList()
        if (allKeys.isEmpty() || allKeys.distinct().size != allKeys.size) return null
        if (translations.keys.any { it !in allKeys }) return null

        val track = buildString {
            append("<translations><translation type=\"subtitle\" xml:lang=\"")
            append(escapeXml(language.trim()))
            append("\">")
            allKeys.forEach { key ->
                append("<text for=\"").append(escapeXml(key)).append("\">")
                append(escapeXml(translations[key]?.takeIf { it.isNotBlank() } ?: ABSENT_TEXT))
                append("</text>")
            }
            append("</translation></translations>")
        }

        val metadata = ITUNES_METADATA.find(ttml)
        val result = if (metadata != null) {
            val inner = TRANSLATIONS.replace(metadata.groupValues[2], "")
            ttml.replaceRange(
                metadata.range,
                metadata.groupValues[1] + track + inner + metadata.groupValues[3],
            )
        } else {
            val closing = METADATA_CLOSE.find(ttml) ?: return null
            val container = "<iTunesMetadata xmlns=\"$ITUNES_NAMESPACE\">$track</iTunesMetadata>"
            ttml.replaceRange(closing.range.first, closing.range.first, container)
        }
        return result.takeIf(TtmlInputPolicy::isAcceptable)
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun decodeXml(value: String): String = ENTITY.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> decodeNumericEntity(entity) ?: match.value
        }
    }

    private fun decodeNumericEntity(value: String): String? {
        if (!value.startsWith('#')) return null
        val codePoint = if (value.startsWith("#x", ignoreCase = true)) {
            value.substring(2).toIntOrNull(16)
        } else {
            value.substring(1).toIntOrNull()
        } ?: return null
        if (!Character.isValidCodePoint(codePoint)) return null
        return String(Character.toChars(codePoint))
    }

    private object DeepSeekLineLimit {
        const val MAX_LINES = 4096
    }

    private val WHITESPACE = Regex("""\s+""")
    private val ENTITY = Regex("""&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);""")
}
