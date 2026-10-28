package dev.genie.android

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class PopupScrollView(context: Context, attributes: AttributeSet) : ScrollView(context, attributes) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenLimit = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) screenLimit
            else minOf(screenLimit, MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
