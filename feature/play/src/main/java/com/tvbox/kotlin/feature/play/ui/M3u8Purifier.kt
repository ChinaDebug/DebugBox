package com.tvbox.kotlin.feature.play.ui

import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.M3U8
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.server.ControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * m3u8 净化播放器（全屏与详情页预览共用）。
 * 下载 m3u8 后移除 404 坏分片并广告净化，经本地 /m3u8 服务提供净化后的播放列表。
 */
class M3u8Purifier @Inject constructor(
    private val okHttpHelper: OkHttpHelper,
    private val controlManager: ControlManager,
    private val appPreferences: AppPreferences
) {

    /** 广告移除回调（参数为移除条数），UI 层据此提示 */
    var onAdRemoved: ((Int) -> Unit)? = null

    /** 净化后经 [onPlay] 播放；开关关闭/非 m3u8/已是本地代理时直接播原地址，净化失败回退原地址 */
    suspend fun purify(url: String, headers: Map<String, String>?, onPlay: (String, Map<String, String>?) -> Unit) {
        val purifyEnabled = appPreferences.getBoolean(AppConstants.VIDEO_PURIFY, true)
        val isM3u8 = url.contains("://127.0.0.1/") || url.contains(".m3u8")
        val isLocalProxy = url.contains("://127.0.0.1/") && url.contains("/m3u8")
        if (!purifyEnabled || !isM3u8 || isLocalProxy) {
            onPlay(url, headers)
            return
        }
        val content = withContext(Dispatchers.IO) {
            runCatching { okHttpHelper.get(url, headers ?: emptyMap()) }.getOrDefault("")
        }
        if (!content.startsWith("#EXTM3U")) {
            onPlay(url, headers)
            return
        }
        val forwardUrl = parseFirstM3u8(url, content)
        val target: Pair<String, String> = if (forwardUrl.isNotEmpty()) {
            val forwardContent = withContext(Dispatchers.IO) {
                runCatching { okHttpHelper.get(forwardUrl, headers ?: emptyMap()) }.getOrDefault("")
            }
            if (!forwardContent.startsWith("#EXTM3U")) {
                onPlay(forwardUrl, headers)
                return
            }
            forwardUrl to forwardContent
        } else {
            url to content
        }
        val base = target.first.substring(0, target.first.lastIndexOf('/') + 1)
        // ===== 净化第一层：坏片检测（地址有效性保障，播放核心）=====
        // 采样探测移除 404 坏分片；加密（#EXT-X-KEY）/相对路径列表跳过探测，交由播放层 HlsTolerant 容错
        val fixedContent = removeBrokenSegments(target.second, headers)
        if (!fixedContent.startsWith("#EXTM3U") ||
            fixedContent.lines().none { it.isNotBlank() && it[0] != '#' }
        ) {
            onPlay(target.first, headers)
            return
        }
        // ===== 净化第二层：广告过滤（可选增值，依赖源配置广告正则 adRegexProvider）=====
        val purified = M3U8.purify(base, fixedContent)
        // 净化后统一补全相对路径分片为绝对 URL：净化后经本地 127.0.0.1/m3u8 代理播放时，
        // 相对路径会被解析到本机（127.0.0.1/xxx.ts）导致分片 404，必须补全为原始 CDN 地址
        val finalContent = absolutizeRelativeUrls(purified ?: fixedContent, base)
        // 净化无实际变更（无坏分片移除、无广告移除）时直接播放原始地址：
        // 播放器会基于原地址自行解析相对路径分片，无需本地代理；
        // 仅当确实移除了坏分片/广告、需要输出净化后列表时才走本地代理
        if (fixedContent == target.second && M3U8.currentAdCount == 0) {
            onPlay(target.first, headers)
            return
        }
        var server = controlManager.getServer()
        if (server == null) {
            controlManager.startServer()
            server = controlManager.getServer()
        }
        if (server == null) {
            onPlay(target.first, headers)
            return
        }
        server.m3u8Content = finalContent
        if (M3U8.currentAdCount > 0) {
            onAdRemoved?.invoke(M3U8.currentAdCount)
        }
        onPlay("http://127.0.0.1:${controlManager.currentPort()}/m3u8", headers)
    }

    /** 采样探测分片（Range bytes=0-0 + 跟随重定向），移除 404 坏分片及其 #EXTINF。
     *  按步长采样而非全量探测：全量探测耗时超过源分片签名有效期，净化完成时签名已过期导致播放失败；大量并发请求也易触发 CDN 风控。
     *  坏分片通常连续出现（整段 404），采样 + 邻域移除即可覆盖。 */
    private suspend fun removeBrokenSegments(
        m3u8Content: String,
        headers: Map<String, String>?
    ): String {
        val lines = (if (m3u8Content.contains("\r\n")) m3u8Content.split("\r\n") else m3u8Content.split("\n"))
            .toMutableList()
        // 加密播放列表（#EXT-X-KEY）跳过分片移除：移除分片只清理其相邻 #EXTINF，
        // 会破坏 #EXT-X-KEY 与分片的对应关系，后续分片用错密钥解密直接播放失败（ERROR_CODE_UNSPECIFIED）；
        // 加密列表的坏分片交由播放层 HlsTolerant 重试兜底
        if (m3u8Content.contains("#EXT-X-KEY")) {
            return m3u8Content
        }
        val segmentIndexes = lines.indices.filter { idx ->
            val line = lines[idx]
            idx > 0 && line.isNotBlank() && line[0] != '#'
        }
        if (segmentIndexes.isEmpty()) return m3u8Content
        // 分片为相对路径（非 http 开头）时无法用 OkHttp 探测（Request.url 需绝对 URL），
        // 跳过探测：交由 absolutizeRelativeUrls 补全 + 播放层 HlsTolerant 重试兜底，
        // 避免全部分片被误判为坏分片而触发 30% 回退（净化形同虚设）
        if (segmentIndexes.any { idx ->
                !lines[idx].startsWith("http://") && !lines[idx].startsWith("https://")
            }
        ) {
            return m3u8Content
        }
        // 采样点：每 6 个分片探测 1 个（均匀覆盖全片），强制包含前 3 个（首屏优先）
        val probeIndexes = (segmentIndexes.filterIndexed { i, _ -> i % 6 == 0 } + segmentIndexes.take(3)).distinct()
        val client = okHttpHelper.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build().newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            })
            .build()
        val badSet = withContext(Dispatchers.IO) {
            probeIndexes.map { idx ->
                async {
                    val url = lines[idx]
                    val ok = runCatching {
                        val builder = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0")
                        // 探测请求与真实播放保持一致（含 UA）：NBY 等源的分片 CDN 校验移动端 UA，
                        // 探测不带 UA 会被误判 404，导致大段分片被误移除、净化后总时长严重缩短
                        headers?.forEach { (k, v) ->
                            if (k.isNotBlank() && v.isNotBlank()) {
                                builder.header(k, v)
                            }
                        }
                        client.newCall(builder.build()).execute().use { resp ->
                            resp.code in 200..299
                        }
                    }.getOrDefault(false)
                    idx to ok
                }
            }.awaitAll().toMap()
        }
        val badCount = badSet.count { !it.value }
        // 坏分片占比过高视为探测异常（探测条件与真实播放不一致），回退原内容不净化：
        // 避免净化后播放列表过短导致总时长错乱、进度条快速跳集（与广告净化 removeMinorityUrl 的 0.3 阈值一致）
        if (badCount == 0) return m3u8Content
        if (badCount > probeIndexes.size * 0.3) {
            return m3u8Content
        }
        // 坏采样点及其邻域（前后 3 个分片）一并移除：未采样的连续坏片随邻域覆盖
        val badIdxSet = badSet.filterValues { !it }.keys
        var removed = 0
        for (segIdx in segmentIndexes) {
            if (badIdxSet.none { kotlin.math.abs(it - segIdx) <= 3 }) continue
            lines[segIdx] = ""
            var j = segIdx - 1
            while (j >= 0 && lines[j].isNotBlank() && lines[j].startsWith("#EXTINF")) {
                lines[j] = ""
                j--
            }
            removed++
        }
        return lines.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** 将播放列表中的相对路径分片与加密 KEY 补全为基于 [base] 的绝对 URL：
     *  净化后经本地 127.0.0.1/m3u8 代理播放时，相对路径会被解析到本机导致分片/密钥 404，
     *  必须预先补全为原始 CDN 绝对地址；http 行与普通标签行原样保留 */
    private fun absolutizeRelativeUrls(content: String, base: String): String {
        val baseUri = runCatching { java.net.URI(base) }.getOrNull() ?: return content
        return content.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("http://") || t.startsWith("https://") -> line
                t.startsWith("#EXT-X-KEY") -> {
                    // 加密密钥 URI 相对路径补全（本地代理播放时解析到 127.0.0.1 会 404）
                    val m = Regex("URI=\"([^\"]+)\"").find(t)
                    if (m == null) {
                        line
                    } else {
                        val keyUrl = m.groupValues[1]
                        if (keyUrl.startsWith("http://") || keyUrl.startsWith("https://")) {
                            line
                        } else {
                            val resolved = runCatching { baseUri.resolve(keyUrl).toString() }.getOrNull() ?: line
                            t.replace(keyUrl, resolved)
                        }
                    }
                }
                t.startsWith("#") -> line
                else -> runCatching { baseUri.resolve(t).toString() }.getOrNull() ?: line
            }
        }
    }

    /** 解析首行 m3u8 跳转地址，非跳转列表返回空串 */
    private fun parseFirstM3u8(url: String, content: String): String {
        val lines = if (content.contains("\r\n")) content.split("\r\n") else content.split("\n")
        var forwardUrl = ""
        var dealtFirst = false
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            if (dealtFirst) {
                forwardUrl = ""
                break
            }
            if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                forwardUrl = when {
                    line.startsWith("http://") || line.startsWith("https://") -> line
                    line[0] == '/' -> {
                        val idx = url.indexOf('/', 9)
                        if (idx > 0) url.substring(0, idx) + line else line
                    }
                    else -> {
                        val idx = url.lastIndexOf('/')
                        url.substring(0, idx + 1) + line
                    }
                }
            }
            dealtFirst = true
        }
        return forwardUrl
    }
}
