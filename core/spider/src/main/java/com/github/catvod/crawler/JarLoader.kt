package com.github.catvod.crawler

import android.content.Context
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import com.github.catvod.Init
import com.github.catvod.net.OkHttp
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.MD5
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 爬虫 jar 加载器。
 * 按 key 管理 DexClassLoader、注入 SpiderApi、同步代理端口与弹幕回调方法，
 * 支持 http/file/assets/clan 多协议 jar 加载。
 * 保留 com.github.catvod.crawler 包名以兼容旧 jar 爬虫调用约定。
 */
class JarLoader {

    companion object {
        private const val TAG = "JarLoader"
        private const val MAIN_KEY = "main"
        private const val BUFFER_SIZE = 16384
    }

    private val loaders = ConcurrentHashMap<String, DexClassLoader>()
    private val proxyMethods = ConcurrentHashMap<String, Method>()
    private val danmuClickMethods = ConcurrentHashMap<String, Method>()
    private val danmuLongClickMethods = ConcurrentHashMap<String, Method>()
    private val spiders = ConcurrentHashMap<String, Spider>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val siteJarKeys = ConcurrentHashMap<String, String>()
    private val aliases = ConcurrentHashMap<String, String>()

    @Volatile
    private var recent: String = MAIN_KEY

    /** 主 jar 加载入口，保留原签名以兼容 ApiConfig 调用，禁止在主线程调用 */
    fun load(cache: String): Boolean {
        val success = load(MAIN_KEY, File(cache))
        if (success) recent = MAIN_KEY
        return success
    }

    /** 设置最近使用的 jar key，用于代理与弹幕方法定位 */
    fun setRecentJarKey(key: String?) {
        if (TextUtils.isEmpty(key)) return
        recent = realKey(key!!)
        injectProxyPort(loaders[recent])
    }

    /** 直播 jar 加载，jar 字符串可包含 ;md5; 校验信息 */
    fun loadLiveJar(jar: String) {
        val key = jarKey(jar)
        parseJar(key, jar)
        setRecentJarKey(key)
    }

    fun clear() {
        for (spider in spiders.values) {
            try {
                spider.destroy()
            } catch (_: Throwable) {
            }
        }
        loaders.clear()
        proxyMethods.clear()
        danmuClickMethods.clear()
        danmuLongClickMethods.clear()
        spiders.clear()
        locks.clear()
        siteJarKeys.clear()
        aliases.clear()
        recent = MAIN_KEY
    }

    private fun load(key: String, file: File?): Boolean {
        if (Thread.interrupted()) return false
        if (!exists(file)) return false
        if (loaders.containsKey(key)) return true
        return try {
            val context = Init.context() ?: return false
            makeJarReadOnly(file!!.absolutePath)
            val cachePath = jarDir().absolutePath
            val loader = DexClassLoader(file.absolutePath, cachePath, cachePath, context.classLoader)
            invokeInit(loader)
            invokeProxy(key, loader)
            invokeDanmaku(key, loader)
            injectProxyPort(loader)
            loaders[key] = loader
            true
        } catch (e: Throwable) {
            LOG.e(TAG, "load error key=$key, msg=${e.message}", e)
            false
        }
    }

