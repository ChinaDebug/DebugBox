package com.tvbox.kotlin.engine.quickjs

import android.content.Context
import android.util.Base64
import com.tvbox.kotlin.core.common.LOG
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.UriUtil
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * QuickJS 引擎封装，提供 JS 爬虫脚本的运行时管理。
 *
 * 职责：
 * 1. 创建/销毁 [QuickJSContext]，每个爬虫独占单线程执行器保证线程安全；
 * 2. 加载 JS 爬虫脚本（本地/远程/字节码），支持 export default、__jsEvalReturn、//bb 三种形态；
 * 3. 注册 Kotlin ↔ JS 桥接对象（由上层 [bridge] 提供 req/http/pdfh 等能力）；
 * 4. 内置模块加载器，解析 assets/js/lib 内置库与相对/远程模块导入；
 * 5. 调用 JS Spider 实例方法，兼容返回 Promise 的异步写法；
 * 6. 按 key 缓存已加载的 Spider 实例。
 *
 * 桥接对象（网络请求、DOM 解析等）由上层（:app）注入，本模块不依赖具体业务实现。
 */
class QuickJsEngine {

    companion object {
        private const val TAG = "QuickJsEngine"
        private const val LIB_DIR = "js/lib"
        private const val CALL_TIMEOUT_SECONDS = 60L

        /** 进程内唯一实例（Hilt @Singleton 创建），供清理入口跨模块清空引擎缓存 */
        @Volatile
        private var globalInstance: QuickJsEngine? = null

        /**
         * 清空引擎内缓存（Spider 句柄 + 脚本字节码），不销毁 QuickJS 运行时。
         * 由 ApiConfig.clearAllCache 等清理入口调用。
         */
        fun clearGlobalCache() {
            globalInstance?.clearCache()
        }

        /** 模块包装代码：从导入模块中提取 __JS_SPIDER__ */
        private const val SPIDER_WRAPPER = "import * as spider from '%s'\n\n" +
            "if (!globalThis.__JS_SPIDER__) {\n" +
            "    if (spider.__jsEvalReturn) {\n" +
            "        globalThis.req = http\n" +
            "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
            "        globalThis.__JS_SPIDER__.is_cat = true\n" +
            "    } else if (spider.default) {\n" +
            "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
            "    }\n" +
            "}"
    }

    @Volatile
    private var appContext: Context? = null

    /** 已加载的 Spider 句柄缓存，key 为站点 key */
    private val handles = ConcurrentHashMap<String, SpiderHandle>()

    /** 主脚本字节码缓存，供模块加载器按 key 名解析 //bb 主脚本 */
    private val bytecodeModules = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var initialized = false

    /** 远程模块加载源（http/https），由上层注入 OkHttp 实现 */
    @Volatile
    var moduleSource: ((String) -> String?)? = null

    /** JS 脚本 local 存储，由上层注入持久化实现，未注入时回落内存 Map */
    @Volatile
    var localStore: JsLocalStore? = null

    private val jsThreadFactory = ThreadFactory { r ->
        Thread(r, "quickjs-spider").apply { isDaemon = true }
    }

    /**
     * 注入 Application 上下文并初始化 QuickJS 运行时。
     * 多次调用幂等。
     */
    fun init(context: Context) {
        if (initialized) return
        globalInstance = this
        appContext = context.applicationContext
        // 初始化 QuickJS 原生库，必须在 QuickJSContext.create() 之前调用，仅需一次
        runCatching { com.whl.quickjs.android.QuickJSLoader.init() }
            .onFailure { LOG.e(TAG, "QuickJSLoader.init 失败: ${it.message}") }
        initialized = true
    }

