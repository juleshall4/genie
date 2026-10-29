package dev.genie.android

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView

class PopupScrollView(context: Context, attributes: AttributeSet) : ScrollView(context, attributes) {
    /** Lets the box fill its parent instead of capping it at half the screen. */
    var expanded = false
        set(value) {
            field = value
            requestLayout()
        }

    /** Called when scrolling up past the top (to expand) or down past the bottom (to collapse). */
    var onPullPastTop: () -> Unit = {}
    var onPullPastBottom: () -> Unit = {}

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var pulled = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (expanded) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val screenLimit = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) screenLimit
            else minOf(screenLimit, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                pulled = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulled) {
                val dy = event.y - downY
                if (dy > touchSlop && !canScrollVertically(-1)) pull(onPullPastTop)
                else if (dy < -touchSlop && !canScrollVertically(1)) pull(onPullPastBottom)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheel > 0 && !canScrollVertically(-1)) return true.also { onPullPastTop() }
            if (wheel < 0 && !canScrollVertically(1)) return true.also { onPullPastBottom() }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun pull(action: () -> Unit) {
        pulled = true
        action()
    }
}
