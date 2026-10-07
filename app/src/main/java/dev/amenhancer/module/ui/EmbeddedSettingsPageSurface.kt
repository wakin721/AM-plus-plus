package dev.amenhancer.module.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout

/** A secondary page in the host content window, with reversible ownership of its views. */
internal class EmbeddedSettingsPageSurface(
    val activity: Activity,
    val root: ViewGroup,
    private val onBack: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val container = activity.findViewById<ViewGroup>(android.R.id.content)
    private val hiddenViews = mutableListOf<Pair<View, Int>>()
    private var previousFocus: View? = null
    private var previousSoftInputMode = 0
    private var platformBack: OnBackInvokedCallback? = null
    private var closed = false
    private var attached = false

    fun attach(): Boolean {
        val parent = container ?: return false
        previousFocus = activity.currentFocus
        previousSoftInputMode = activity.window.attributes.softInputMode
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            hiddenViews += child to child.visibility
            child.visibility = if (parent is FrameLayout) View.INVISIBLE else View.GONE
        }
        root.isClickable = true
        root.isFocusableInTouchMode = true
        parent.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        attached = true
        root.requestFocus()
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            root.rootWindowInsets?.let(::applyInsets)
        }
        root.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            platformBack = OnBackInvokedCallback { handleBack() }.also {
                activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, it,
                )
            }
        }
        return true
    }

    private fun applyInsets(insets: WindowInsets) {
        val location = IntArray(2)
        root.getLocationInWindow(location)
        // Non-edge-to-edge Activities already inset android.R.id.content.
        // Add only the part of a system bar/IME that actually overlaps this page.
        val top: Int
        val bottom: Int
        val left: Int
        val right: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            top = safe.top
            bottom = safe.bottom
            left = safe.left
            right = safe.right
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
        }
        val decor = activity.window.decorView
        val windowBounds = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds else null
        val windowWidth = windowBounds?.width() ?: decor.width
        val windowHeight = windowBounds?.height() ?: decor.height
        root.setPadding(
            (left - location[0]).coerceAtLeast(0),
            (top - location[1]).coerceAtLeast(0),
            (right - (windowWidth - location[0] - root.width)).coerceAtLeast(0),
            (bottom - (windowHeight - location[1] - root.height)).coerceAtLeast(0),
        )
    }

    fun handleBack(): Boolean {
        if (closed || root.parent == null) return false
        val visibleFrame = Rect()
        root.getWindowVisibleDisplayFrame(visibleFrame)
        val keyboardVisible = if (Build.VERSION.SDK_INT >= 30) {
            root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            activity.window.decorView.height - visibleFrame.bottom > root.resources.displayMetrics.density * 100
        }
        if (keyboardVisible && activity.currentFocus is EditText) {
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
            root.requestFocus()
        } else {
            onBack()
        }
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        if (!attached) {
            onClosed()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            platformBack?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        platformBack = null
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
        (root.parent as? ViewGroup)?.removeView(root)
        hiddenViews.forEach { (view, visibility) ->
            if (view.parent === container) view.visibility = visibility
        }
        hiddenViews.clear()
        activity.window.setSoftInputMode(previousSoftInputMode)
        previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        previousFocus = null
        onClosed()
    }
}

internal class EmbeddedSettingsNavigation {
    var page = EmbeddedSettingsPage.MAIN
        private set

    fun openUsb() { page = EmbeddedSettingsPage.USB_AUDIO }

    fun openLyrics() { page = EmbeddedSettingsPage.CUSTOM_LYRICS }

    /** False lets the page owner return to Apple's settings list. */
    fun back(): Boolean {
        if (page == EmbeddedSettingsPage.MAIN) return false
        page = EmbeddedSettingsPage.MAIN
        return true
    }
}