    /**
     * 加载 JS 爬虫脚本并返回 Spider 句柄。
     *
     * @param key 站点 key，用于缓存
     * @param api JS 脚本 URL（http(s)://）或本地路径
     * @param ext 扩展参数，传入 JS init(extend)
     * @param classLoader 自定义 jsapi jar 的 ClassLoader，可能为空
     * @param bridge 桥接对象，其 @Function 注解方法注册为全局函数（req/http/pdfh 等）
     * @return Spider 句柄，加载失败返回 null
     */
    fun loadSpider(
        key: String,
        api: String,
        ext: String,
        classLoader: Class<*>?,
        bridge: Any? = null
    ): SpiderHandle? {
        handles[key]?.let { return it }
        val context = appContext ?: run {
            LOG.w(TAG, "QuickJsEngine 未初始化，跳过 loadSpider")
            return null
        }
        val executor = Executors.newSingleThreadExecutor(jsThreadFactory)
        return try {
            executor.submit<SpiderHandle?> {
                doLoad(context, key, api, ext, classLoader, bridge, executor)
            }.get()
        } catch (e: Throwable) {
            LOG.e(TAG, "JS 爬虫加载失败: key=$key, error=${e.message}")
            executor.shutdownNow()
            null
        }
    }

    /** 调用 Spider 方法并返回字符串结果，失败返回空串 */
    fun callMethod(handle: SpiderHandle, methodName: String, vararg args: Any?): String {
        return awaitCall(handle, methodName, args)?.toString() ?: ""
    }

    /** 调用 Spider 方法并返回 Boolean 结果 */
    fun callMethodBoolean(handle: SpiderHandle, methodName: String, vararg args: Any?): Boolean {
        return "true".equals(awaitCall(handle, methodName, args)?.toString(), ignoreCase = true)
    }

    /** 调用 Spider 方法返回原始 JS 结果（可能为 JSObject/JSArray），失败返回 null */
    fun callRaw(handle: SpiderHandle, methodName: String, vararg args: Any?): Any? {
        return awaitCall(handle, methodName, args)
    }

    /** 调用 Spider 的 init(extend)，由上层在拿到扩展参数后触发 */
    fun initSpider(handle: SpiderHandle, ext: String) {
        if (handle.executor.isShutdown) return
        handle.executor.submit {
            try {
                val fn = handle.spiderObj.getJSFunction("init") ?: return@submit
                val arg = buildInitArg(handle.context, handle.key, ext, handle.isCat)
                fn.call(arg)
            } catch (e: Throwable) {
                LOG.e(TAG, "JS init 调用失败: key=${handle.key}, error=${e.message}")
            }
        }
    }

    /** 销毁指定 Spider 实例，释放 QuickJS 上下文与执行器 */
    fun destroy(key: String) {
        handles.remove(key)?.let { handle ->
            try {
                handle.context.destroy()
            } catch (_: Throwable) {
            }
            handle.executor.shutdownNow()
        }
    }

    /** 销毁所有 Spider 实例 */
    fun destroyAll() {
        for (key in handles.keys.toList()) {
            destroy(key)
        }
        bytecodeModules.clear()
    }

    /** 清空缓存但不销毁上下文（用于配置变更） */
    fun clearCache() {
        handles.clear()
        bytecodeModules.clear()
    }

    // ------------------------------------------------------------------
    // 加载与调用
    // ------------------------------------------------------------------

    private fun doLoad(
        context: Context,
        key: String,
        api: String,
        ext: String,
        classLoader: Class<*>?,
        bridge: Any?,
        executor: ExecutorService
    ): SpiderHandle? {
        val raw = loadScriptContent(api) ?: run {
            LOG.e(TAG, "JS 脚本加载失败: $api")
            return null
        }
        val ctx = try {
            QuickJSContext.create()
        } catch (e: Throwable) {
            return null
        }
        try {
            configContext(ctx, context)
            bindBridge(ctx, bridge, executor)
            bindLocal(ctx)
            evaluateLib(ctx, "net.js")
            if (classLoader != null) bindJsApi(ctx, classLoader)
            val isCat = evaluateSpider(ctx, key, api, raw)
            val global = ctx.getGlobalObject()
            val spiderObj = global.getJSObject(jsKey(key))
                ?: global.getJSObject("SPIDER")
                ?: global.getJSObject("Spider")
                ?: run {
                    LOG.e(TAG, "JS 脚本未定义全局 Spider 对象: key=$key")
                    return null
                }
            val handle = SpiderHandle(key, ctx, spiderObj, executor, isCat)
            handles[key] = handle
            return handle
        } catch (e: Throwable) {
            LOG.e(TAG, "JS 爬虫加载失败: key=$key, error=${e.message}")
            runCatching { ctx.destroy() }
            executor.shutdownNow()
            return null
        }
    }

