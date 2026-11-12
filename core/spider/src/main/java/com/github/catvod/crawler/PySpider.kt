package com.github.catvod.crawler

import android.content.Context
import com.chaquo.python.PyObject
import com.tvbox.kotlin.core.common.LOG
import java.io.ByteArrayInputStream

/**
 * Python 爬虫桥接器，实现 [Spider] 接口并委托到 Chaquopy 加载的 Python Runner。
 *
 * Runner（runner.py）包装了 Python Spider 实例，app.py 中的桥接函数接收 Runner 作为首参，
 * 调用对应 Spider 方法并返回 JSON 字符串。本类在 Kotlin 侧调用这些桥接函数。
 */
class PySpider(
    private val runner: PyObject
) : Spider() {

    companion object {
        private const val TAG = "PySpider"
    }

    @Throws(Exception::class)
    override fun init(context: Context, extend: String) {
        callBridge("init", extend)
    }

    @Throws(Exception::class)
    override fun homeContent(filter: Boolean): String {
        return callBridgeForResult("homeContent", filter) ?: ""
    }

    @Throws(Exception::class)
    override fun homeVideoContent(): String {
        return callBridgeForResult("homeVideoContent") ?: ""
    }

    @Throws(Exception::class)
    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String {
        val extendJson = com.google.gson.Gson().toJson(extend)
        return callBridgeForResult("categoryContent", tid, pg, filter, extendJson) ?: ""
    }

    @Throws(Exception::class)
    override fun detailContent(ids: List<String>): String {
        val idsJson = com.google.gson.Gson().toJson(ids)
        return callBridgeForResult("detailContent", idsJson) ?: ""
    }

    @Throws(Exception::class)
    override fun searchContent(key: String, quick: Boolean): String {
        return callBridgeForResult("searchContent", key, quick) ?: ""
    }

    @Throws(Exception::class)
    override fun searchContent(key: String, quick: Boolean, pg: String): String {
        return callBridgeForResult("searchContent", key, quick, pg) ?: ""
    }

    @Throws(Exception::class)
    override fun playerContent(flag: String, id: String, vipFlags: List<String>): String {
        val vipJson = com.google.gson.Gson().toJson(vipFlags)
        return callBridgeForResult("playerContent", flag, id, vipJson) ?: ""
    }

    @Throws(Exception::class)
    override fun isVideoFormat(url: String): Boolean {
        return callBridgeForResult("isVideoFormat", url) == "true"
    }

    @Throws(Exception::class)
    override fun manualVideoCheck(): Boolean {
        return callBridgeForResult("manualVideoCheck") == "true"
    }

    override fun liveContent(url: String): String {
        return callBridgeForResult("liveContent", url) ?: ""
    }

    @Throws(Exception::class)
    override fun proxyLocal(params: Map<String, String>): Array<Any?>? {
        return try {
            val paramsJson = com.google.gson.Gson().toJson(params)
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("app")
            // Python 侧返回 list，需按元素解析：code/msg/stream/header/isBase64
            val list = module.callAttr("localProxy", runner, paramsJson).asList()
            if (list.isEmpty()) return null
            val base64 = list.size > 4 && list[4].toInt() == 1
            val headerAvailable = list.size > 3 && list[3] != null
            val result = arrayOfNulls<Any?>(4)
            result[0] = list[0].toInt()
            result[1] = list[1].toString()
            result[2] = getStream(list.getOrNull(2), base64)
            result[3] = if (headerAvailable) getHeader(list.getOrNull(3)) else null
            result
        } catch (e: Throwable) {
            LOG.e(TAG, "Python 本地代理调用失败: ${e.message}")
            null
        }
    }

    override fun cancelByTag() {
        // Python 侧无对应接口，空实现
    }

    override fun destroy() {
        // Python 侧无对应接口，空实现
    }

    /**
     * 调用 app.py 中的桥接函数，函数签名为 func(runner, ...args)。
     * 异常时返回 null 并记录日志。
     */
    private fun callBridgeForResult(funcName: String, vararg args: Any?): String? {
        return try {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("app")
            val callArgs = ArrayList<Any?>(args.size + 1)
            callArgs.add(runner)
            args.forEach { callArgs.add(it) }
            module.callAttr(funcName, *callArgs.toTypedArray()).toString()
        } catch (e: Throwable) {
            LOG.e(TAG, "Python 桥接调用失败: $funcName, error=${e.message}")
            null
        }
    }

    /** 调用 app.py 中的桥接函数（无返回值） */
    private fun callBridge(funcName: String, vararg args: Any?) {
        try {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("app")
            val callArgs = ArrayList<Any?>(args.size + 1)
            callArgs.add(runner)
            args.forEach { callArgs.add(it) }
            module.callAttr(funcName, *callArgs.toTypedArray())
        } catch (e: Throwable) {
            LOG.e(TAG, "Python 桥接调用失败: $funcName, error=${e.message}")
        }
    }

    /** 将 Python 侧 stream 元素转为输入流，支持 bytes 类型与 base64 编码 */
    private fun getStream(o: PyObject?, base64: Boolean): ByteArrayInputStream {
        if (o == null) return ByteArrayInputStream(ByteArray(0))
        return try {
            val typeStr = o.type().toString()
            if (typeStr.contains("bytes")) {
                ByteArrayInputStream(o.toJava(ByteArray::class.java))
            } else {
                var content = o.toString()
                if (base64 && content.contains("base64,")) {
                    content = content.split("base64,")[1]
                }
                val bytes = if (base64) {
                    android.util.Base64.decode(content, android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP)
                } else {
                    content.toByteArray(Charsets.UTF_8)
                }
                ByteArrayInputStream(bytes)
            }
        } catch (_: Throwable) {
            ByteArrayInputStream(ByteArray(0))
        }
    }

    /** 将 Python 侧 header 元素（dict）转为 Map */
    private fun getHeader(o: PyObject?): Map<String, String>? {
        if (o == null) return null
        val headerMap = HashMap<String, String>()
        try {
            val map = o.asMap()
            for (k in map.keys) {
                headerMap[k.toString()] = map[k]?.toString() ?: ""
            }
        } catch (_: Throwable) {
        }
        return headerMap
    }
}
