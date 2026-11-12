package com.github.catvod.crawler

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.Base64
import android.view.Display
import android.view.Surface
import com.github.catvod.Init
import com.github.catvod.net.OkHttp
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.tvbox.kotlin.core.common.util.LocalIPAddress
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 爬虫 API 注入对象，提供主进程的服务端口、屏幕方向、并发请求与 web 解析代理能力。
 * 由 JarLoader 在创建 Spider 实例时通过 spider.initApi(this) 注入给 jar 内爬虫使用。
 */
open class SpiderApi {

    /** 获取本机服务地址，local=true 返回 127.0.0.1 地址，否则返回局域网地址 */
    open fun getAddress(local: Boolean): String = try {
        val host = if (local) "127.0.0.1" else LocalIPAddress.getLocalIPAddress(Init.context())
        "http://$host:${com.github.catvod.Proxy.getPort()}/"
    } catch (_: Throwable) {
        ""
    }

    /** 获取本地服务端口字符串 */
    open fun getPort(): String = try {
        val address = getAddress(true)
        val idx = address.lastIndexOf(":")
        if (idx >= 0) address.substring(idx + 1).replace("/", "") else ""
    } catch (_: Throwable) {
        ""
    }

    /** 通过 SpiderDebug 统一输出日志 */
    open fun log(msg: String) {
        try {
            SpiderDebug.log(msg)
        } catch (_: Throwable) {
        }
    }

    /** 获取当前屏幕方向，便于 jar 内爬虫自适应旋转 */
    open fun getScreenOrientation(): Int = try {
        val context = Init.context() ?: return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val orientation = context.resources.configuration.orientation
        var rotation = Surface.ROTATION_0
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager != null) {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) rotation = display.rotation
        }
        when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Configuration.ORIENTATION_LANDSCAPE ->
                if (rotation == Surface.ROTATION_90) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    } catch (_: Throwable) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** 并发批量请求，最多 6 个线程同时执行，结果合并为 JsonArray 字符串 */
    open fun multiReq(array: JsonArray?): String {
        return try {
            if (array == null || array.size() == 0) return ""
            val executor: ExecutorService = Executors.newFixedThreadPool(minOf(array.size(), 6))
            val futures = ArrayList<Future<String>>()
            for (element in array) {
                if (!element.isJsonObject) continue
                futures.add(executor.submit<String> { request(element.asJsonObject) })
            }
            val result = JsonArray()
            for (future in futures) result.add(toResult(future.get()))
            executor.shutdown()
            result.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    /** 生成走本地代理的 web 解析 URL，flag 用于标识解析来源 */
    open fun webParse(url: String?, flag: String?): String {
        return try {
            if (url.isNullOrEmpty()) return ""
            val encoded = Base64.encodeToString(
                url.toByteArray(charset("UTF-8")),
                Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
            )
            "proxy://go=SuperParse&flag=" + (flag ?: "") + "&url=" + encoded
        } catch (_: Throwable) {
            ""
        }
    }

    private fun request(obj: JsonObject): String {
        return try {
            val url = string(obj, "url")
            if (url.isEmpty()) return ""
            val method = string(obj, "method")
            val headers = headers(obj.get("headers"))
            val builder = Request.Builder().url(url).headers(headers)
            if ("POST".equals(method, ignoreCase = true)) builder.post(body(obj))
            val client = OkHttp.client()
            client.newCall(builder.build()).execute().use { response: Response ->
                response.body?.string() ?: ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun toResult(text: String?): JsonElement {
        if (text == null) return JsonPrimitive("")
        return try {
            val trim = text.trim()
            if (trim.startsWith("{") || trim.startsWith("[")) JsonParser.parseString(trim)
            else JsonPrimitive(text)
        } catch (_: Throwable) {
            JsonPrimitive(text)
        }
    }

    private fun body(obj: JsonObject): okhttp3.RequestBody {
        val data = obj.get("data")
        if (data == null || data.isJsonNull) {
            return "".toRequestBody(null)
        }
        val postType = string(obj, "postType")
        if ("form".equals(postType, ignoreCase = true) && data.isJsonObject) {
            val builder = FormBody.Builder()
            for ((key, value) in data.asJsonObject.entrySet()) {
                builder.add(key, value.asString)
            }
            return builder.build()
        }
        val raw = if (data.isJsonPrimitive) data.asString else data.toString()
        return raw.toRequestBody(null)
    }

    private fun headers(element: JsonElement?): Headers {
        return try {
            if (element == null || element.isJsonNull || !element.isJsonObject) {
                return Headers.Builder().build()
            }
            val map = HashMap<String, String>()
            for ((key, value) in element.asJsonObject.entrySet()) {
                map[key] = value.asString
            }
            map.toHeaders()
        } catch (_: Throwable) {
            Headers.Builder().build()
        }
    }

    private fun string(obj: JsonObject, key: String): String {
        val element = obj.get(key)
        return if (element == null || element.isJsonNull) "" else element.asString
    }
}