    private fun awaitCall(handle: SpiderHandle, methodName: String, args: Array<out Any?>): Any? {
        if (handle.executor.isShutdown) return null
        val future = CompletableFuture<Any?>()
        handle.executor.submit {
            try {
                val fn = handle.spiderObj.getJSFunction(methodName)
                if (fn == null) {
                    future.complete(null)
                    return@submit
                }
                val converted = Array(args.size) { jsUtils.toJSValue(handle.context, args[it]) }
                val result = fn.call(*converted)
                val then = (result as? JSObject)?.getJSFunction("then")
                if (then != null) {
                    // 返回 Promise：注册回调，由执行器线程在微任务执行时完成
                    then.call(JSCallFunction { resolved ->
                        future.complete(resolved?.firstOrNull())
                        null
                    })
                } else {
                    future.complete(result)
                }
            } catch (e: Throwable) {
                LOG.e(TAG, "JS 方法调用失败: $methodName, error=${e.message}")
                future.complete(null)
            }
        }
        return try {
            future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 上下文配置
    // ------------------------------------------------------------------

    private fun configContext(ctx: QuickJSContext, context: Context) {
        ctx.setModuleLoader(object : QuickJSContext.BytecodeModuleLoader() {
            override fun getModuleBytecode(moduleName: String): ByteArray {
                // 主脚本字节码优先按 key 命中（//bb 形态）
                bytecodeModules[moduleName]?.let { return it }
                val code = loadModuleCode(moduleName)
                if (code.isEmpty()) return ctx.compileModule("", moduleName)
                return when {
                    code.startsWith("//DRPY") ->
                        Base64.decode(code.removePrefix("//DRPY"), Base64.URL_SAFE)
                    code.startsWith("//bb") ->
                        byteFF(Base64.decode(code.removePrefix("//bb"), Base64.DEFAULT))
                    else -> ctx.compileModule(code, moduleName)
                }
            }

            override fun moduleNormalizeName(moduleBaseName: String, moduleName: String): String {
                return UriUtil.resolve(moduleBaseName, moduleName)
            }
        })
        ctx.setConsole(object : QuickJSContext.Console {
            override fun log(s: String) {
            }

            override fun info(s: String) {
            }

            override fun warn(s: String) {
            }

            override fun error(s: String) {
                LOG.e(TAG, s)
            }
        })
    }

    /** 将桥接对象 @Function/@ContextSetter 注解方法注册为全局 JS 函数 */
    private fun bindBridge(ctx: QuickJSContext, bridge: Any?, executor: ExecutorService) {
        if (bridge == null) return
        bind(ctx.getGlobalObject(), bridge, ctx)
        if (bridge is JsBridgeHost) {
            bridge.attachExecutor(executor)
        }
    }

    /** 注册全局 local 对象，读写脚本运行时状态 */
    private fun bindLocal(ctx: QuickJSContext) {
        val local = ctx.createNewJSObject()
        ctx.setProperty(ctx.getGlobalObject(), "local", local)
        val store = object {
            @Function
            fun get(k: String, def: String): String = localStore?.get("jsRuntime_${k}_$def", "") ?: ""
            @Function
            fun set(k: String, v: String, value: String) {
                localStore?.set("jsRuntime_${k}_$v", value)
            }
            @Function
            fun delete(k: String, v: String) {
                localStore?.delete("jsRuntime_${k}_$v")
            }
        }
        bind(local, store, ctx)
    }

    /** 在上下文中求值 assets/js/lib 内置模块（net.js 提供 req/http） */
    private fun evaluateLib(ctx: QuickJSContext, name: String) {
        val context = appContext ?: return
        val code = runCatching {
            context.assets.open("$LIB_DIR/$name").readBytes().toString(Charsets.UTF_8)
        }.getOrNull() ?: return
        ctx.evaluate(code, name)
    }

    /** 绑定自定义 jsapi jar 内的 @Function 方法为全局 jsapi 对象 */
    private fun bindJsApi(ctx: QuickJSContext, clz: Class<*>) {
        val apiObj = ctx.createNewJSObject()
        ctx.setProperty(ctx.getGlobalObject(), "jsapi", apiObj)
        try {
            val classes = clz.declaredClasses
            if (classes.isEmpty()) {
                val javaObj = clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx)
                bind(apiObj, javaObj, ctx)
            } else {
                val outer = clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx)
                for (subClz in classes) {
                    val javaObj = try {
                        subClz.getDeclaredConstructor(clz).newInstance(outer)
                    } catch (_: Throwable) {
                        continue
                    }
                    val subObj = ctx.createNewJSObject()
                    bind(subObj, javaObj, ctx)
                    ctx.setProperty(apiObj, subClz.simpleName, subObj)
                }
            }
        } catch (e: Throwable) {
            LOG.e(TAG, "jsapi 绑定失败: ${e.message}")
        }
    }

