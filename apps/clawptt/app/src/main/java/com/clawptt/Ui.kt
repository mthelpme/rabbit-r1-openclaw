package com.clawptt

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/** The openclaw design system, for the programmatic panel/settings UI. */
object Ui {

    fun col(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)
    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun figtree(ctx: Context): Typeface = ResourcesCompat.getFont(ctx, R.font.figtree) ?: Typeface.SANS_SERIF
    fun figtreeSemibold(ctx: Context): Typeface = Typeface.create(figtree(ctx), 600, false)
    fun figtreeBold(ctx: Context): Typeface = Typeface.create(figtree(ctx), 700, false)

    /** Fully-rounded pill fill. */
    fun pill(fill: Int, radiusPx: Float = 9999f) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(fill)
    }

    /** Fully-rounded outlined pill. */
    fun pillStroke(ctx: Context, stroke: Int, widthDp: Float = 1.5f, radiusPx: Float = 9999f) =
        GradientDrawable().apply {
            cornerRadius = radiusPx; setColor(Color.TRANSPARENT); setStroke(dp(ctx, widthDp), stroke)
        }

    fun card(ctx: Context, fill: Int, radiusDp: Float = 10f) = GradientDrawable().apply {
        cornerRadius = dpf(ctx, radiusDp); setColor(fill)
    }

    /** Radial glow (oval) fading center -> transparent. */
    fun glow(center: Int, radiusPx: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        shape = GradientDrawable.OVAL
        gradientRadius = radiusPx
        colors = intArrayOf(center, Color.TRANSPARENT)
    }

    /** Vertical background gradient (top -> bottom). */
    fun bgGradient(top: Int, bottom: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    )

    fun colorWithAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** saturation 0..1, brightness multiplier. */
    fun mascotFilter(saturation: Float, brightness: Float): ColorMatrixColorFilter {
        val m = ColorMatrix().apply { setSaturation(saturation) }
        m.postConcat(ColorMatrix(floatArrayOf(
            brightness, 0f, 0f, 0f, 0f,
            0f, brightness, 0f, 0f, 0f,
            0f, 0f, brightness, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        return ColorMatrixColorFilter(m)
    }
}
