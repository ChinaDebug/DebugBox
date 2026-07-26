package com.github.tvbox.osc.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 壁纸管理单例
 * 负责壁纸文件的异步解码、虚化以及 Bitmap 缓存，避免在 Activity 主线程执行耗时操作
 */
public class WallpaperManager {

    private static volatile WallpaperManager instance;
    private static final Object LOCK = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 当前正在执行的加载任务，用于取消重复请求
    private Future<?> currentTask;
    // 缓存的处理后 Bitmap（已被所有 Activity 共享，invalidate 时不主动 recycle，避免影响正在显示的界面）
    private Bitmap cachedBitmap;

    public interface LoadCallback {
        void onLoaded(Bitmap bitmap);
    }

    public static WallpaperManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new WallpaperManager();
                }
            }
        }
        return instance;
    }

    /**
     * 异步加载壁纸
     *
     * @param context  上下文，用于获取文件路径与资源
     * @param force    是否强制重新加载，忽略缓存
     * @param callback 加载完成回调，始终在主线程触发
     */
    public void loadWallpaper(Context context, boolean force, LoadCallback callback) {
        if (context == null) {
            callback.onLoaded(null);
            return;
        }
        if (!force && cachedBitmap != null && !cachedBitmap.isRecycled()) {
            callback.onLoaded(cachedBitmap);
            return;
        }
        // 取消上一个未完成的任务，避免重复加载
        if (currentTask != null) {
            currentTask.cancel(false);
        }
        final Context appContext = context.getApplicationContext();
        currentTask = executor.submit(new Runnable() {
            @Override
            public void run() {
                final Bitmap result = doLoad(appContext);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        currentTask = null;
                        cachedBitmap = result;
                        callback.onLoaded(result);
                    }
                });
            }
        });
    }

    /**
     * 清空缓存，使下次加载重新读取文件
     */
    public void invalidate() {
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
        // 不主动 recycle cachedBitmap，因为可能仍有 Activity 的 BitmapDrawable 在引用它
        cachedBitmap = null;
    }

    /**
     * 释放线程池，应用退出时调用
     */
    public void shutdown() {
        invalidate();
        executor.shutdown();
    }

    private Bitmap doLoad(Context context) {
        try {
            File wp = new File(context.getFilesDir().getAbsolutePath() + "/wp");
            if (!wp.exists()) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
            int imageHeight = opts.outHeight;
            int imageWidth = opts.outWidth;
            int picHeight = 720;
            int picWidth = 1080;
            int scaleX = imageWidth / picWidth;
            int scaleY = imageHeight / picHeight;
            int scale = Math.max(Math.max(scaleX, scaleY), 1);
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = scale;
            Bitmap rawBitmap = BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
            if (rawBitmap == null || rawBitmap.isRecycled()) {
                return null;
            }
            // 根据开关对壁纸进行虚化处理，默认开启
            if (Hawk.get(HawkConfig.WALLPAPER_BLUR, true)) {
                Bitmap blurBitmap = BlurUtils.blurWallpaper(context, rawBitmap, 10f);
                if (blurBitmap != null && !blurBitmap.isRecycled()) {
                    if (blurBitmap != rawBitmap && !rawBitmap.isRecycled()) {
                        rawBitmap.recycle();
                    }
                    return blurBitmap;
                }
            }
            return rawBitmap;
        } catch (Throwable th) {
            LOG.e(th);
            return null;
        }
    }
}