    /** 反射注册 @Function 注解方法为 JS 函数，@ContextSetter 方法注入运行时 */
    private fun bind(target: JSObject, receiver: Any, ctx: QuickJSContext) {
        for (method in receiver.javaClass.methods) {
            if (method.isAnnotationPresent(ContextSetter::class.java)) {
                try {
                    method.invoke(receiver, ctx)
                } catch (_: Throwable) {
                }
            }
        }
        for (method in receiver.javaClass.methods) {
            val ann = method.getAnnotation(Function::class.java) ?: continue
            val name = ann.name.ifEmpty { method.name }
            ctx.setProperty(target, name, JSCallFunction { args ->
                try {
                    method.invoke(receiver, *args)
                } catch (e: Throwable) {
                    LOG.w(TAG, "桥接方法调用失败: $name, error=${e.message}")
                    null
                }
            })
        }
    }

    // ------------------------------------------------------------------
    // 脚本求值
    // ------------------------------------------------------------------

    /** 求值爬虫脚本，返回是否为 cat 型（影响 init 参数形态） */
    private fun evaluateSpider(ctx: QuickJSContext, key: String, api: String, raw: String): Boolean {
        if (raw.startsWith("//bb")) {
            val bytes = Base64.decode(raw.removePrefix("//bb"), Base64.DEFAULT)
            val fixed = byteFF(bytes)
            bytecodeModules["$key.js"] = fixed
            ctx.execute(fixed)
            ctx.evaluateModule(
                SPIDER_WRAPPER.format("$key.js") + "globalThis.${jsKey(key)} = __JS_SPIDER__;",
                "tv_box_root.js"
            )
            return true
        }
        var code = raw
        if (code.contains("__JS_SPIDER__")) {
            code = code.replace(Regex("__JS_SPIDER__\\s*="), "export default ")
        }
        val isCat = code.contains("__jsEvalReturn") && !code.contains("export default")
        ctx.evaluateModule(code, api)
        ctx.evaluateModule(
            SPIDER_WRAPPER.format(api) + "globalThis.${jsKey(key)} = __JS_SPIDER__;",
            "tv_box_root.js"
        )
        return isCat
    }

