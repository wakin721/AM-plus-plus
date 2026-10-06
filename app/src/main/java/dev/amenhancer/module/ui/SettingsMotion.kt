package dev.amenhancer.module.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/** Animate content, leaving the window/inset-owning container stationary. */
internal class SettingsPageMotion(private val view: View) : View.OnAttachStateChangeListener {
    private var pending: Runnable? = null

    init { view.addOnAttachStateChangeListener(this) }

    fun enter(direction: Int = 0) {
        cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val rtl = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        view.alpha = 0f
        view.translationX = direction * rtl * 28f * view.resources.displayMetrics.density
        view.translationY = if (direction == 0) 12f * view.resources.displayMetrics.density else 0f
        val start = Runnable {
            pending = null
            if (!view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
                cancel()
            } else {
                view.animate().alpha(1f).translationX(0f).translationY(0f)
                    .setDuration(220L).setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
        }
        pending = start
        view.post(start)
    }

    fun cancel() {
        pending?.let(view::removeCallbacks)
        pending = null
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) { cancel() }
}

/** Preserve an in-flight toggle when a settings write rebuilds the rows. */
internal fun captureSettingsSwitchMotion(root: View): Map<String, Float> = buildMap {
    visitSettingsSwitches(root) { toggle ->
        toggle.motionKey?.let { put(it, toggle.motionProgress) }
    }
}

internal fun restoreSettingsSwitchMotion(root: View, progress: Map<String, Float>) {
    visitSettingsSwitches(root) { toggle ->
        progress[toggle.motionKey]?.let(toggle::resumeMotionFrom)
    }
}

private fun visitSettingsSwitches(root: View, visit: (EmbeddedSettingsSwitch) -> Unit) {
    if (root is EmbeddedSettingsSwitch) visit(root)
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) visitSettingsSwitches(root.getChildAt(index), visit)
    }
}
