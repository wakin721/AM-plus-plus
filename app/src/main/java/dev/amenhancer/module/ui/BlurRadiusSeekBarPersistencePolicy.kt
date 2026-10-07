package dev.amenhancer.module.ui

/** Touch drags save on release; keyboard/accessibility changes save immediately. */
internal object BlurRadiusSeekBarPersistencePolicy {
    fun shouldPersistProgressChange(fromUser: Boolean, trackingTouch: Boolean): Boolean =
        fromUser && !trackingTouch
}
