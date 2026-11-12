package com.tvbox.kotlin.core.common

import android.util.Log

/**
 * 统一日志工具，直接输出到 Logcat。
 */
object LOG {

    @JvmStatic
    fun e(t: Throwable) {
        e(AppConstants.TAG, Log.getStackTraceString(t), t)
    }

    @JvmStatic
    fun e(tag: String, t: Throwable) {
        e(tag, Log.getStackTraceString(t), t)
    }

    @JvmStatic
    fun e(msg: String?) {
        e(AppConstants.TAG, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String?) {
        e(tag, msg, null)
    }

    @JvmStatic
    fun e(tag: String, msg: String?, t: Throwable?) {
        Log.e(tag, msg ?: "null", t)
    }

    @JvmStatic
    fun w(msg: String?) {
        w(AppConstants.TAG, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String?) {
        Log.w(tag, msg ?: "null")
    }

    @JvmStatic
    fun i(msg: String?) {
        i(AppConstants.TAG, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String?) {
        Log.i(tag, msg ?: "null")
    }

    @JvmStatic
    fun d(msg: String?) {
        d(AppConstants.TAG, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String?) {
        Log.d(tag, msg ?: "null")
    }
}