    /** 构造 init(extend) 参数：cat 型脚本传 {stype,skey,ext} 配置对象，其余传 ext 原文或解析后的 JSON */
    private fun buildInitArg(ctx: QuickJSContext, key: String, ext: String, isCat: Boolean): Any? {
        return if (isCat) {
            val cfg = ctx.createNewJSObject()
            ctx.setProperty(cfg, "stype", 3)
            ctx.setProperty(cfg, "skey", jsKey(key))
            if (isJson(ext)) ctx.setProperty(cfg, "ext", ctx.parse(ext)) else ctx.setProperty(cfg, "ext", ext)
            cfg
        } else {
            if (isJson(ext)) ctx.parse(ext) else ext
        }
    }

    // ------------------------------------------------------------------
    // 模块加载
    // ------------------------------------------------------------------

    /** 加载 JS 脚本内容：远程 URL 走 moduleSource，本地路径直接读取，assets 走 assetManager */
    private fun loadScriptContent(api: String): String? {
        val context = appContext ?: return null
        return when {
            api.startsWith("assets://") -> {
                runCatching {
                    context.assets.open(api.removePrefix("assets://")).readBytes().toString(Charsets.UTF_8)
                }.getOrNull()
            }
            api.startsWith("http://") || api.startsWith("https://") -> {
                moduleSource?.invoke(api)
            }
            api.startsWith("file://") -> {
                runCatching { File(api.removePrefix("file://")).readText(Charsets.UTF_8) }.getOrNull()
            }
            else -> {
                runCatching { File(api).readText(Charsets.UTF_8) }.getOrNull()
            }
        }
    }

    /** 模块代码加载：assets/js/lib、assets://、本地文件、远程 URL */
    private fun loadModuleCode(name: String): String {
        val context = appContext ?: return ""
        return when {
            name.startsWith("assets://") -> {
                runCatching {
                    context.assets.open(name.removePrefix("assets://")).readBytes().toString(Charsets.UTF_8)
                }.getOrNull() ?: ""
            }
            name.startsWith("file://") -> {
                runCatching { File(name.removePrefix("file://")).readText(Charsets.UTF_8) }.getOrNull() ?: ""
            }
            name.startsWith("http://") || name.startsWith("https://") -> {
                moduleSource?.invoke(name) ?: ""
            }
            File(name).exists() -> {
                runCatching { File(name).readText(Charsets.UTF_8) }.getOrNull() ?: ""
            }
            else -> {
                val libName = when {
                    name.contains("gbk.js") -> "gbk.js"
                    name.contains("模板.js") -> "模板.js"
                    name.contains("cat.js") -> "cat.js"
                    else -> name
                }
                runCatching {
                    context.assets.open("$LIB_DIR/$libName").readBytes().toString(Charsets.UTF_8)
                }.getOrNull() ?: ""
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun isJson(text: String): Boolean {
        return try {
            org.json.JSONObject(text)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 站点 key 转安全 JS 全局标识符*/
    private fun jsKey(key: String): String = "J" + key.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** 修复 QuickJS 字节码头部魔数 */
    private fun byteFF(bytes: ByteArray): ByteArray {
        if (bytes.size <= 4) return bytes
        val newBt = ByteArray(bytes.size - 4)
        newBt[0] = 1
        System.arraycopy(bytes, 5, newBt, 1, bytes.size - 5)
        return newBt
    }

    /**
     * Spider 句柄，封装 QuickJS 上下文、JS Spider 对象与单线程执行器。
     * 由 [QuickJsEngine.loadSpider] 创建，外部不可直接构造。
     */
    class SpiderHandle(
        val key: String,
        val context: QuickJSContext,
        val spiderObj: JSObject,
        val executor: ExecutorService,
        val isCat: Boolean
    )

    private val jsUtils = JSUtils<Any?>()
}

/** JS 桥接对象宿主接口：允许引擎将爬虫执行器注入桥接对象，供异步回调调度 */
interface JsBridgeHost {
    fun attachExecutor(executor: ExecutorService)
}

/** JS local 存储接口，由上层注入持久化实现 */
interface JsLocalStore {
    fun get(key: String, default: String): String
    fun set(key: String, value: String)
    fun delete(key: String)
}
