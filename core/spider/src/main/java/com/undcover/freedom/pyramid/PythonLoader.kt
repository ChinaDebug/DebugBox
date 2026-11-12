package com.undcover.freedom.pyramid

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.tvbox.kotlin.core.common.LOG
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Python 爬虫加载器，基于 Chaquopy 引擎执行 Python 爬虫脚本。
 *
 * 由 core:spider 的 pyLoader 直接调用。Chaquopy 的 Python 运行时与
 * Python 脚本资源由 engine:pyramid 模块打包进应用。
 *
 * 工作流程：
 * 1. [setApplication] 注入 Application 上下文，初始化 Chaquopy AndroidPlatform；
 * 2. [setConfig] 写入站点配置 JSON 并解析站点映射，供依赖插件解析下载地址；
 * 3. [getSpider] 下载/加载 Python 爬虫脚本（含依赖插件），返回 Python Runner 包装（PyObject），
 *    由 pyLoader 包装为 [com.github.catvod.crawler.Spider] 实现使用。
 *
 * Python 脚本位于 engine:pyramid 的 src/python/，入口 app.py 提供 downloadPlugin /
 * loadFromDisk / getDependence / init / homeContent / detailContent / searchContent /
 * playerContent / localProxy 等桥接函数。
 */
class PythonLoader private constructor() {

    companion object {
        private const val TAG = "PythonLoader"

        @Volatile
        private var instance: PythonLoader? = null

        @JvmStatic
        fun getInstance(): PythonLoader {
            return instance ?: synchronized(this) {
                instance ?: PythonLoader().also { instance = it }
            }
        }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized: Boolean = false

    /** 已加载的 Spider 缓存，key 为站点标识 */
    private val spiders = ConcurrentHashMap<String, Any>()

    /** 当前配置 JSON */
    @Volatile
    private var configJson: String? = null

    /** 站点配置映射：api -> 站点 JSONObject，供依赖插件解析下载地址 */
    private val siteMap = ConcurrentHashMap<String, JSONObject>()

    /**
     * 注入 Application 上下文并初始化 Chaquopy Python 运行时。
     * 多次调用幂等。
     */
    fun setApplication(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            initialized = true
        } catch (e: Throwable) {
            LOG.e(TAG, "Chaquopy 初始化失败: ${e.message}")
        }
    }

    /**
     * 写入站点配置 JSON，并解析站点映射用于依赖插件下载。
     * 仅在配置变化时更新。
     */
    fun setConfig(jsonStr: String?) {
        if (jsonStr == null || jsonStr == configJson) return
        configJson = jsonStr
        try {
            val configJo = JSONObject(jsonStr)
            val siteList = configJo.optJSONArray("sites")
            if (siteList != null) {
                siteMap.clear()
                for (i in 0 until siteList.length()) {
                    val jo = siteList.getJSONObject(i)
                    val api = jo.optString("api")
                    if (api.isNotEmpty()) siteMap[api] = jo
                }
            }
        } catch (_: Throwable) {
        }
        // 配置更新后清空缓存，使下次 getSpider 重新加载
        spiders.clear()
    }

    /** 按依赖 api 查找站点配置中的依赖插件下载地址，已加载的返回空串 */
    fun getUrlByApi(api: String): String {
        val jo = siteMap[api] ?: return ""
        val key = jo.optString("key")
        val url = jo.optString("ext")
        return if (key.isNotEmpty() && url.isNotEmpty() && !spiders.containsKey(key)) url else ""
    }

    /**
     * 加载 Python 爬虫脚本并返回 Python Runner 包装对象。
     *
     * @param key 站点 key，用于缓存
     * @param api Python 脚本 URL（http(s):// 或 file://），可含 extend 参数
     * @return Python Runner 对象（com.chaquo.python.PyObject），由 pyLoader 包装为 Spider
     */
    fun getSpider(key: String, api: String): Any? {
        if (!initialized) {
            LOG.w(TAG, "PythonLoader 未初始化，跳过 getSpider")
            return null
        }
        if (!hasStoragePermission()) {
            LOG.w(TAG, "存储权限未授予，跳过 Python 爬虫加载")
            return null
        }
        // 命中缓存
        spiders[key]?.let { return it }

        return try {
            val context = appContext ?: return null
            val basePath = context.filesDir.absolutePath + "/py_plugins"
            val py = Python.getInstance()
            val module = py.getModule("app")
            // 下载/加载主插件，返回本地路径
            val spiderPath = module.callAttr("downloadPlugin", basePath, api).toString()
            val spiderObj = module.callAttr("loadFromDisk", spiderPath)
            // 下载依赖插件：依赖 api 从站点配置映射解析下载地址，下载结果写入 SpiderPath 供 init 使用
            try {
                val deps = module.callAttr("getDependence", spiderObj).asList()
                for (dep in deps) {
                    val depUrl = getUrlByApi(dep.toString())
                    if (depUrl.isNotEmpty()) {
                        module.callAttr("downloadPlugin", basePath, depUrl)
                    }
                }
            } catch (_: Throwable) {
            }
            // 创建 Runner 包装
            val runner = py.getModule("runner").callAttr("Runner", spiderObj)
            // 缓存并返回
            spiders[key] = runner
            runner
        } catch (e: Throwable) {
            LOG.e(TAG, "加载 Python 爬虫失败: key=$key, api=$api, error=${e.message}")
            null
        }
    }

    /** 检查存储权限 */
    private fun hasStoragePermission(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            return Environment.isExternalStorageManager()
        }
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 清空所有缓存的 Spider 实例 */
    fun clear() {
        spiders.clear()
    }
}
