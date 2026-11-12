package com.tvbox.kotlin.core.server.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 自定义 Web 接收器。
 *
 * 接收源/直播/解析刷新广播，为上层注册的回调分发变更事件。
 */
class CustomWebReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION != intent.action) return
        val action = intent.extras?.getString(EXTRA_ACTION) ?: return
        synchronized(callbacks) {
            callbacks.forEach { it.onChange(action, null) }
        }
    }

    /** 变更回调接口 */
    interface Callback {
        fun onChange(action: String?, obj: Any?)
    }

    companion object {
        const val ACTION = "android.content.movie.custom.web.Action"
        const val REFRESH_SOURCE = "source"
        const val REFRESH_LIVE = "live"
        const val REFRESH_PARSE = "parse"
        const val EXTRA_ACTION = "action"

        /** 静态回调列表，外部注册/反注册需自行保证线程安全 */
        @JvmStatic
        val callbacks: MutableList<Callback> = ArrayList()
    }
}
