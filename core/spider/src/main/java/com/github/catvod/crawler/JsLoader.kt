package com.github.catvod.crawler

import com.github.catvod.Init
import com.github.catvod.net.OkHttp
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.MD5
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * JS 爬虫加载器，负责加载 JS 自定义 jsapi jar 并通过 QuickJS 引擎执行 JS 爬虫。
 * jar 的下载、缓存、DexClassLoader 加载与 Spider 实例缓存由本类承担；
 * JS 爬虫实际执行委托给 [jsSpiderFactory]（由上层注入 QuickJS 实现），
 * 工厂未注入时回落到 SpiderNull，保证加载器可独立编译运行。
 * 保留 com.github.catvod.crawler 包名以兼容旧调用约定。
 */
class JsLoader {

    companion object {
        private const val TAG = "JsLoader"
        private const val BUFFER_SIZE = 2048
        private const val JSAPI_CLASS = "com.github.catvod.js.Method"
        private const val LOAD_RETRY = 5
    }

    private val spiders = ConcurrentHashMap<String, Spider>()
    private val classes = ConcurrentHashMap<String, Class<*>>()

    @Volatile
    private var recentKey: String = ""

    /** JS 爬虫工厂，由上层注入 QuickJS 实现以解耦 JsSpider 依赖树 */
    @Volatile
    var jsSpiderFactory: JsSpiderFactory? = null

    /** 销毁所有 JS 爬虫实例，取消其网络请求并释放资源 */
    fun destroy() {
        for (spider in spiders.values) {
            try {
                spider.cancelByTag()
                spider.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    fun clear() {
        // 先销毁所有 JS 爬虫实例（取消网络请求并释放 QuickJS context），再清空缓存
        destroy()
        spiders.clear()
        classes.clear()
    }

    /** 取消所有 JS 爬虫的网络请求，不释放实例 */
    fun stopAll() {
        for (spider in spiders.values) {
            try {
                spider.cancelByTag()
            } catch (_: Throwable) {
            }
        }
    }

    /** 加载自定义 jsapi jar 内的 com.github.catvod.js.Method 类，重试 5 次以应对时序问题 */
    private fun loadClassLoader(jar: String, key: String): Boolean {
        var success = false
        var classInit: Class<*>? = null
        try {
            val context = Init.context() ?: return false
            val cacheDir = File(context.cacheDir.absolutePath + "/catvod_jsapi")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val classLoader = DexClassLoader(jar, cacheDir.absolutePath, null, context.classLoader)
            var count = 0
            do {
                try {
                    classInit = classLoader.loadClass(JSAPI_CLASS)
                    if (classInit != null) {
                        success = true
                        break
                    }
                    Thread.sleep(200)
                } catch (_: Throwable) {
                }
                count++
            } while (count < LOAD_RETRY)
            if (success && classInit != null) {
                classes[key] = classInit
            }
        } catch (e: Throwable) {
            LOG.e(e)
        }
        return success
    }

    /** 加载 jsapi jar，优先命中缓存（按 md5 或一周有效期校验），否则下载后加载 */
    private fun loadJarInternal(jar: String, md5: String, key: String): Class<*>? {
        if (classes.containsKey(key)) {
            return classes[key]
        }
        val context = Init.context() ?: return null
        val cache = File(context.filesDir.absolutePath + "/csp/$key.jar")
        if (md5.isNotEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equals(md5, ignoreCase = true)) {
                loadClassLoader(cache.absolutePath, key)
                return classes[key]
            }
        } else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if (loadClassLoader(cache.absolutePath, key)) {
                    return classes[key]
                }
            }
        }
        try {
            OkHttp.newCall(jar).execute().use { response ->
                val body = response.body ?: return classes[key]
                cache.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(cache).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                    }
                }
            }
            loadClassLoader(cache.absolutePath, key)
            return classes[key]
        } catch (e: Throwable) {
            LOG.e(e)
        }
        return null
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        spiders[key]?.let {
            return it
        }
        if (api.isEmpty()) return SpiderNull()

        var classLoader: Class<*>? = null
        if (jar.isNotEmpty()) {
            val urls = jar.split(";md5;")
            val jarUrl = urls[0]
            val jarKey = MD5.string2MD5(jarUrl) ?: ""
            val jarMd5 = if (urls.size > 1) urls[1].trim() else ""
            classLoader = loadJarInternal(jarUrl, jarMd5, jarKey)
        }
        recentKey = key
        return try {
            val factory = jsSpiderFactory ?: return SpiderNull()
            val context = Init.context() ?: return SpiderNull()
            val spider = factory.create(key, api, classLoader)
            spider.init(context, ext)
            spiders[key] = spider
            spider
        } catch (e: Throwable) {
            LOG.e(TAG, "getSpider error: ${e.message}")
            SpiderNull()
        }
    }

    fun proxyInvoke(params: Map<String, String>): Array<Any?>? {
        return try {
            spiders[recentKey]?.proxyLocal(params)
        } catch (_: Throwable) {
            null
        }
    }
}
