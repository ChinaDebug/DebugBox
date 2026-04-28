package com.undcover.freedom.pyramid;

import android.content.Context;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public class PyToast {
    private static Toast innerToast;
    private static WeakReference<Context> mContextRef;
    private static PyToast sInstance;

    public static void init(Context context) {
        mContextRef = new WeakReference<>(context.getApplicationContext());
    }

    public static PyToast getInstance() {
        if (sInstance == null) {
            synchronized (PyToast.class) {
                if (sInstance == null) {
                    sInstance = new PyToast();
                }
            }
        }
        return sInstance;
    }

    public static void showCancelableToast(String msg) {
        showCancelableToast(msg, Toast.LENGTH_SHORT);
    }

    public static void showCancelableToast(String msg, int duration) {
        if (innerToast != null) {
            innerToast.cancel();
        }

        Context context = mContextRef != null ? mContextRef.get() : null;
        if (context != null) {
            innerToast = Toast.makeText(context, msg, duration);
            innerToast.show();
        }
    }

    public static void showMessage(String msg, int duration) {
        Context context = mContextRef != null ? mContextRef.get() : null;
        if (context != null) {
            Toast.makeText(context, msg, duration).show();
        }
    }
}
