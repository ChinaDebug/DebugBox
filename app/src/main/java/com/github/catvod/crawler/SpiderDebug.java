package com.github.catvod.crawler;

import android.util.Log;

public class SpiderDebug {
    private static final String TAG = "SpiderLog";

    public static void log(Throwable th) {
        Log.e(TAG, th.getMessage(), th);
    }

    public static void log(String msg) {
        Log.d(TAG, msg);
    }

    public static String ec(int i) {
        return "";
    }
}
