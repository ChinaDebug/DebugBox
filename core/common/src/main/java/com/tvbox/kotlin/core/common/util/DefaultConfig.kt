package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.LOG
import kotlin.jvm.Volatile
import java.util.regex.Pattern

/** 默认配置与通用工具方法 */
object DefaultConfig {

    /** 移动端 UA：某些源线路 ua 配置（源返回 vod_play_list 的 ua 字段），服务器校验移动端 UA */
    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"

    /** 当前订阅配置的目录 URL（http://host/path/），由 ApiConfig 加载配置时写入 */
    @Volatile
    var configBaseUrl: String = ""
        private set

    /** 更新配置目录 URL（内部使用） */
    fun updateConfigBaseUrl(url: String) {
        if (url.isNotBlank()) configBaseUrl = url
    }

    /**
     * 补全相对路径图片 URL。
     * 仅处理 "./xxx" 或 "/xxx" 开头（爬虫返回的相对路径图），基于配置目录拼接为完整 http URL；
     * http/https/data/file 及其余形式原样返回，保证不影响既有加载逻辑。
     */
    fun fixImageUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("data:") || url.startsWith("file:")
        ) {
            return url
        }
        val base = configBaseUrl
        if (base.isBlank()) return url
        return when {
            url.startsWith("./") -> base + url.substring(2)
            url.startsWith("/") -> base + url.substring(1)
            else -> url
        }
    }

    private val snifferMatch: Pattern = Pattern.compile(
        "https?((?!https?).){10,}?\\.(m3u8|mp4|flv|avi|mkv|rm|rmvb|wmv|mpg|mpeg|mov|ts|webm|f4v|3gp|asf|vob)(\\?|\$|#)|" +
            "https?((?!https?).)*?video/tos[^\\?]*|" +
            "https?((?!https?).){10,}?/m3u8\\?pt=m3u8.*|" +
            "https?((?!https?).)*?default\\.ixigua\\.com/.*|" +
            "https?((?!https?).)*?dycdn-tos\\.pstatp[^\\?]*|" +
            "https?((?!https?).)*?bytecdn\\.cn/.*|" +
            "https?((?!https?).)*?bytednsdoc\\.com/.*|" +
            "https?.*?/player/m3u8play\\.php\\?url=.*|" +
            "https?.*?/player/.*?[pP]lay\\.php\\?url=.*|" +
            "https?.*?/playlist/m3u8/\\?vid=.*|" +
            "https?.*?\\.php\\?type=m3u8&.*|" +
            "https?.*?/download\\.aspx\\?.*|" +
            "https?.*?/api/up_api\\.php\\?.*|" +
            "https?.*?\\.66yk\\.cn.*|" +
            "https?((?!https?).)*?netease\\.com/file/.*|" +
            "https?((?!https?).)*?\\.m3u8\\.cdn.*|" +
            "https?((?!https?).)*?/video/.*\\.m3u8.*|" +
            "https?((?!https?).)*?cdn.*?/.*\\.(m3u8|mp4|ts).*|" +
            "https?((?!https?).)*?/play/.*\\.(m3u8|mp4).*|" +
            "https?((?!https?).)*?/stream/.*\\.(m3u8|mp4|ts).*|" +
            "https?((?!https?).)*?/media/.*\\.(m3u8|mp4|ts).*|" +
            "https?((?!https?).)*?/hls/.*\\.(m3u8|ts).*|" +
            "https?((?!https?).)*?/dash/.*\\.(mpd|m4s).*"
    )

    /** 判断 url 是否为可嗅探的视频格式 */
    fun isVideoFormat(url: String): Boolean {
        if (url.contains("=http")) return false
        if (snifferMatch.matcher(url).find()) {
            val lowerUrl = url.lowercase()
            return !lowerUrl.contains(".js") &&
                !lowerUrl.contains(".css") &&
                !lowerUrl.contains(".jpg") &&
                !lowerUrl.contains(".jpeg") &&
                !lowerUrl.contains(".png") &&
                !lowerUrl.contains(".gif") &&
                !lowerUrl.contains(".ico") &&
                !lowerUrl.contains(".webp") &&
                !lowerUrl.contains(".svg") &&
                !lowerUrl.contains(".woff") &&
                !lowerUrl.contains(".ttf") &&
                !lowerUrl.contains("rl=") &&
                !isHtmlFile(url)
        }
        return false
    }

    private fun isHtmlFile(url: String): Boolean {
        val queryStart = url.indexOf('?')
        val hashStart = url.indexOf('#')
        var pathEnd = url.length
        if (queryStart > 0) pathEnd = minOf(pathEnd, queryStart)
        if (hashStart > 0) pathEnd = minOf(pathEnd, hashStart)
        val path = url.substring(0, pathEnd).lowercase()
        return path.endsWith(".html") || path.endsWith(".htm")
    }

    /** 安全读取 JsonObject 字符串字段 */
    fun safeJsonString(obj: JsonObject?, key: String, defaultVal: String): String {
        return try {
            if (obj != null && obj.has(key)) {
                val element = obj.get(key)
                if (element.isJsonObject || element.isJsonArray) element.toString().trim()
                else obj.getAsJsonPrimitive(key).getAsString().trim()
            } else defaultVal
        } catch (_: Throwable) {
            defaultVal
        }
    }

    /** 安全读取 JsonObject Int 字段 */
    fun safeJsonInt(obj: JsonObject?, key: String, defaultVal: Int): Int {
        return try {
            if (obj != null && obj.has(key)) obj.getAsJsonPrimitive(key).asInt else defaultVal
        } catch (_: Throwable) {
            defaultVal
        }
    }

    /** 安全读取 JsonObject 数组字段：字段缺失、为 null 或非数组时返回 null */
    fun safeJsonArray(obj: JsonObject?, key: String): JsonArray? {
        return try {
            if (obj != null && obj.has(key)) obj.get(key).takeIf { it.isJsonArray }?.asJsonArray else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 安全读取 JsonObject 字符串数组字段 */
    fun safeJsonStringList(obj: JsonObject?, key: String): ArrayList<String> {
        val result = ArrayList<String>()
        return try {
            if (obj != null && obj.has(key)) {
                if (obj.get(key).isJsonObject) {
                    result.add(obj.get(key).asString)
                } else {
                    // 逐元素读取并跳过非字符串项，避免单个异常元素中断整个列表
                    for (opt in obj.getAsJsonArray(key)) {
                        opt.takeIf { it.isJsonPrimitive }?.asString?.let { result.add(it) }
                    }
                }
            }
            result
        } catch (_: Throwable) {
            result
        }
    }

    /**
     * 处理 proxy:// 前缀的 url。
     * 实际代理地址由上层业务模块注入，common 模块仅剥离前缀占位符。
     */
    fun checkReplaceProxy(urlOri: String): String {
        if (urlOri.startsWith("proxy://")) {
            return urlOri.replace("proxy://", "")
        }
        return urlOri
    }
}
