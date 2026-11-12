package com.tvbox.kotlin.app.di

import android.content.Context
import com.github.catvod.crawler.JsSpiderFactory
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import com.tvbox.kotlin.app.js.GlobalBridge
import com.tvbox.kotlin.app.js.JsSpider
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.engine.quickjs.JsLocalStore
import com.tvbox.kotlin.engine.quickjs.QuickJsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * JS 爬虫工厂实现，集成 QuickJS 运行时。
 *
 * 由 [com.github.catvod.crawler.JsLoader] 通过其 jsSpiderFactory 字段调用，
 * 加载远程/本地 JS 脚本并创建 [JsSpider] 实例。
 *
 * 工作流程：
 * 1. 初始化 QuickJS 引擎，注入桥接对象（GlobalBridge）、远程模块加载源与 local 持久化存储；
 * 2. 远程主脚本下载到本地缓存目录后加载；
 * 3. 加载失败回落到 SpiderNull，保证调用方不崩溃。
 */
@Singleton
class JsSpiderFactoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: QuickJsEngine,
    private val okHttpHelper: OkHttpHelper,
    private val controlManagerProvider: Provider<ControlManager>,
    private val appPreferences: AppPreferences
) : JsSpiderFactory {

    init {
        engine.init(context)
        engine.moduleSource = ::loadRemoteContent
        engine.localStore = object : JsLocalStore {
            override fun get(key: String, default: String): String = appPreferences.getString(key, default)

            override fun set(key: String, value: String) {
                appPreferences.putSync(key, value)
            }

            override fun delete(key: String) {
                runBlocking { appPreferences.remove(key) }
            }
        }
    }

    override fun create(key: String, api: String, classLoader: Class<*>?): Spider {
        if (api.isEmpty()) return SpiderNull()
        return try {
            // 远程脚本直接以 http 地址交给引擎加载，保证相对模块导入（./xxx.js）能按 URL 解析下载；
            // 非 http 的本地/内置资源路径经 ensureLocalScript 归一化
            val loadTarget = if (api.startsWith("http://") || api.startsWith("https://")) api else ensureLocalScript(api, key)
            if (loadTarget.isEmpty()) {
                LOG.e(TAG, "JS 脚本无法获取: $api")
                return SpiderNull()
            }
            val bridge = GlobalBridge(okHttpHelper, controlManagerProvider.get())
            val handle = engine.loadSpider(key, loadTarget, "", classLoader, bridge)
            if (handle == null) {
                return SpiderNull()
            }
            JsSpider(engine, handle)
        } catch (e: Throwable) {
            LOG.e(TAG, "JS 爬虫创建失败: key=$key, api=$api, error=${e.message}")
            SpiderNull()
        }
    }

    /** 远程模块/主脚本内容加载（带磁盘缓存），供引擎模块加载器解析 http(s) 导入 */
    private fun loadRemoteContent(url: String): String? {
        val md5 = MD5.string2MD5(url) ?: return null
        val cacheFile = File(context.cacheDir, "$JS_CACHE_DIR/$md5.js")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return runCatching { cacheFile.readText() }.getOrNull()
        }
        return try {
            val request = Request.Builder().url(url).build()
            okHttpHelper.client().newCall(request).execute().use { response ->
                val body = response.body ?: return null
                val text = body.string()
                cacheFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                runCatching { cacheFile.writeText(text) }
                text
            }
        } catch (e: Throwable) {
            LOG.e(TAG, "远程模块加载失败: $url, error=${e.message}")
            null
        }
    }

    /**
     * 确保非远程脚本本地可用：
     * - assets:// 内置资源原样返回；
     * - 其他视为本地路径直接返回。
     */
    private fun ensureLocalScript(api: String, key: String): String {
        return when {
            api.startsWith("assets://") -> api
            else -> {
                if (File(api).exists()) api else ""
            }
        }
    }

    companion object {
        private const val TAG = "JsSpiderFactoryImpl"
        private const val JS_CACHE_DIR = "csp_js"
    }
}
