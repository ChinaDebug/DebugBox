package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.event.LogEvent;

import org.greenrobot.eventbus.EventBus;

public class LOG {
    private static String TAG = "TVBox";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    public static void e(Throwable t) {
        Log.e(TAG, t.getMessage(), t);
        if (DEBUG) {
            EventBus.getDefault().post(new LogEvent(String.format("【E/%s】=>>>", TAG) + Log.getStackTraceString(t)));
        }
    }

    public static void e(String tag, Throwable t) {
        Log.e(tag, t.getMessage(), t);
        if (DEBUG) {
            EventBus.getDefault().post(new LogEvent(String.format("【E/%s】=>>>", tag) + Log.getStackTraceString(t)));
        }
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
        if (DEBUG) {
            EventBus.getDefault().post(new LogEvent(String.format("【E/%s】=>>>", TAG) + msg));
        }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        if (DEBUG) {
            EventBus.getDefault().post(new LogEvent(String.format("【E/%s】=>>>", tag) + msg));
        }
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        if (DEBUG) {
            EventBus.getDefault().post(new LogEvent(String.format("【E/%s】=>>>", tag) + msg + "\n" + Log.getStackTraceString(t)));
        }
    }

    public static void i(String msg) {
        if (DEBUG) {
            Log.i(TAG, msg);
            EventBus.getDefault().post(new LogEvent(String.format("【I/%s】=>>>", TAG) + msg));
        }
    }

    public static void i(String tag, String msg) {
        if (DEBUG) {
            Log.i(tag, msg);
            EventBus.getDefault().post(new LogEvent(String.format("【I/%s】=>>>", tag) + msg));
        }
    }

    public static void i(String tag, String msg, Throwable t) {
        if (DEBUG) {
            Log.i(tag, msg, t);
            EventBus.getDefault().post(new LogEvent(String.format("【I/%s】=>>>", tag) + msg + "\n" + Log.getStackTraceString(t)));
        }
    }

    public static void d(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    public static void d(String tag, String msg) {
        if (DEBUG) {
            Log.d(tag, msg);
        }
    }

    public static void d(String tag, String msg, Throwable t) {
        if (DEBUG) {
            Log.d(tag, msg, t);
        }
    }
}