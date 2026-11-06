package com.coke.nestspace.container

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Hosts a headless target at the device's real resolution and scales the
 * rendered child down to the actual host window.
 *
 * The target's view tree therefore keeps 1080x2400 coordinates and layout.
 * This is a live view scaled by the compositor, not a screenshot or screen
 * recording.
 */
class HeadlessScaleFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var scaleToRealResolution: Boolean = false
    var realWidth: Int = 0
    var realHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (!scaleToRealResolution || realWidth <= 0 || realHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val child = getChildAt(0)
        child?.measure(
            MeasureSpec.makeMeasureSpec(realWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(realHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        if (!scaleToRealResolution || realWidth <= 0 || realHeight <= 0) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val width = right - left
        val height = bottom - top
        child.layout(0, 0, realWidth, realHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = width.toFloat() / realWidth
        child.scaleY = height.toFloat() / realHeight
    }

    fun realMetrics(): Pair<Int, Int>? = if (realWidth > 0 && realHeight > 0) {
        realWidth to realHeight
    } else {
        null
    }
}
