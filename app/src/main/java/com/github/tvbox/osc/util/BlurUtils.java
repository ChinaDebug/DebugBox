package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * 壁纸虚化工具类
 * 使用纯 Java 的 StackBlur（Box Blur）实现高斯模糊效果，兼容所有 Android 版本，不依赖已废弃的 RenderScript
 */
public class BlurUtils {

    // 最大模糊半径
    private static final float MAX_RADIUS = 25f;
    // 高分辨率壁纸先做缩放，降低模糊计算量；取值越大虚化越轻，保留更多原图细节
    private static final float SCALE_FACTOR = 0.5f;

    /**
     * 对传入的 Bitmap 进行高斯模糊处理
     *
     * @param source 原始 Bitmap
     * @param radius 模糊半径，建议 1~25
     * @return 模糊后的 Bitmap，若失败则返回原图
     */
    public static Bitmap blur(Bitmap source, float radius) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        if (radius <= 0) {
            return source;
        }
        if (radius > MAX_RADIUS) {
            radius = MAX_RADIUS;
        }
        return stackBlur(source, (int) radius);
    }

    /**
     * 将壁纸按固定比例缩放后模糊，再放大回原尺寸，兼顾效果与性能
     */
    public static Bitmap blurWallpaper(Context context, Bitmap source, float radius) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        int width = Math.max(1, (int) (source.getWidth() * SCALE_FACTOR));
        int height = Math.max(1, (int) (source.getHeight() * SCALE_FACTOR));
        Bitmap scaled = null;
        Bitmap blurred = null;
        Bitmap enlarged = null;
        try {
            scaled = Bitmap.createScaledBitmap(source, width, height, true);
            blurred = blur(scaled, radius);
            if (blurred == null || blurred.isRecycled()) {
                return source;
            }
            enlarged = Bitmap.createScaledBitmap(blurred, source.getWidth(), source.getHeight(), true);
            return enlarged;
        } finally {
            // 回收中间产物，避免内存泄漏；注意 blurred 失败时可能等于 scaled，避免重复回收
            if (scaled != null && scaled != source && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (blurred != null && blurred != source && blurred != scaled && !blurred.isRecycled()) {
                blurred.recycle();
            }
        }
    }

    /**
     * StackBlur 纯 Java 实现
     */
    private static Bitmap stackBlur(Bitmap source, int radius) {
        if (radius < 1) {
            return source;
        }
        Bitmap bitmap = source.copy(source.getConfig() != null ? source.getConfig() : Bitmap.Config.ARGB_8888, true);
        if (bitmap == null) {
            return source;
        }
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pix = new int[w * h];
            bitmap.getPixels(pix, 0, w, 0, 0, w, h);

            int wm = w - 1;
            int hm = h - 1;
            int wh = w * h;
            int div = radius + radius + 1;

            int r[] = new int[wh];
            int g[] = new int[wh];
            int b[] = new int[wh];
            int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
            int vmin[] = new int[Math.max(w, h)];

            int divsum = (div + 1) >> 1;
            divsum *= divsum;
            int dv[] = new int[256 * divsum];
            for (i = 0; i < 256 * divsum; i++) {
                dv[i] = (i / divsum);
            }

            yw = yi = 0;

            int[][] stack = new int[div][3];
            int stackpointer;
            int stackstart;
            int[] sir;
            int rbs;
            int r1 = radius + 1;
            int routsum, goutsum, boutsum;
            int rinsum, ginsum, binsum;

            for (y = 0; y < h; y++) {
                rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
                for (i = -radius; i <= radius; i++) {
                    p = pix[yi + Math.min(wm, Math.max(i, 0))];
                    sir = stack[i + radius];
                    sir[0] = (p & 0xff0000) >> 16;
                    sir[1] = (p & 0x00ff00) >> 8;
                    sir[2] = (p & 0x0000ff);
                    rbs = r1 - Math.abs(i);
                    rsum += sir[0] * rbs;
                    gsum += sir[1] * rbs;
                    bsum += sir[2] * rbs;
                    if (i > 0) {
                        rinsum += sir[0];
                        ginsum += sir[1];
                        binsum += sir[2];
                    } else {
                        routsum += sir[0];
                        goutsum += sir[1];
                        boutsum += sir[2];
                    }
                }
                stackpointer = radius;

                for (x = 0; x < w; x++) {
                    r[yi] = dv[rsum];
                    g[yi] = dv[gsum];
                    b[yi] = dv[bsum];

                    rsum -= routsum;
                    gsum -= goutsum;
                    bsum -= boutsum;

                    stackstart = stackpointer - radius + div;
                    sir = stack[stackstart % div];

                    routsum -= sir[0];
                    goutsum -= sir[1];
                    boutsum -= sir[2];

                    if (y == 0) {
                        vmin[x] = Math.min(x + radius + 1, wm);
                    }
                    p = pix[yw + vmin[x]];
                    sir[0] = (p & 0xff0000) >> 16;
                    sir[1] = (p & 0x00ff00) >> 8;
                    sir[2] = (p & 0x0000ff);

                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];

                    rsum += rinsum;
                    gsum += ginsum;
                    bsum += binsum;

                    stackpointer = (stackpointer + 1) % div;
                    sir = stack[(stackpointer) % div];

                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];

                    rinsum -= sir[0];
                    ginsum -= sir[1];
                    binsum -= sir[2];

                    yi++;
                }
                yw += w;
            }

            for (x = 0; x < w; x++) {
                rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
                yp = -radius * w;
                for (i = -radius; i <= radius; i++) {
                    yi = Math.max(0, yp) + x;
                    sir = stack[i + radius];
                    sir[0] = r[yi];
                    sir[1] = g[yi];
                    sir[2] = b[yi];
                    rbs = r1 - Math.abs(i);
                    rsum += r[yi] * rbs;
                    gsum += g[yi] * rbs;
                    bsum += b[yi] * rbs;
                    if (i > 0) {
                        rinsum += sir[0];
                        ginsum += sir[1];
                        binsum += sir[2];
                    } else {
                        routsum += sir[0];
                        goutsum += sir[1];
                        boutsum += sir[2];
                    }
                    if (i < hm) {
                        yp += w;
                    }
                }
                yi = x;
                stackpointer = radius;
                for (y = 0; y < h; y++) {
                    pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                    rsum -= routsum;
                    gsum -= goutsum;
                    bsum -= boutsum;

                    stackstart = stackpointer - radius + div;
                    sir = stack[stackstart % div];

                    routsum -= sir[0];
                    goutsum -= sir[1];
                    boutsum -= sir[2];

                    if (x == 0) {
                        vmin[y] = Math.min(y + r1, hm) * w;
                    }
                    p = x + vmin[y];
                    sir[0] = r[p];
                    sir[1] = g[p];
                    sir[2] = b[p];

                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];

                    rsum += rinsum;
                    gsum += ginsum;
                    bsum += binsum;

                    stackpointer = (stackpointer + 1) % div;
                    sir = stack[stackpointer];

                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];

                    rinsum -= sir[0];
                    ginsum -= sir[1];
                    binsum -= sir[2];

                    yi += w;
                }
            }

            bitmap.setPixels(pix, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Throwable th) {
            LOG.e(th);
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return source;
        }
    }
}
