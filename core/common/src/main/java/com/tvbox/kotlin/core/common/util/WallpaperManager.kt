package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** 壁纸加载与缓存单例 */
class WallpaperManager private constructor() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentTask: Future<*>? = null
    private var cachedBitmap: Bitmap? = null

    fun interface LoadCallback {
        fun onLoaded(bitmap: Bitmap?)
    }

    /** 异步加载壁纸，结果始终在主线程回调 */
    @Synchronized
    fun loadWallpaper(context: Context?, force: Boolean, callback: LoadCallback) {
        if (context == null) {
            callback.onLoaded(null)
            return
        }
        val cached = cachedBitmap
        if (!force && cached != null && !cached.isRecycled) {
            callback.onLoaded(cached)
            return
        }
        currentTask?.cancel(false)
        val appContext = context.applicationContext
        currentTask = executor.submit {
            val result = doLoad(appContext)
            mainHandler.post {
                currentTask = null
                cachedBitmap = result
                callback.onLoaded(result)
            }
        }
    }

    /** 清空缓存 */
    @Synchronized
    fun invalidate() {
        currentTask?.cancel(false)
        currentTask = null
        cachedBitmap = null
    }

    private fun doLoad(context: Context): Bitmap? {
        return try {
            val wp = File(context.filesDir.absolutePath + "/wp")
            if (!wp.exists()) return null
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(wp.absolutePath, opts)
            val imageHeight = opts.outHeight
            val imageWidth = opts.outWidth
            // 目标尺寸为 1280x720 设计稿（TV 盒子窗口上限）。原图更大的壁纸
            // 若按整数除法可能不下采样，导致 1920x1080 全尺寸大图作为窗口背景
            // 每帧渲染拖慢盒子。这里向上取整采样，保证解码后不超过目标尺寸。
            val picHeight = 720
            val picWidth = 1280
            val scaleX = (imageWidth + picWidth - 1) / picWidth
            val scaleY = (imageHeight + picHeight - 1) / picHeight
            val scale = maxOf(maxOf(scaleX, scaleY), 1)
            opts.inJustDecodeBounds = false
            opts.inSampleSize = scale
            val rawBitmap = BitmapFactory.decodeFile(wp.absolutePath, opts)
            if (rawBitmap == null || rawBitmap.isRecycled) return null
            val blurEnabled = AppPreferencesHolder.getBoolean(AppConstants.WALLPAPER_BLUR, true)
            if (blurEnabled) {
                val blurBitmap = BlurUtils.blurWallpaper(context, rawBitmap, 10f)
                if (blurBitmap != null && !blurBitmap.isRecycled) {
                    if (blurBitmap !== rawBitmap && !rawBitmap.isRecycled) {
                        rawBitmap.recycle()
                    }
                    return blurBitmap
                }
            }
            rawBitmap
        } catch (th: Throwable) {
            LOG.e(th)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: WallpaperManager? = null
        private val LOCK = Any()

        @JvmStatic
        fun getInstance(): WallpaperManager {
            return instance ?: synchronized(LOCK) {
                instance ?: WallpaperManager().also { instance = it }
            }
        }
    }
}
