package com.tvbox.kotlin.app.js

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.server.ControlManager
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JS 爬虫全局桥接对象，注册为 QuickJS 全局函数。
 *
 * 提供：
 * - _http：同步/异步 HTTP 请求（配合 net.js 的 req/http 使用）；
 * - pdfh/pdfa/pd/pdfla/joinUrl：HTML 解析；
 * - getProxy/js2Proxy：本地代理地址；
 * - setTimeout：延迟执行；
 * - aesX：AES 加解密。
 *
 * 每个爬虫实例持有独立桥接对象，避免异步回调串台。
 */
class GlobalBridge(
    private val okHttpHelper: OkHttpHelper,
    private val controlManager: ControlManager
) : com.tvbox.kotlin.engine.quickjs.JsBridgeHost {

    private lateinit var runtime: QuickJSContext
    private lateinit var executor: ExecutorService
    private val timer = Timer(true)

    @ContextSetter
    fun setContext(runtime: QuickJSContext) {
        this.runtime = runtime
    }

    override fun attachExecutor(executor: ExecutorService) {
        this.executor = executor
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    @Function("_http")
    fun http(url: String, options: JSObject?): JSObject? {
        val complete = options?.getJSFunction("complete")
        val req = ReqLike.fromJson(JSUtils.toJsonObject(options).toString())
        if (complete != null) {
            complete.hold()
            asyncRequest(url, req) { res ->
                executor.submit {
                    try {
                        complete.call(res)
                    } finally {
                        complete.release()
                    }
                }
            }
            return null
        }
        return try {
            val client = if (req.redirect == 1) okHttpHelper.client() else okHttpHelper.noRedirectClient()
            val call = client.newCall(buildRequest(url, req))
            val res = call.execute()
            buildResponse(res, req)
        } catch (_: Throwable) {
            errorResponse()
        }
    }

    private fun asyncRequest(url: String, req: ReqLike, onResult: (JSObject) -> Unit) {
        val client = if (req.redirect == 1) okHttpHelper.client() else okHttpHelper.noRedirectClient()
        client.newCall(buildRequest(url, req)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(errorResponse())
            }

            override fun onResponse(call: Call, response: Response) {
                onResult(buildResponse(response, req))
            }
        })
    }

    private fun buildRequest(url: String, req: ReqLike): Request {
        val builder = Request.Builder().url(url).tag(JS_OKHTTP_TAG).headers(req.headerMap.toHeaders())
        return when (req.method.lowercase()) {
            "post" -> builder.post(buildPostBody(req)).build()
            "header" -> builder.head().build()
            else -> builder.get().build()
        }
    }

    private fun buildPostBody(req: ReqLike): RequestBody {
        val data = req.data
        return when {
            data != null && req.postType == "json" ->
                data.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            data != null && req.postType == "form" -> {
                val form = FormBody.Builder()
                data.toMap().forEach { (k, v) -> form.add(k, v) }
                form.build()
            }
            data != null && req.postType == "form-data" -> {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                data.toMap().forEach { (k, v) -> builder.addFormDataPart(k, v) }
                builder.build()
            }
            req.body != null -> req.body!!.toRequestBody(req.contentType?.toMediaTypeOrNull())
            else -> ByteArray(0).toRequestBody(null)
        }
    }

    private fun buildResponse(res: Response, req: ReqLike): JSObject {
        return try {
            val obj = runtime.createNewJSObject()
            val header = runtime.createNewJSObject()
            res.headers.toMultimap().forEach { (key, values) ->
                if (values.size == 1) runtime.setProperty(header, key, values[0])
                else runtime.setProperty(header, key, JSUtils<Any>().toArray(runtime, values))
            }
            runtime.setProperty(obj, "headers", header)
            res.body?.use { body ->
                val bytes = body.bytes()
                when (req.buffer) {
                    1 -> {
                        val array = runtime.createNewJSArray()
                        for (i in bytes.indices) array.set(bytes[i].toInt(), i)
                        runtime.setProperty(obj, "content", array)
                    }
                    2 -> runtime.setProperty(obj, "content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    else -> runtime.setProperty(obj, "content", String(bytes, Charset.forName(req.charset)))
                }
            }
            obj
        } catch (e: Throwable) {
            errorResponse()
        }
    }

    private fun errorResponse(): JSObject {
        val obj = runtime.createNewJSObject()
        runtime.setProperty(obj, "headers", runtime.createNewJSObject())
        runtime.setProperty(obj, "content", "")
        return obj
    }

    // ------------------------------------------------------------------
    // HTML 解析
    // ------------------------------------------------------------------

    @Function
    fun pdfh(html: String, rule: String): String = HtmlParser.parseDomForUrl(html, rule, "")

    @Function
    fun pd(html: String, rule: String, addUrl: String): String = HtmlParser.parseDomForUrl(html, rule, addUrl)

    @Function
    fun pdfa(html: String, rule: String): JSArray =
        JSUtils<Any>().toArray(runtime, HtmlParser.parseDomForArray(html, rule))

    @Function
    fun pdfla(html: String, p1: String, listText: String, listUrl: String, addUrl: String): JSArray =
        JSUtils<Any>().toArray(runtime, HtmlParser.parseDomForList(html, p1, listText, listUrl, addUrl))

    @Function
    fun joinUrl(parent: String, child: String): String = HtmlParser.joinUrl(parent, child)

    // ------------------------------------------------------------------
    // 代理地址
    // ------------------------------------------------------------------

    @Function
    fun getProxy(local: Boolean): String = controlManager.getAddress(local) + "proxy?do=js"

    @Function
    fun js2Proxy(dynamic: Boolean, siteType: Int, siteKey: String, url: String, headers: JSObject): String {
        val encodedHeader = URLEncoder.encode(headers.stringify(), "UTF-8")
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        return getProxy(true) + "&from=catvod&siteType=$siteType&siteKey=$siteKey&header=$encodedHeader&url=$encodedUrl"
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    @Function
    fun setTimeout(func: JSFunction, delay: Int) {
        func.hold()
        timer.schedule(object : TimerTask() {
            override fun run() {
                executor.submit {
                    try {
                        func.call()
                    } finally {
                        func.release()
                    }
                }
            }
        }, delay.toLong())
    }

    @Function
    fun aesX(mode: String, encrypt: Boolean, input: String, inBase64: Boolean, key: String, iv: String?, outBase64: Boolean): String {
        return try {
            var keyBuf = key.toByteArray(Charsets.UTF_8)
            if (keyBuf.size < 16) keyBuf = keyBuf.copyOf(16)
            val cipher = Cipher.getInstance("$mode" + "Padding")
            val keySpec = SecretKeySpec(keyBuf, "AES")
            val inBuf = if (inBase64) {
                Base64.decode(input.replace("_", "/").replace("-", "+"), Base64.DEFAULT)
            } else {
                input.toByteArray(Charsets.UTF_8)
            }
            val outBuf = if (iv == null) {
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
                cipher.doFinal(inBuf)
            } else {
                var ivBuf = iv.toByteArray(Charsets.UTF_8)
                if (ivBuf.size < 16) ivBuf = ivBuf.copyOf(16)
                cipher.init(
                    if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    keySpec,
                    IvParameterSpec(ivBuf)
                )
                cipher.doFinal(inBuf)
            }
            if (outBase64) Base64.encodeToString(outBuf, Base64.NO_WRAP)
            else String(outBuf, Charsets.UTF_8)
        } catch (e: Throwable) {
            ""
        }
    }

    private companion object {
        const val JS_OKHTTP_TAG = "js_okhttp_tag"
    }
}

/** JS 侧请求参数，对应 options 的 JSON 字段 */
private class ReqLike {
    var buffer: Int = 0
    var redirect: Int = 1
    var timeout: Int = 10000
    var postType: String = "json"
    var method: String = "get"
    var body: String? = null
    var data: JsonElement? = null
    var headers: JsonElement? = null

    companion object {
        fun fromJson(json: String): ReqLike {
            return try {
                GlobalBridgeGson.fromJson(json, ReqLike::class.java) ?: ReqLike()
            } catch (_: Throwable) {
                ReqLike()
            }
        }
    }

    val headerMap: Map<String, String>
        get() {
            val map = HashMap<String, String>()
            try {
                headers?.asJsonObject?.keySet()?.forEach { key ->
                    map[key] = headers!!.asJsonObject.get(key).takeIf { it.isJsonPrimitive }?.asString ?: ""
                }
            } catch (_: Throwable) {
            }
            return map
        }

    val contentType: String?
        get() = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value

    val charset: String
        get() {
            contentType?.split(";")?.forEach { text ->
                if (text.contains("charset=")) return text.split("=")[1]
            }
            return "UTF-8"
        }
}

/** 供 ReqLike Gson 反序列化使用的单例 */
private val GlobalBridgeGson: Gson = Gson()

/** 表单参数提取：JSON 对象成员转 Map */
private fun JsonElement.toMap(): Map<String, String> {
    val map = HashMap<String, String>()
    try {
        if (isJsonObject) {
            asJsonObject.keySet().forEach { key ->
                map[key] = asJsonObject.get(key).takeIf { it.isJsonPrimitive }?.asString ?: ""
            }
        }
    } catch (_: Throwable) {
    }
    return map
}
