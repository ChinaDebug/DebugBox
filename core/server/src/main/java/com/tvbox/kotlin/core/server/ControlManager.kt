package com.tvbox.kotlin.core.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.UrlUtils
import com.tvbox.kotlin.core.server.receiver.DetailReceiver
import com.tvbox.kotlin.core.server.receiver.PushReceiver
import com.tvbox.kotlin.core.server.receiver.SearchReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地服务控制管理器。
 *
 * 由 Hilt 注入为单例，负责启动/停止 [RemoteServer]、维护引用计数、
 * 转发 [DataReceiver] 回调到事件总线与广播。
 *
 * 端口自 [AppConstants.LOCAL_SERVER_PORT] 起递增直到 9999，启动成功后通知上层。
 */
@Singleton
class ControlManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val host: ServerHost,
    private val preferences: AppPreferences
) {

    @Volatile
    private var server: RemoteServer? = null

    /** 当前监听端口 */
    @Volatile
    private var currentPort: Int = AppConstants.LOCAL_SERVER_PORT

    /** 引用计数，支持多业务同时持有服务 */
    private var serverRefCount = 0

    /** 服务地址：local=true 返回回环地址，false 返回本机局域网地址 */
    fun getAddress(local: Boolean): String {
        val s = server ?: return ""
        if (!s.isStarting()) return ""
        return if (local) s.getLoadAddress() else s.getServerAddress()
    }

    /** 服务是否运行中 */
    fun isServerRunning(): Boolean = server?.isStarting() == true

    /** 当前监听端口，服务未启动返回 -1 */
    fun currentPort(): Int = if (isServerRunning()) currentPort else -1

    /** 当前服务实例（用于设置媒体信息等），未启动返回 null */
    fun getServer(): RemoteServer? = server

    /**
     * 启动本地服务，引用计数 +1。
     * 端口被占用时自动 +1 重试，直到 9999。
     */
    @Synchronized
    fun startServer() {
        serverRefCount++
        if (serverRefCount > 1 && server != null && server!!.isStarting()) {
            return
        }
        var port = AppConstants.LOCAL_SERVER_PORT
        while (port < 9999) {
            val remote = RemoteServer(context, port, host)
            remote.setDataReceiver(buildDataReceiver())
            try {
                remote.start()
                server = remote
                currentPort = port
                val dohEnabled = preferences.getInt(AppConstants.DOH_URL, 0) > 0
                host.onServerStarted(port, dohEnabled)
                return
            } catch (e: IOException) {
                try {
                    remote.stop()
                } catch (_: Exception) {
                    // 忽略停止异常
                }
                port++
            }
        }
        // 所有端口均启动失败，回滚引用计数
        serverRefCount--
    }

    /** 停止本地服务，引用计数 -1，归零时真正停止 */
    @Synchronized
    fun stopServer() {
        serverRefCount--
        if (serverRefCount > 0) return
        serverRefCount = 0
        server?.takeIf { it.isStarting() }?.stop()
        server = null
    }

    /** 构造 DataReceiver，将远端推送转发到事件总线与广播 */
    private fun buildDataReceiver(): DataReceiver {
        return object : DataReceiver {
            override fun onTextReceived(text: String?) {
                if (text.isNullOrBlank()) return
                val intent = Intent().apply {
                    action = SearchReceiver.ACTION
                    setPackage(context.packageName)
                    component = ComponentName(context, SearchReceiver::class.java)
                    putExtras(Bundle().apply { putString("title", text) })
                }
                context.sendBroadcast(intent)
            }

            override fun onApiReceived(url: String?) {
                // 事件推送：应用内订阅设置对话框打开时同步回显到编辑框（不落盘，落盘由对话框提交完成）
                EventHub.post(TypedEvent(TYPE_API_URL_CHANGE, url))
            }

            override fun onLiveReceived(url: String?) {
                EventHub.post(TypedEvent(TYPE_LIVE_URL_CHANGE, url))
            }

            override fun onEpgReceived(url: String?) {
                EventHub.post(TypedEvent(TYPE_EPG_URL_CHANGE, url))
            }

            override fun onPushReceived(url: String?) {
                if (url.isNullOrBlank()) return
                // 通过显式广播交由 PushReceiver 启动播放/详情页，
                // 避免依赖 HomeActivity 前台订阅 EventHub 导致后台推送事件丢失
                val intent = Intent().apply {
                    action = PushReceiver.ACTION
                    setPackage(context.packageName)
                    component = ComponentName(context, PushReceiver::class.java)
                    putExtra(PushReceiver.EXTRA_URL, url)
                    putExtra(PushReceiver.EXTRA_DIRECT, UrlUtils.isDirectPlayUrl(url))
                    putExtra(PushReceiver.EXTRA_HAS_PUSH_AGENT, host.hasPushAgent())
                }
                context.sendBroadcast(intent)
            }

            override fun onMirrorReceived(id: String?, sourceKey: String?) {
                if (id.isNullOrBlank() || sourceKey.isNullOrBlank()) return
                val intent = Intent().apply {
                    action = DetailReceiver.ACTION
                    setPackage(context.packageName)
                    component = ComponentName(context, DetailReceiver::class.java)
                    putExtras(Bundle().apply {
                        putString("id", id)
                        putString("sourceKey", sourceKey)
                    })
                }
                context.sendBroadcast(intent)
            }

            override fun onDanmuReceived(url: String?) {
                if (url.isNullOrBlank()) return
                // 弹幕源由爬虫异步生成，播放页按事件订阅接收后立即加载
                EventHub.post(TypedEvent(TYPE_DANMU_REFRESH, url))
            }

            override fun onSubmitAll(api: String?, live: String?, epg: String?) {
                // 统一保存全部配置（同步更新缓存），再通知首页重载生效
                if (!api.isNullOrEmpty()) preferences.putSync(AppConstants.API_URL, api)
                if (!live.isNullOrEmpty()) preferences.putSync(AppConstants.LIVE_URL, live)
                if (!epg.isNullOrEmpty()) preferences.putSync(AppConstants.EPG_URL, epg)
                // 首页可能不在前台（如设置页订阅设置对话框打开时），EventHub 事件会丢；
                // 写持久化待重载标记，HomeActivity 收到事件或 onResume 时消费
                preferences.putSync(AppConstants.RELOAD_PENDING, true)
                EventHub.post(TypedEvent(TYPE_SUBMIT_ALL, true))
            }
        }
    }

    companion object {
        const val TYPE_API_URL_CHANGE = "api_url_change"
        const val TYPE_LIVE_URL_CHANGE = "live_url_change"
        const val TYPE_EPG_URL_CHANGE = "epg_url_change"

        /** web 订阅设置界面"提交全部并重载"按钮触发的统一重载事件 */
        const val TYPE_SUBMIT_ALL = "submit_all"

        /** 爬虫异步生成弹幕后的刷新事件，payload 为弹幕地址 */
        const val TYPE_DANMU_REFRESH = "danmu_refresh"
    }
}
