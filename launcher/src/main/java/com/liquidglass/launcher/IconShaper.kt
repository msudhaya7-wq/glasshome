package com.liquidglass.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Ella app icons-um iPhone maadhiri ore "squircle" shape-la maathum. */
object IconShaper {

    private var cachedSize = -1
    private var cachedPath: Path? = null

    /** iOS icon shape: superellipse (n = 5). */
    private fun squircle(size: Float): Path {
        val r = size / 2f
        val n = 5.0
        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val t = 2.0 * Math.PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = r + r * sign(c) * abs(c).pow(2.0 / n)
            val y = r + r * sign(s) * abs(s).pow(2.0 / n)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }

    fun shape(context: Context, icon: Drawable): Drawable {
        val density = context.resources.displayMetrics.density
        val size = maxOf(144, (62 * density).toInt())

        // 1) Icon content-a full square-la varaiyuvom
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        if (icon is AdaptiveIconDrawable) {
            // Adaptive icon layers 108dp, adhula 72dp dhaan visible -> 1.5x periya bounds
            val ext = size / 4
            icon.background?.let {
                it.setBounds(-ext, -ext, size + ext, size + ext)
                it.draw(cc)
            }
            icon.foreground?.let {
                it.setBounds(-ext, -ext, size + ext, size + ext)
                it.draw(cc)
            }
        } else {
            // Pazhaya style icon: vellai squircle mela nadula vaippom
            cc.drawColor(Color.WHITE)
            val pad = (size * 0.14f).toInt()
            icon.setBounds(pad, pad, size - pad, size - pad)
            icon.draw(cc)
        }

        // 2) Smooth (anti-aliased) squircle mask
        if (cachedSize != size) {
            cachedPath = squircle(size.toFloat())
            cachedSize = size
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.WHITE
        oc.drawPath(cachedPath!!, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(content, 0f, 0f, paint)
        content.recycle()

        return BitmapDrawable(context.resources, out)
    }
}
