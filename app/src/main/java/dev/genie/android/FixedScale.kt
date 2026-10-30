package dev.genie.android

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.min

// The width of the screens in the Paper design, where 1px is 1dp.
private const val DESIGN_WIDTH_DP = 432

// Genie scales every phone so its short side is as wide as the Paper design, with unscaled text. It
// ignores the system font size and display size settings, so it always looks as designed.
fun Context.fixedScaleConfiguration(): Configuration = Configuration(resources.configuration).apply {
    val metrics = resources.displayMetrics
    val dpi = min(metrics.widthPixels, metrics.heightPixels) * DisplayMetrics.DENSITY_DEFAULT / DESIGN_WIDTH_DP
    screenWidthDp = metrics.widthPixels * DisplayMetrics.DENSITY_DEFAULT / dpi
    screenHeightDp = metrics.heightPixels * DisplayMetrics.DENSITY_DEFAULT / dpi
    smallestScreenWidthDp = min(screenWidthDp, screenHeightDp)
    densityDpi = dpi
    fontScale = 1f
}

fun Context.withFixedScale(): Context = createConfigurationContext(fixedScaleConfiguration())
