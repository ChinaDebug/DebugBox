package com.github.catvod.crawler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.github.catvod.Init
import com.github.catvod.crawler.python.IPyLoader
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.util.MD5
import com.undcover.freedom.pyramid.PythonLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Python 爬虫加载器，基于 Chaquopy 引擎（engine:pyramid 打包运行时与脚本资源）。
 * 初始化在后台线程完成，完成后通过 EventHub 通知上层重新加载首页。
 */
class pyLoader : IPyLoader {

    companion object {
        const val TYPE_PY_LOADER_READY = "py_loader_ready"
        const val TYPE_PY_LOADER_ERROR = "py_loader_error"
        const val TYPE_STORAGE_PERMISSION_DENIED = "storage_permission_denied"
    }

    private val spiders = ConcurrentHashMap<String, Spider>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isInitialized: Boolean = false

    private var lastConfig: String? = null
    private var recentPyApi: String? = null

    private val pythonLoader = PythonLoader.getInstance()

    init {
        // 延迟初始化到后台线程，避免 ANR
        scope.launch {
            try {
                val context = Init.context() ?: return@launch
                pythonLoader.setApplication(context)
                isInitialized = true
                EventHub.post(TypedEvent(TYPE_PY_LOADER_READY))
            } catch (e: Throwable) {
                EventHub.post(TypedEvent(TYPE_PY_LOADER_ERROR))
            }
        }
    }

    fun isInitialized(): Boolean = isInitialized

    override fun clear() {
        // 先销毁缓存的 Python 爬虫实例，再清空映射与底层 runner 缓存
        for (spider in spiders.values) {
            try {
                spider.destroy()
            } catch (_: Throwable) {
            }
        }
        spiders.clear()
        pythonLoader.clear()
    }

    override fun setConfig(jsonStr: String?) {
        if (!isInitialized) return
        if (jsonStr != null && jsonStr != lastConfig) {
            try {
                pythonLoader.setConfig(jsonStr)
                lastConfig = jsonStr
            } catch (_: Throwable) {
            }
        }
    }

    override fun setRecentPyKey(pyApi: String?) {
        recentPyApi = pyApi
    }

    override fun getSpider(key: String, cls: String, ext: String): Spider {
        spiders[key]?.let { return it }
        if (!isInitialized) return SpiderNull()
        return try {
            if (!hasStoragePermission()) {
                EventHub.post(TypedEvent(TYPE_STORAGE_PERMISSION_DENIED))
                return SpiderNull()
            }
            // PythonLoader.getSpider 返回 Chaquopy 的 PyObject（Runner 包装），
            // 由 PySpider 桥接为 Spider 接口实现
            val runner = pythonLoader.getSpider(key, getPyUrl(cls, ext))
            if (runner is com.chaquo.python.PyObject) {
                val spider = PySpider(runner)
                spider.siteKey = key
                // 创建后初始化：触发 app.py init（依赖 setExtendInfo + spider.init(extend)）
                val context = Init.context() ?: return SpiderNull()
                spider.init(context, ext)
                spiders[key] = spider
                spider
            } else {
                SpiderNull()
            }
        } catch (e: Throwable) {
            SpiderNull()
        }
    }

    override fun proxyInvoke(params: Map<String, String>): Array<Any?>? {
        val api = recentPyApi ?: return null
        return try {
            val spider = getSpider(MD5.string2MD5(api) ?: "", api, "")
            spider.proxyLocal(params)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getPyUrl(api: String, ext: String): String {
        val builder = StringBuilder(api)
        if (ext.isNotEmpty()) {
            builder.append(if (api.contains("?")) "&" else "?").append("extend=").append(ext)
        }
        return builder.toString()
    }

    private fun hasStoragePermission(): Boolean {
        val context = Init.context() ?: return false
        // Android 11+ 且 targetSdkVersion>=30 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            return Environment.isExternalStorageManager()
        }
        // Android 10 及以下使用传统存储权限
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
