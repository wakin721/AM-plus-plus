package dev.amenhancer.module.ui
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
enum class EmbeddedHostActivityRole {
    Player,
    MainContent,
    Settings,
}

object EmbeddedSettingsTextPolicy {
    private val classNameMarkers = listOf(
        "settings",
        "setting",
        "preferences",
        "preference",
        "accountsettings",
    )
    private val titleMarkers = listOf(
        "settings",
        "preference",
        "设置",
        "通用",
    )

    fun isSettingsClassName(className: String): Boolean {
        val normalized = className.lowercase(Locale.ROOT)
        return classNameMarkers.any(normalized::contains)
    }

    fun isSettingsTitle(text: CharSequence?): Boolean {
        val normalized = text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isBlank()) return false
        return titleMarkers.any(normalized::contains)
    }

    fun containsSettingsTitle(root: View, ignoredTag: Any? = null): Boolean {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_VIEW_SCAN_NODES) {
            val view = pending.removeFirst()
            if (ignoredTag != null && view.tag == ignoredTag) continue
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue
            if (view is TextView && isSettingsTitle(view.text)) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    pending.addLast(view.getChildAt(index))
                }
            }
        }
        return false
    }

    private const val MAX_VIEW_SCAN_NODES = 1024
}

