package dev.amenhancer.module.ui
internal enum class EmbeddedOnlineSource {
    AMLL,
    AM_LYRICS,
    LUNABEAT,
}

internal sealed interface EmbeddedActionResult {
    data class Done(val message: String) : EmbeddedActionResult
    data class Failed(val message: String) : EmbeddedActionResult
}

