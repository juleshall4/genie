package dev.genie.android

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.ScrollView

class PopupScrollView(context: Context, attributes: AttributeSet) : ScrollView(context, attributes) {
    /** Lets the box fill its parent instead of capping it at half the screen. */
    var expanded = false
        set(value) {
            field = value
            requestLayout()
        }

    /**
     * Called when a swipe that starts at the top keeps pulling down (to expand), or one that starts at the
     * bottom keeps pushing up (to collapse). Returns whether it did anything, so the swipe can end there.
     */
    var onPullPastTop: () -> Boolean = { false }
    var onPullPastBottom: () -> Boolean = { false }

    // A pull has to be deliberate: three times as far as a scroll needs to start.
    private val pullDistance = ViewConfiguration.get(context).scaledTouchSlop * 3
    private var downY = 0f
    private var atTop = false
    private var atBottom = false
    private var pulled = false

    // While the box resizes, the held view stays put above the box's bottom edge.
    private var held: View? = null
    private var heldOffset = 0
    private val release = Runnable { held = null }

    init {
        // The box lets Puff spill past its edges, so this view clips its own content. Otherwise text scrolled
        // past the top would draw over the handle, outside the fade.
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
    }

    /** Keeps [anchor] in place on screen for [millis] while this view and its content resize, or until a touch. */
    fun hold(anchor: View, millis: Long) {
        held = anchor
        heldOffset = scrollY + height - top(anchor)
        removeCallbacks(release)
        postDelayed(release, millis)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (expanded) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val screenLimit = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) screenLimit
            else minOf(screenLimit, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        keepHeld()
    }

    // Resize animations change the view's bounds without a new layout.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keepHeld()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                held = null
                downY = event.y
                atTop = !canScrollVertically(-1)
                atBottom = !canScrollVertically(1)
                pulled = false
            }
            MotionEvent.ACTION_MOVE -> if (!pulled) {
                val dy = event.y - downY
                if ((dy > pullDistance && atTop && onPullPastTop()) || (dy < -pullDistance && atBottom && onPullPastBottom())) {
                    // The rest of the swipe belongs to the resize, so the content doesn't also scroll under it.
                    pulled = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
        }
        return pulled || super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (wheel > 0 && !canScrollVertically(-1) && onPullPastTop()) return true
            if (wheel < 0 && !canScrollVertically(1) && onPullPastBottom()) return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun keepHeld() {
        val anchor = held ?: return
        scrollTo(0, top(anchor) + heldOffset - height)
    }

    // The anchor's top within the scrolling content.
    private fun top(view: View): Int {
        var y = 0
        var child = view
        while (child !== this) {
            y += child.top
            child = child.parent as View
        }
        return y
    }
}
