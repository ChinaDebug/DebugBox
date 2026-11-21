package com.tvbox.kotlin.core.spider

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.github.catvod.Proxy
import com.github.catvod.crawler.JarLoader
import java.util.concurrent.Executors

/**
 * 爬虫搜索隔离服务，运行在独立 `:spider` 进程。
 *
 * 加固 jar 的搜索逻辑在 native 层，崩溃表现为 SIGSEGV 而非 Java 异常，无法在主进程捕获；
 * 将搜索调用转移到该服务后，native 崩溃只会杀死本进程，主进程 UI 不受影响。
 */
class IsolatedSpiderService : Service() {

    private val executor = Executors.newCachedThreadPool()
    private lateinit var handlerThread: HandlerThread
    private lateinit var messenger: Messenger

    /** jar 加载器与 spider 实例仅在本进程使用 */
    private val jarLoader = JarLoader()

    override fun onCreate() {
        super.onCreate()
        // jar 的 Init.init 会强转 Application，必须注入 Application 而非 Service 本身
        runCatching { com.github.catvod.Init.set(applicationContext) }
        handlerThread = HandlerThread("spider-service").also { it.start() }
        messenger = Messenger(Handler(handlerThread.looper) { msg ->
            onMessage(msg)
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        executor.shutdownNow()
        runCatching { jarLoader.clear() }
        handlerThread.quitSafely()
        super.onDestroy()
        // 所有绑定释放后主动结束本进程，及时释放 jar/native 占用的内存
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun onMessage(msg: Message) {
        if (msg.what != MSG_SEARCH) return
        val data = msg.data ?: return
        val requestId = data.getLong(KEY_REQUEST_ID)
        val replyTo = msg.replyTo
        val sourceKey = data.getString(KEY_SOURCE_KEY).orEmpty()
        val api = data.getString(KEY_API).orEmpty()
        val ext = data.getString(KEY_EXT).orEmpty()
        val siteJar = data.getString(KEY_SITE_JAR).orEmpty()
        val mainJar = data.getString(KEY_MAIN_JAR).orEmpty()
        val keyword = data.getString(KEY_KEYWORD).orEmpty()
        val proxyPort = data.getInt(KEY_PROXY_PORT)
        executor.execute {
            val result = Bundle().apply { putLong(KEY_REQUEST_ID, requestId) }
            try {
                // 主进程的本地服务端口需要同步到 jar 内 Proxy 占位类
                runCatching { Proxy.set(proxyPort) }
                result.putString(KEY_JSON, search(sourceKey, api, ext, siteJar, mainJar, keyword))
            } catch (e: Throwable) {
                result.putString(KEY_ERROR, e.message ?: e.javaClass.simpleName)
            }
            runCatching {
                replyTo?.send(Message.obtain(null, MSG_RESULT).apply { this.data = result })
            }
        }
    }

    private fun search(
        sourceKey: String,
        api: String,
        ext: String,
        siteJar: String,
        mainJar: String,
        keyword: String
    ): String {
        if (siteJar.isEmpty() && mainJar.isNotEmpty()) {
            // 先加载主 jar：parseJar 命中本地缓存时不会重复下载
            jarLoader.parseJar(MAIN_JAR_KEY, mainJar)
        }
        val spider = jarLoader.getSpider(sourceKey, api, ext, siteJar)
        return spider.searchContent(keyword, false)
    }

    companion object {
        private const val MAIN_JAR_KEY = "main"

        const val MSG_SEARCH = 1
        const val MSG_RESULT = 2

        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SOURCE_KEY = "source_key"
        const val KEY_API = "api"
        const val KEY_EXT = "ext"
        const val KEY_SITE_JAR = "site_jar"
        const val KEY_MAIN_JAR = "main_jar"
        const val KEY_KEYWORD = "keyword"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_JSON = "json"
        const val KEY_ERROR = "error"
    }
}
