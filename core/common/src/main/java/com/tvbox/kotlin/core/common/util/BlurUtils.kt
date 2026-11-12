package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.graphics.Bitmap
import com.tvbox.kotlin.core.common.LOG

/** 高斯模糊工具，纯 Java StackBlur 实现，兼容所有 Android 版本 */
object BlurUtils {

    private const val MAX_RADIUS = 25f
    private const val SCALE_FACTOR = 0.5f

    /** 对 Bitmap 进行高斯模糊，失败返回原图 */
    @JvmStatic
    fun blur(source: Bitmap?, radiusIn: Float): Bitmap? {
        if (source == null || source.isRecycled) return source
        if (radiusIn <= 0) return source
        val radius = if (radiusIn > MAX_RADIUS) MAX_RADIUS else radiusIn
        return stackBlur(source, radius.toInt())
    }

    /** 缩放后模糊再放大，兼顾效果与性能 */
    @JvmStatic
    fun blurWallpaper(context: Context?, source: Bitmap?, radius: Float): Bitmap? {
        if (source == null || source.isRecycled) return source
        val width = maxOf(1, (source.width * SCALE_FACTOR).toInt())
        val height = maxOf(1, (source.height * SCALE_FACTOR).toInt())
        var scaled: Bitmap? = null
        var blurred: Bitmap? = null
        try {
            scaled = Bitmap.createScaledBitmap(source, width, height, true)
            blurred = blur(scaled, radius)
            if (blurred == null || blurred.isRecycled) return source
            return Bitmap.createScaledBitmap(blurred, source.width, source.height, true)
        } finally {
            if (scaled != null && scaled != source && !scaled.isRecycled) scaled.recycle()
            if (blurred != null && blurred != source && blurred != scaled && !blurred.isRecycled) blurred.recycle()
        }
    }

    private fun stackBlur(source: Bitmap, radius: Int): Bitmap? {
        if (radius < 1) return source
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val bitmap = try {
            source.copy(config, true)
        } catch (e: Throwable) {
            LOG.e(e)
            return source
        } ?: return source
        try {
            val w = bitmap.width
            val h = bitmap.height
            val pix = IntArray(w * h)
            bitmap.getPixels(pix, 0, w, 0, 0, w, h)

            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1

            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            val vmin = IntArray(maxOf(w, h))

            var divsum = (div + 1) shr 1
            divsum *= divsum
            val dv = IntArray(256 * divsum) { it / divsum }

            var yw = 0
            var yi = 0

            val stack = Array(div) { IntArray(3) }

            for (y in 0 until h) {
                var rinsum = 0; var ginsum = 0; var binsum = 0
                var routsum = 0; var goutsum = 0; var boutsum = 0
                var rsum = 0; var gsum = 0; var bsum = 0
                for (i in -radius..radius) {
                    val p = pix[yi + minOf(wm, maxOf(i, 0))]
                    val sir = stack[i + radius]
                    sir[0] = (p shr 16) and 0xff
                    sir[1] = (p shr 8) and 0xff
                    sir[2] = p and 0xff
                    val rbs = radius + 1 - Math.abs(i)
                    rsum += sir[0] * rbs
                    gsum += sir[1] * rbs
                    bsum += sir[2] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                }
                var stackpointer = radius

                for (x in 0 until w) {
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]

                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                    val stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]

                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                    if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
                    val p = pix[yw + vmin[x]]
                    sir[0] = (p shr 16) and 0xff
                    sir[1] = (p shr 8) and 0xff
                    sir[2] = p and 0xff

                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]

                    rsum += rinsum; gsum += ginsum; bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    val sirOut = stack[stackpointer % div]

                    routsum += sirOut[0]; goutsum += sirOut[1]; boutsum += sirOut[2]

                    rinsum -= sirOut[0]; ginsum -= sirOut[1]; binsum -= sirOut[2]

                    yi++
                }
                yw += w
            }

            for (x in 0 until w) {
                var rinsum = 0; var ginsum = 0; var binsum = 0
                var routsum = 0; var goutsum = 0; var boutsum = 0
                var rsum = 0; var gsum = 0; var bsum = 0
                var yp = -radius * w
                for (i in -radius..radius) {
                    val yiPos = maxOf(0, yp) + x
                    val sir = stack[i + radius]
                    sir[0] = r[yiPos]; sir[1] = g[yiPos]; sir[2] = b[yiPos]
                    val rbs = radius + 1 - Math.abs(i)
                    rsum += r[yiPos] * rbs
                    gsum += g[yiPos] * rbs
                    bsum += b[yiPos] * rbs
                    if (i > 0) {
                        rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                    } else {
                        routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                    }
                    if (i < hm) yp += w
                }
                var yiPos = x
                var stackpointer = radius
                for (y in 0 until h) {
                    pix[yiPos] = (0xff000000.toInt() and pix[yiPos]) or
                        (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                    rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                    val stackstart = stackpointer - radius + div
                    val sir = stack[stackstart % div]

                    routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                    if (x == 0) vmin[y] = minOf(y + radius + 1, hm) * w
                    val p = x + vmin[y]
                    sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]

                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]

                    rsum += rinsum; gsum += ginsum; bsum += binsum

                    stackpointer = (stackpointer + 1) % div
                    val sirOut = stack[stackpointer]

                    routsum += sirOut[0]; goutsum += sirOut[1]; boutsum += sirOut[2]

                    rinsum -= sirOut[0]; ginsum -= sirOut[1]; binsum -= sirOut[2]

                    yiPos += w
                }
            }

            bitmap.setPixels(pix, 0, w, 0, 0, w, h)
            return bitmap
        } catch (th: Throwable) {
            LOG.e(th)
            if (!bitmap.isRecycled) bitmap.recycle()
            return source
        }
    }
}
