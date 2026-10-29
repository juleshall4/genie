package dev.genie.android

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import java.util.function.Consumer

/**
 * Frosts the screen behind the Genie box. Android can only blur behind a whole window, so this adds an
 * empty, untouchable window under the overlay that tracks the box's bounds and corners. The box itself
 * still draws its tint and animates in the overlay window; this window only carries the blur.
 * Does nothing where cross-window blur is unsupported or switched off (for example, in battery saver).
 */
class BoxBlur(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val radius = (BLUR_DP * context.resources.displayMetrics.density).toInt()
    private var dialog: Dialog? = null
    private var box: View? = null
    private val bounds = Rect()
    private val location = IntArray(2)
    private var corner = -1f
    private val follow = ViewTreeObserver.OnPreDrawListener { sync(); true }
    private val enabled = Consumer<Boolean> { on -> dialog?.window?.setBackgroundBlurRadius(if (on) radius else 0) }

    /** Adds the blur window; call before adding the overlay window so it sits underneath. */
    fun show() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !windowManager.isCrossWindowBlurEnabled) return
        dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar).apply {
            window!!.apply {
                setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                setFormat(PixelFormat.TRANSLUCENT)
                setWindowAnimations(0)
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply {
                    gravity = Gravity.TOP or Gravity.START
                    width = 1
                    height = 1
                    fitInsetsTypes = 0
                }
                setBackgroundBlurRadius(radius)
            }
            show()
        }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, enabled)
    }

    /** Starts following [box], syncing on each frame the overlay draws. */
    fun follow(box: View) {
        if (dialog == null) return
        this.box = box
        box.viewTreeObserver.addOnPreDrawListener(follow)
    }

    fun dismiss() {
        box?.viewTreeObserver?.removeOnPreDrawListener(follow)
        box = null
        if (dialog == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        windowManager.removeCrossWindowBlurEnabledListener(enabled)
        dialog?.dismiss()
        dialog = null
        corner = -1f
        bounds.setEmpty()
    }

    private fun sync() {
        val box = box ?: return
        val window = dialog?.window ?: return
        box.getLocationOnScreen(location)
        val next = Rect(location[0], location[1], location[0] + box.width, location[1] + box.height)
        if (next != bounds) {
            bounds.set(next)
            window.attributes = window.attributes.apply {
                x = next.left
                y = next.top
                width = next.width()
                height = next.height()
            }
        }
        // The blur is clipped to the corners of the window's background.
        val nextCorner = (box.background as? GradientDrawable)?.cornerRadius ?: 0f
        if (nextCorner != corner) {
            corner = nextCorner
            window.setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = nextCorner
            })
        }
    }

    private companion object {
        // Matches the 30px backdrop blur in the Paper design (drawn at 1px = 1dp).
        const val BLUR_DP = 30f
    }
}
