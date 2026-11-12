package com.tvbox.kotlin.core.server.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.event.ServerEvent

/**
 * 搜索推送接收器。
 *
 * 接收远端推送的搜索词广播，转发为事件并启动搜索页。
 *
 * 因 server 模块不依赖 feature 层，目标 Activity 通过字符串类名启动，
 * 类名对应 app 模块 AndroidManifest 中注册的 SearchActivity。
 */
class SearchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION != intent.action) return
        val extras = intent.extras ?: return
        val title = extras.getString(EXTRA_TITLE)
        // 转发事件，上层订阅后可执行回退栈等定制逻辑
        EventHub.post(ServerEvent(SERVER_SEARCH, title))
        // 启动搜索页，CLEAR_TOP 复用已存在的实例；
        // 同时携带 "word" 与 "title" 两个键，兼容读取任一键的调用方
        val target = Intent().apply {
            setClassName(context.packageName, SEARCH_ACTIVITY_CLASS)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_WORD, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(target) }
    }

    companion object {
        const val ACTION = "android.content.movie.search.Action"
        const val EXTRA_TITLE = "title"

        /** 搜索词键，保证远端推送能自动填入并搜索 */
        const val EXTRA_WORD = "word"
        const val SERVER_SEARCH = "server_search"

        /** 目标搜索页全限定名，由 app 模块保证存在 */
        private const val SEARCH_ACTIVITY_CLASS = "com.tvbox.kotlin.feature.search.ui.SearchActivity"
    }
}
