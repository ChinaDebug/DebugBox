package com.tvbox.kotlin.app.js

import android.content.Context
import android.util.Base64
import com.github.catvod.crawler.Spider
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.engine.quickjs.QuickJsEngine
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.HashMap

/**
 * JS 爬虫桥接器，实现 [Spider] 接口并委托到 QuickJS 加载的 JS Spider 实例。
 *
 * JS 脚本需导出默认对象（或 __jsEvalReturn），提供：
 * - init(extend)
 * - home(filter)
 * - homeVod()
 * - category(tid, pg, filter, extend)
 * - detail(id)
 * - search(key, quick[, pg])
 * - play(flag, id, vipFlags)
 * - sniffer()
 * - isVideo(url)
 * - proxy(params)
 *
 * 调用通过 [QuickJsEngine] 转发，参数按类型自动转换为 JS 值（Map→对象、List→数组）。
 */
class JsSpider(
    private val engine: QuickJsEngine,
    private val handle: QuickJsEngine.SpiderHandle
) : Spider() {

    companion object {
        private const val TAG = "JsSpider"
    }

    init {
        siteKey = handle.key
    }

    @Throws(Exception::class)
    override fun init(context: Context, extend: String) {
        engine.initSpider(handle, extend)
    }

    @Throws(Exception::class)
    override fun homeContent(filter: Boolean): String =
        engine.callMethod(handle, "home", filter)

    @Throws(Exception::class)
    override fun homeVideoContent(): String =
        engine.callMethod(handle, "homeVod")

    @Throws(Exception::class)
    override fun categoryContent(
        tid: String,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>
    ): String = engine.callMethod(handle, "category", tid, pg, filter, extend)

    @Throws(Exception::class)
    override fun detailContent(ids: List<String>): String =
        engine.callMethod(handle, "detail", ids.firstOrNull() ?: "")

    @Throws(Exception::class)
    override fun searchContent(key: String, quick: Boolean): String =
        engine.callMethod(handle, "search", key, quick)

    @Throws(Exception::class)
    override fun searchContent(key: String, quick: Boolean, pg: String): String =
        engine.callMethod(handle, "search", key, quick, pg)

    @Throws(Exception::class)
    override fun playerContent(flag: String, id: String, vipFlags: List<String>): String =
        engine.callMethod(handle, "play", flag, id, vipFlags)

    @Throws(Exception::class)
    override fun action(action: String): String? =
        // JS 脚本未定义 action 方法时返回 null，与 Spider 基类默认行为一致（非 action 型爬虫不弹窗）
        engine.callRaw(handle, "action", action)?.toString()

    @Throws(Exception::class)
    override fun manualVideoCheck(): Boolean =
        engine.callMethodBoolean(handle, "sniffer")

    @Throws(Exception::class)
    override fun isVideoFormat(url: String): Boolean =
        engine.callMethodBoolean(handle, "isVideo", url)

    @Throws(Exception::class)
    override fun proxyLocal(params: Map<String, String>): Array<Any?>? {
        return try {
            if ("catvod" == params["from"]) proxy2(params) else proxy1(params)
        } catch (e: Throwable) {
            LOG.e(TAG, "JS proxy 调用失败: ${e.message}")
            emptyArray()
        }
    }

    override fun cancelByTag() {
        // JS 侧无对应接口，空实现
    }

    override fun destroy() {
        try {
            engine.destroy(handle.key)
        } catch (e: Throwable) {
            LOG.e(TAG, "JsSpider destroy 失败: ${e.message}")
        }
    }

    /** 普通代理：proxy(params) 返回 [code, msg, stream, header?, isBase64?] */
    private fun proxy1(params: Map<String, String>): Array<Any?> {
        val raw = engine.callRaw(handle, "proxy", params) ?: return emptyArray()
        val array = (raw as? JSArray)?.let { JSUtils.toJsonArray(it) } ?: return emptyArray()
        val headerAvailable = array.length() > 3 && array.opt(3) != null
        val result = arrayOfNulls<Any?>(4)
        result[0] = array.opt(0)
        result[1] = array.opt(1)
        result[2] = getStream(array.opt(2))
        result[3] = if (headerAvailable) getHeader(array.opt(3)) else null
        if (array.length() > 4 && array.optInt(4) == 1) {
            var content = array.optString(2)
            if (content.contains("base64,")) content = content.substring(content.indexOf("base64,") + 7)
            result[2] = ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT))
        }
        return result
    }

    /** catvod 型代理：proxy(url 段数组, header 对象) 返回 {code,buffer,content,headers} */
    private fun proxy2(params: Map<String, String>): Array<Any?> {
        val url = params["url"] ?: return emptyArray()
        val header = params["header"] ?: ""
        val headerObj = runCatching {
            JSONObject(header).let { obj ->
                HashMap<String, String>().apply { obj.keys().forEach { k -> put(k, obj.optString(k)) } }
            }
        }.getOrNull() ?: ""
        val raw = engine.callRaw(handle, "proxy", url.split("/"), headerObj)
        val json = raw?.toString() ?: return emptyArray()
        val res = JSONObject(json)
        val headersObj = res.optJSONObject("headers") ?: JSONObject()
        val contentType = headersObj.optString("Content-Type")
            .ifEmpty { headersObj.optString("content-type") }
            .ifEmpty { "application/octet-stream" }
        val stream = if (res.optInt("buffer") == 2) {
            ByteArrayInputStream(Base64.decode(res.optString("content"), Base64.DEFAULT))
        } else {
            ByteArrayInputStream(res.optString("content").toByteArray(Charsets.UTF_8))
        }
        return arrayOf(200, contentType, stream)
    }

    private fun getStream(value: Any?): InputStream {
        return if (value is JSONArray) {
            val bytes = ByteArray(value.length())
            for (i in bytes.indices) bytes[i] = value.optInt(i).toByte()
            ByteArrayInputStream(bytes)
        } else {
            ByteArrayInputStream((value?.toString() ?: "").toByteArray(Charsets.UTF_8))
        }
    }

    private fun getHeader(value: Any?): Map<String, String> {
        val headers = HashMap<String, String>()
        try {
            when (value) {
                is JSONObject -> value.keys().forEach { headers[it] = value.optString(it) }
                is String -> JSONObject(value).keys().forEach { headers[it] = JSONObject(value).optString(it) }
                is Map<*, *> -> value.forEach { (k, v) -> headers[k.toString()] = v.toString() }
                else -> {
                }
            }
        } catch (_: Throwable) {
        }
        return headers
    }
}