    /**
     * Android 14+ 动态加载代码要求被加载文件必须为只读，否则系统会抛出异常。
     * 低版本直接返回 true 以兼容旧行为。
     */
    private fun makeJarReadOnly(jarPath: String): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val jarFile = File(jarPath)
        if (!jarFile.exists()) return false
        var result = jarFile.setReadOnly()
        if (!result) {
            // setReadOnly 失败时尝试通过显式关闭写权限达到只读效果
            result = jarFile.setWritable(false, false) && jarFile.setReadable(true, false)
        }
        if (!result) LOG.e(TAG, "设置 jar 只读失败: $jarPath")
        return result
    }

    /** 调用 jar 内 Init.init(Context) */
    private fun invokeInit(loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Init")
            val method = clz.getMethod("init", Context::class.java)
            method.invoke(null, Init.context())
        } catch (e: Throwable) {
            // 个别加固/异常 jar 直接 init 失败仅导致该源不可用，不影响 App
            LOG.e(TAG, "invokeInit error", e)
        }
    }

    /** 反射获取 jar 内 Proxy.proxy(Map) 静态方法，用于代理请求分发 */
    private fun invokeProxy(key: String, loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clz.getMethod("proxy", Map::class.java)
            proxyMethods[key] = method
        } catch (_: Throwable) {
            // 部分自实现 jar 不提供 Proxy 类属正常情况，忽略即可
        }
    }

    /** 反射获取 jar 内 Danmaku 类的 onClick / onLongClick 方法，用于弹幕搜索 UI 联动 */
    private fun invokeDanmaku(key: String, loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Danmaku")
            try {
                danmuClickMethods[key] = clz.getMethod("onClick", String::class.java, String::class.java)
            } catch (_: Throwable) {
            }
            try {
                danmuLongClickMethods[key] = clz.getMethod("onLongClick", String::class.java, String::class.java)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    /** 解析并加载 jar，支持 ;md5; 校验以及 http/file/assets/clan 协议 */
    fun parseJar(key: String, jar: String) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(jar)) return
        if (loaders.containsKey(key)) return
        val lock = lock(key)
        synchronized(lock) {
            if (loaders.containsKey(key)) return
            var source = jar
            var md5 = ""
            val texts = jar.split(";md5;".toRegex())
            if (texts.size > 1) {
                source = texts[0]
                md5 = texts[1].trim()
            }
            aliases[jarKey(source)] = key
            if (md5.startsWith("http")) {
                val value = OkHttp.string(md5)
                md5 = value?.trim() ?: ""
            }
            val file = fileForJar(source)
            when {
                !TextUtils.isEmpty(md5) && exists(file) && MD5.getFileMd5(file).equals(md5, ignoreCase = true) ->
                    load(key, file)
                TextUtils.isEmpty(md5) && exists(file) && !FileUtils.isWeekAgo(file) ->
                    load(key, file)
                source.startsWith("http") ->
                    load(key, download(source, file))
                source.startsWith("assets") ->
                    load(key, copyAsset(source, file))
                source.startsWith("file") ->
                    load(key, local(source))
                source.startsWith("clan://") ->
                    load(key, download(clanToAddress(source), file))
            }
        }
    }

    /** 直接获取 DexClassLoader，供外部使用反射加载其他类 */
    fun dex(jar: String): DexClassLoader? {
        return try {
            val key = jarKey(jar)
            parseJar(key, jar)
            loaders[key]
        } catch (e: Throwable) {
            LOG.e(TAG, "dex error", e)
            null
        }
    }

    fun getSpider(key: String, api: String, ext: String, jar: String): Spider {
        val safeApi = api
        if (TextUtils.isEmpty(safeApi)) return SpiderNull()

        val jaKey = if (TextUtils.isEmpty(jar)) MAIN_KEY else jarKey(jar)
        val spKey = jaKey + key
        recent = jaKey
        siteJarKeys[key] = jaKey
        injectProxyPort(loaders[jaKey])

        spiders[spKey]?.let {
            return it
        }

        return try {
            if (MAIN_KEY != jaKey) parseJar(jaKey, jar)
            val loader = loaders[jaKey] ?: return SpiderNull()
            val context = Init.context() ?: return SpiderNull()
            val spider = loader.loadClass("com.github.catvod.spider." + className(safeApi))
                .getDeclaredConstructor().newInstance() as Spider
            spider.siteKey = key
            spider.initApi(SpiderApi())
            spider.init(context, ext)
            spiders[spKey] = spider
            spider
        } catch (e: Throwable) {
            LOG.e(TAG, "getSpider error key=$spKey, msg=${e.message}", e)
            SpiderNull()
        }
    }

    /** 调用当前 jar 提供的弹幕搜索 UI 方法，longClick 区分长按场景 */
    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        try {
            val methods = if (longClick) danmuLongClickMethods else danmuClickMethods
            var method = methods[recent]
            if (method == null) method = methods[MAIN_KEY]
            if (method == null) return
            method.invoke(null, name, episode)
        } catch (e: Throwable) {
            LOG.e(TAG, "searchDanmuUi error", e)
        }
    }

    /** 当前 jar 是否提供弹幕搜索 UI 方法 */
    fun hasDanmuSearchUi(): Boolean =
        danmuClickMethods.containsKey(recent) || danmuLongClickMethods.containsKey(recent)

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject {
        return try {
            val clz = loadParserClass("com.github.catvod.parser.Json$key")
            val method = clz.getMethod("parse", LinkedHashMap::class.java, String::class.java)
            method.invoke(null, jxs, url) as JSONObject
        } catch (e: Throwable) {
            LOG.e(TAG, "jsonExt error", e)
            JSONObject()
        }
    }

    fun jsonExtMix(
        flag: String,
        key: String,
        name: String,
        jxs: LinkedHashMap<String, HashMap<String, String>>,
        url: String
    ): JSONObject {
        return try {
            val clz = loadParserClass("com.github.catvod.parser.Mix$key")
            val method = clz.getMethod(
                "parse",
                LinkedHashMap::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            method.invoke(null, jxs, name, flag, url) as JSONObject
        } catch (e: Throwable) {
            LOG.e(TAG, "jsonExtMix error", e)
            JSONObject()
        }
    }

    /**
     * 代理调用入口，按 siteKey 关联的 jar → 最近 jar → 其余 jar 顺序回退匹配，
     * 兼容多源聚合场景。
     */
    fun proxyInvoke(params: Map<String, String>?): Array<Any?>? {
        val siteKey = params?.get("siteKey")
        if (!TextUtils.isEmpty(siteKey)) {
            proxyInvoke(proxyMethods[siteJarKeys[siteKey]], params)?.let { return it }
        }
        proxyInvoke(proxyMethods[recent], params)?.let { return it }
        for ((jarKey, method) in proxyMethods) {
            if (jarKey == recent) continue
            proxyInvoke(method, params)?.let { return it }
        }
        return null
    }

    private fun proxyInvoke(method: Method?, params: Map<String, String>?): Array<Any?>? {
        return try {
            method?.invoke(null, params) as? Array<Any?>
        } catch (e: Throwable) {
            LOG.e(TAG, "proxyInvoke error", e)
            null
        }
    }

    @Throws(ClassNotFoundException::class)
    private fun loadParserClass(name: String): Class<*> {
        loaders[recent]?.let { loader ->
            try {
                return loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        loaders[MAIN_KEY]?.let { return it.loadClass(name) }
        throw ClassNotFoundException(name)
    }

    private fun download(url: String, file: File): File {
        try {
            OkHttp.newCall(url).execute().use { response ->
                val body = response.body ?: return file
                body.byteStream().use { input ->
                    FileOutputStream(create(file)).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var length: Int
                        while (input.read(buffer).also { length = it } != -1) {
                            if (Thread.interrupted()) return file
                            output.write(buffer, 0, length)
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: Throwable) {
            LOG.e(TAG, "download error", e)
        }
        return file
    }

    private fun copyAsset(url: String, file: File): File {
        var input: InputStream? = null
        var output: FileOutputStream? = null
        try {
            val path = url.replace("assets://", "").replace("assets/", "")
            input = Init.context()?.assets?.open(path) ?: return file
            output = FileOutputStream(create(file))
            val buffer = ByteArray(BUFFER_SIZE)
            var length: Int
            while (input.read(buffer).also { length = it } != -1) {
                output.write(buffer, 0, length)
            }
            output.flush()
        } catch (e: Throwable) {
            LOG.e(TAG, "copyAsset error", e)
        } finally {
            close(input)
            close(output)
        }
        return file
    }

    private fun local(path: String): File {
        val realPath = path.replace("file:/", "")
        val file = File(Environment.getExternalStorageDirectory(), realPath)
        return if (file.exists()) file else File(realPath)
    }

    private fun fileForJar(jar: String): File = File(jarDir(), jarKey(jar) + ".jar")

    private fun jarDir(): File {
        val dir = File(Init.context()?.cacheDir, "jar")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Throws(Exception::class)
    private fun create(file: File): File {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        if (file.exists()) file.delete()
        file.createNewFile()
        file.setReadable(true)
        file.setWritable(true)
        file.setExecutable(true)
        return file
    }

    private fun exists(file: File?): Boolean = file != null && file.exists() && file.length() > 0

    private fun lock(key: String): Any {
        locks[key]?.let { return it }
        val created = Any()
        val old = locks.putIfAbsent(key, created)
        return old ?: created
    }

    private fun jarKey(jar: String?): String {
        val key = MD5.string2MD5(jar ?: "")
        return if (TextUtils.isEmpty(key)) MAIN_KEY else key!!
    }

    private fun realKey(key: String): String = aliases[key] ?: key

    private fun className(api: String): String =
        if (api.contains("csp_")) api.split("csp_")[1] else api

    /** 将 clan:// 协议地址转换为可访问的 http 地址，支持 clan://localhost 与 clan://host 两种形式 */
    private fun clanToAddress(url: String): String {
        if (url.startsWith("clan://localhost/")) {
            return url.replace("clan://localhost/", SpiderApi().getAddress(true) + "file/")
        }
        if (url.startsWith("clan://")) {
            val text = url.substring(7)
            val index = text.indexOf('/')
            if (index > 0) return "http://" + text.substring(0, index) + "/file/" + text.substring(index + 1)
        }
        return url
    }

    /** 同步本地服务端口到主进程与 jar 内的 com.github.catvod.Proxy 类 */
    private fun injectProxyPort(loader: DexClassLoader?) {
        // 先同步主进程 Proxy 占位类，jar 内代码未自带 Proxy 时会回落到主进程访问
        com.github.catvod.Proxy.set(getServerPort())
        if (loader == null) return
        try {
            val proxy = loader.loadClass("com.github.catvod.Proxy")
            val set = proxy.getMethod("set", Int::class.javaPrimitiveType)
            set.invoke(null, getServerPort())
        } catch (_: Throwable) {
            // jar 内未实现 Proxy.set(int) 时属正常情况，忽略即可
        }
    }

    /** 获取当前本地服务端口，优先使用运行期动态端口（ControlManager 启动时同步） */
    private fun getServerPort(): Int = com.github.catvod.Proxy.getPort()

    private fun close(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
        }
    }
}
