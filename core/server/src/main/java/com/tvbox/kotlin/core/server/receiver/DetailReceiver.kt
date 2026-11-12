package com.tvbox.kotlin.core.server.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent

/**
 * 详情推送接收器。
 *
 * 接收远端推送的镜像播放广播，转发为事件并启动详情页。
 *
 * 因 server 模块不依赖 feature 层，目标 Activity 通过字符串类名启动，
 * 类名对应 app 模块 AndroidManifest 中注册的 DetailActivity（singleTask）。
 */
class DetailReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION != intent.action) return
        val extras = intent.extras ?: return
        val id = extras.getString(EXTRA_ID)
        val sourceKey = extras.getString(EXTRA_SOURCE_KEY)
        // 转发事件，上层订阅后可执行业务逻辑
        EventHub.post(TypedEvent(TYPE_MIRROR, id to sourceKey))
        // 启动详情页，singleTask + CLEAR_TOP 复用已存在实例
        val target = Intent().apply {
            setClassName(context.packageName, DETAIL_ACTIVITY_CLASS)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_SOURCE_KEY, sourceKey)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(target) }
    }

    companion object {
        const val ACTION = "android.content.movie.detail.Action"
        const val EXTRA_ID = "id"
        const val EXTRA_SOURCE_KEY = "sourceKey"
        const val TYPE_MIRROR = "mirror_detail"

        /** 目标详情页全限定名，由 app 模块保证存在 */
        private const val DETAIL_ACTIVITY_CLASS = "com.tvbox.kotlin.feature.detail.ui.DetailActivity"
    }
}
