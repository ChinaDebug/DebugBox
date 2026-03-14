package com.github.tvbox.osc.util;

import android.content.Context;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.base.App;

import java.lang.ref.WeakReference;

public class ToastHelper {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static WeakReference<Toast> currentToastRef = null;

    public static void showToast(Context context, String text) {
        Context appContext = context != null ? context.getApplicationContext() : App.getInstance();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(appContext, text, Toast.LENGTH_SHORT);
        } else {
            mainHandler.post(() -> showToastInternal(appContext, text, Toast.LENGTH_SHORT));
        }
    }

    public static void showToast(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(App.getInstance(), text, Toast.LENGTH_SHORT);
        } else {
            mainHandler.post(() -> showToastInternal(App.getInstance(), text, Toast.LENGTH_SHORT));
        }
    }

    public static void showLong(Context context, String text) {
        Context appContext = context != null ? context.getApplicationContext() : App.getInstance();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(appContext, text, Toast.LENGTH_LONG);
        } else {
            mainHandler.post(() -> showToastInternal(appContext, text, Toast.LENGTH_LONG));
        }
    }

    public static void showLong(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showToastInternal(App.getInstance(), text, Toast.LENGTH_LONG);
        } else {
            mainHandler.post(() -> showToastInternal(App.getInstance(), text, Toast.LENGTH_LONG));
        }
    }

    private static void showToastInternal(Context context, String text, int duration) {
        // 先取消之前的 Toast，避免内存泄漏
        cancelToast();
        if (context == null) return;
        try {
            Toast toast = Toast.makeText(context, text, duration);
            currentToastRef = new WeakReference<>(toast);
            toast.show();
        } catch (Exception e) {
            // 忽略 Toast 显示异常
        }
    }

    /**
     * 取消当前显示的 Toast，防止内存泄漏
     */
    public static void cancelToast() {
        if (currentToastRef != null) {
            Toast toast = currentToastRef.get();
            if (toast != null) {
                try {
                    toast.cancel();
                } catch (Exception e) {
                    // 忽略取消异常
                }
            }
            currentToastRef = null;
        }
    }

    public static void debugToast(Context context, String text) {
        if (HawkConfig.isDebug()) {
            showToast(context, text);
        }
    }

    public static void debugToast(String text) {
        if (HawkConfig.isDebug()) {
            showToast(text);
        }
    }
}
