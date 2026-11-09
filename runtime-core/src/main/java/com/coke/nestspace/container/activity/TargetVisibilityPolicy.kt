package com.coke.nestspace.container.activity

/**
 * A host window and its synthetic target Activity have different visibility
 * lifecycles. Mirror and headless hosts intentionally move behind the
 * assistant, but the virtual Activity still has to be started/resumed so its
 * Fragments, ViewPager pages and background initialization can run.
 */
internal object TargetVisibilityPolicy {
    fun shouldRunForegroundLifecycle(
        hostResumed: Boolean,
        mirrorMode: Boolean,
        headless: Boolean
    ): Boolean = hostResumed || mirrorMode || headless

    fun shouldKeepTargetActiveWhileHostHidden(
        pictureInPicture: Boolean,
        mirrorMode: Boolean,
        headless: Boolean
    ): Boolean = pictureInPicture || mirrorMode || headless
}
