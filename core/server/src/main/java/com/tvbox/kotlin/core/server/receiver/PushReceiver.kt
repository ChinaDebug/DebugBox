package com.tvbox.kotlin.core.server.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 推送链接接收器。
 *
 * 接收远端推送的播放地址广播（由 [com.tvbox.kotlin.core.server.ControlManager] 发送），
 * 直接启动播放页或详情页，不依赖 HomeActivity 前台订阅事件，避免事件丢失导致"推送无反应"。
 *
 * 直链地址进入播放页直接播放；非直链且有 push_agent 源时进入详情页由该源解析；
 * 两者都不满足时提示无法解析。
 */
class PushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION != intent.action) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val direct = intent.getBooleanExtra(EXTRA_DIRECT, false)
        val hasPushAgent = intent.getBooleanExtra(EXTRA_HAS_PUSH_AGENT, false)

        val targetClass = when {
            direct -> PLAY_ACTIVITY_CLASS
            hasPushAgent -> DETAIL_ACTIVITY_CLASS
            else -> null
        }
        if (targetClass == null) {
            Toast.makeText(
                context,
                "当前源未配置支持push_agent的源\n无法解析该推送地址",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val target = Intent().apply {
            setClassName(context.packageName, targetClass)
            if (direct) {
                putExtra("url", url)
                putExtra("title", "推送视频")
                putExtra("position", 0L)
            } else {
                putExtra("vodId", url)
                putExtra("sourceKey", "push_agent")
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(target) }
    }

    companion object {
        const val ACTION = "android.content.movie.push.Action"
        const val EXTRA_URL = "url"
        const val EXTRA_DIRECT = "direct"
        const val EXTRA_HAS_PUSH_AGENT = "hasPushAgent"

        /** 目标页面全限定名，由 app 模块保证存在 */
        private const val PLAY_ACTIVITY_CLASS = "com.tvbox.kotlin.feature.play.ui.PlayActivity"
        private const val DETAIL_ACTIVITY_CLASS = "com.tvbox.kotlin.feature.detail.ui.DetailActivity"
    }
}
