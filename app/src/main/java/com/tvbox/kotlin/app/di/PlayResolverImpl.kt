package com.tvbox.kotlin.app.di

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.tvbox.kotlin.app.thunder.Jianpian
import com.tvbox.kotlin.app.thunder.Thunder
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.spider.VideoParseRuler
import com.tvbox.kotlin.core.ui.PlayResolver
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 播放解析实现，由 :app 注入到 feature:play 模块。
 *
 * - [loadPlayerContent]：委托 [ApiConfig.playerContent] 调用爬虫获取播放内容；
 * - [resolveVideo]：按解析器类型处理，JSON 类型请求解析接口提取实际地址，
 *   嗅探类型使用 WebView 加载解析页拦截视频请求提取真实地址。
 */
@Singleton
class PlayResolverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiConfig: ApiConfig,
    private val okHttpHelper: OkHttpHelper
) : PlayResolver {

    override suspend fun loadPlayerContent(
        sourceKey: String,
        flag: String,
        id: String,
        vipFlags: List<String>
    ): String = withContext(Dispatchers.IO) {
        runCatchingResume {
            apiConfig.playerContent(sourceKey, flag, id, vipFlags)
        }.getOrDefault("").let { fixLocalProxyPort(it) }
    }

    /**
     * 修复 jar 返回内容中本地代理端口 -1 的引用。
     * 部分 jar 内 Proxy 端口未同步成功时会把代理地址生成为 127.0.0.1:-1，
     * 这里按实际监听端口兜底替换，保证弹幕等本地代理请求可用。
     */
    private fun fixLocalProxyPort(content: String): String {
        if (!content.contains("127.0.0.1:-1") && !content.contains("localhost:-1")) return content
        val port = com.github.catvod.Proxy.getPort()
        return content
            .replace("127.0.0.1:-1", "127.0.0.1:$port")
            .replace("localhost:-1", "localhost:$port")
    }

    override suspend fun getDefaultParse(): ParseBean? = withContext(Dispatchers.IO) {
        runCatchingResume { apiConfig.getDefaultParse() }.getOrNull()
    }

    override suspend fun normalizePlayUrl(url: String, sourceKey: String): String = withContext(Dispatchers.IO) {
        when {
            url.startsWith("proxy://") -> {
                val base = com.github.catvod.Proxy.getUrl(true) + "?" + url.removePrefix("proxy://")
                // 附加 siteKey 供代理分发定位处理 jar（多 jar 场景必需）
                if (sourceKey.isNotEmpty() && !base.contains("siteKey=")) {
                    base + (if (base.contains("?")) "&" else "?") +
                        "siteKey=" + java.net.URLEncoder.encode(sourceKey, "UTF-8")
                } else {
                    base
                }
            }
            url.startsWith("video://") -> url.removePrefix("video://")
            else -> url
        }
    }

    override suspend fun containsFlag(flag: String): Boolean = withContext(Dispatchers.IO) {
        runCatchingResume { apiConfig.containsFlag(flag) }.getOrDefault(false)
    }

    /**
     * 特殊播放协议处理：
     * 1. tvbox-drive:// 直链（去前缀直播，进度键用去前缀地址）；
     * 2. 荐片（tvbox-xg:/含 gbl.114s 的 ftp）经 P2P 组件解码为本地 http 地址；
     * 3. magnet/thunder/ftp 由开源 BT 引擎下载后回调本地 HTTP 播放地址；
     * 4. type 0/1 源非 http 地址判为"视频源地址错误"。
     * 返回 null 表示普通协议，走常规解析流程。
     */
    override suspend fun resolvePlayProtocol(
        episodeUrl: String,
        sourceKey: String
    ): PlayResolver.ProtocolResult? = withContext(Dispatchers.IO) {
        if (episodeUrl.isEmpty()) return@withContext null
        // 1. tvbox-drive:// 直链播放
        if (episodeUrl.startsWith("tvbox-drive://")) {
            val driveUrl = episodeUrl.substring("tvbox-drive://".length)
            if (driveUrl.isEmpty()) {
                return@withContext PlayResolver.ProtocolResult(error = context.getString(uiR.string.play_err_no_video_addr))
            }
            return@withContext PlayResolver.ProtocolResult(url = driveUrl)
        }
        // 2. 荐片地址（Jianpian.isJpUrl 判定）
        if (episodeUrl.startsWith("tvbox-xg:") ||
            (episodeUrl.startsWith("ftp://", ignoreCase = true) && episodeUrl.contains("gbl.114s"))
        ) {
            val jp = if (episodeUrl.startsWith("tvbox-xg:")) {
                Jianpian.jpUrlDec(episodeUrl.substring(9))
            } else {
                Jianpian.jpUrlDec(episodeUrl)
            }
            if (jp.startsWith("http", ignoreCase = true)) {
                return@withContext PlayResolver.ProtocolResult(url = jp)
            }
            return@withContext PlayResolver.ProtocolResult(error = if (jp.isEmpty()) context.getString(uiR.string.play_err_jianpian) else jp)
        }
        // 3. 下载类协议（magnet/thunder/ftp，Thunder.play 等待本地 HTTP 播放地址回调）
        if (Thunder.isNetworkDownloadTask(episodeUrl)) {
            return@withContext resolveThunder(episodeUrl)
        }
        // 4. type 0/1 源非 http 地址校验（sourceType 0/1 判定）：
        // 采集站理论上返回 http 直链，但磁力/网盘/本地代理/局域网流等合法特殊协议
        // 仅拦截既非 http 又非任何已知协议的异常地址
        val type = runCatchingResume { apiConfig.getSource(sourceKey)?.type }.getOrDefault(-1)
        if ((type == 0 || type == 1) && !isKnownPlayableProtocol(episodeUrl)) {
            return@withContext PlayResolver.ProtocolResult(error = context.getString(uiR.string.play_err_video_addr))
        }
        null
    }

    /**
     * 已知可播放/可处理的协议白名单：
     * 除 http/https 外覆盖下载类（磁力/迅雷解密链）、网盘直链、本地代理、局域网文件与直播流协议，
     * 用于 type 0/1 采集站返回非 http 地址时的放行判断，避免误杀合法特殊协议。
     */
    private fun isKnownPlayableProtocol(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") ||
            lower.startsWith("magnet:") ||
            lower.startsWith("thunder://") ||
            lower.startsWith("ftp://") ||
            lower.startsWith("tvbox-drive://") ||
            lower.startsWith("tvbox-xg:") ||
            lower.startsWith("proxy://") ||
            lower.startsWith("data:") ||
            lower.startsWith("clan://") ||
            lower.startsWith("smb://") ||
            lower.startsWith("nfs://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://") ||
            lower.startsWith("rtp://") ||
            lower.startsWith("udp://") ||
            lower.startsWith("blutv://") ||
            lower.startsWith("file://")
    }

    /** 下载类协议解析：Thunder 接管后等待本地 HTTP 播放地址，失败返回错误信息 */
    private suspend fun resolveThunder(url: String): PlayResolver.ProtocolResult? =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val handed = try {
                    Thunder.play(url, object : Thunder.ThunderCallback {
                        override fun status(code: Int, info: String) {
                            // 状态回调：code<0 表示下载失败，直接结束等待并返回错误
                            if (code < 0 && cont.isActive) {
                                cont.resume(PlayResolver.ProtocolResult(error = info))
                            }
                        }

                        override fun play(playUrl: String) {
                            if (cont.isActive) cont.resume(PlayResolver.ProtocolResult(url = playUrl))
                        }
                    })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    LOG.e(e)
                    if (cont.isActive) {
                        cont.resume(PlayResolver.ProtocolResult(error = context.getString(uiR.string.play_err_thunder)))
                    }
                    return@suspendCancellableCoroutine
                }
                // Thunder 未接管（非其可解析地址），按普通协议继续
                if (!handed && cont.isActive) cont.resume(null)
            }
        }

    /** 停止下载类协议后台任务（开源 BT 下载/荐片 P2P，Thunder.stop() + Jianpian.finish） */
    override fun stopDownloadProtocols() {
        // 取消后台备用解析收集：其 WebView 嗅探会发出声音，页面销毁时立即取消停声
        // （备用收集跑在全局 resolveScope，不随页面销毁自动取消，必须显式取消）
        backupCollectJob?.cancel()
        backupCollectJob = null
        try {
            Thunder.stop()
        } catch (_: Throwable) {
        }
        try {
            Jianpian.finish()
        } catch (_: Throwable) {
        }
    }

    override suspend fun fetchDanmu(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        // 仅本地代理转发端点（/proxy，do 参数由爬虫处理）调用 proxyLocal；
        // 本地弹幕服务的普通地址（如 /danmu）直接 HTTP 拉取——proxyLocal 无对应
        // 爬虫处理器时返回 null，会被误判为弹幕加载失败
        if (isLocalProxyEndpoint(url)) {
            val params = HashMap<String, String>()
            runCatching {
                val query = URL(url).query
                if (!query.isNullOrEmpty()) {
                    query.split("&").forEach { pair ->
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            params[pair.substring(0, idx)] =
                                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        }
                    }
                }
            }
            // 空参数也尝试调用，
            // 部分源弹幕 URL 无 query 参数时由 proxyLocal 实现兜底，避免误判为加载失败
            val localResult = runCatchingResume {
                val rs = apiConfig.proxyLocal(params)
                if (rs != null && rs.size >= 3 && rs[2] is InputStream) {
                    (rs[2] as InputStream).bufferedReader().use { it.readText() }
                } else null
            }.getOrNull()
            localResult
        } else {
            runCatchingResume {
                okHttpHelper.get(url)
            }.getOrNull()
        }
    }

    /** 是否为本地代理转发的 /proxy 端点（爬虫 proxyLocal 入口） */
    private fun isLocalProxyEndpoint(url: String): Boolean {
        if (!url.contains("127.0.0.1") && !url.contains("localhost")) return false
        return url.substringBefore('?').trimEnd('/').endsWith("/proxy")
    }

    override suspend fun resolveVideo(
        parseBean: ParseBean,
        url: String,
        flag: String
    ): PlayResolver.ResolveResult = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext PlayResolver.ResolveResult("")

        when (parseBean.type) {
            ParseBean.TYPE_JSON, ParseBean.TYPE_JSON_EXT, ParseBean.TYPE_AGGREGATE -> {
                resolveByJson(parseBean, url)
            }
            ParseBean.TYPE_SNIFF -> {
                resolveBySniff(parseBean, url)
            }
            else -> {
                PlayResolver.ResolveResult(url)
            }
        }
    }

    /**
     * JSON 解析：解析站地址 + 编码后的视频地址拼接请求，从响应中提取播放地址。
     * 解析站返回 parse=1 或 video:// 地址时，继续用 WebView 二次嗅探真实视频地址。
     */
    private suspend fun resolveByJson(
        parseBean: ParseBean,
        videoUrl: String
    ): PlayResolver.ResolveResult {
        val parseUrl = parseBean.getUrl()
        // 解析站为空时 JSON 请求无意义，判定解析失败
        if (parseUrl.isEmpty()) return PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_parser_no_url))

        val requestUrl = parseUrl + encodeUrl(videoUrl)
        val headers = buildHeaders(parseBean.ext)
        return try {
            val response = okHttpHelper.get(requestUrl, headers)
            val parsed = parseJsonResponse(response, videoUrl)
            if (parsed.error != null) return PlayResolver.ResolveResult("", error = parsed.error)
            // 解析站要求二次嗅探：用 WebView 加载返回地址拦截真实视频请求
            if (parsed.needSniff) {
                return resolveBySniffUrl(parsed.url, parsed.headers)
            }
            PlayResolver.ResolveResult(parsed.url, parsed.headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 解析接口请求失败（服务器未响应）
            PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_parse_no_response))
        }
    }

    /**
     * 对解析站返回的地址执行 WebView 嗅探（解析站返回 parse=1 / video:// 时）。
     * 此类地址通常是需二次解析的网页/播放页，直接播放必然失败。
     */
    private suspend fun resolveBySniffUrl(
        url: String,
        headers: Map<String, String>?
    ): PlayResolver.ResolveResult {
        val result = withContext(Dispatchers.Main) {
            sniffWithWebView(url, headers ?: emptyMap())
        }
        if (result == null) {
            return PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_sniff_timeout))
        }
        return PlayResolver.ResolveResult(result.first, result.second)
    }

    /**
     * 嗅探解析：使用 WebView 加载解析页，拦截视频请求提取真实播放地址。
     * 加载解析页后在 [WebViewClient.shouldInterceptRequest]
     * 中匹配视频格式 URL（m3u8/mp4/ts 等），命中后停止加载并返回。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveBySniff(
        parseBean: ParseBean,
        videoUrl: String
    ): PlayResolver.ResolveResult {
        // 解析站为空时直接嗅探视频地址本身（不编码），否则拼接解析站地址与编码后的视频地址
        val parseUrl = parseBean.getUrl()
        val loadUrl = if (parseUrl.isEmpty()) videoUrl else parseUrl + encodeUrl(videoUrl)
        val headers = buildHeaders(parseBean.ext)
        val result = withContext(Dispatchers.Main) {
            sniffWithWebView(loadUrl, headers)
        }
        if (result == null) {
            // 嗅探失败判定解析失败，返回空由上层处理，避免拿网页地址冒充播放地址
            return PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_sniff_timeout))
        }
        return PlayResolver.ResolveResult(result.first, result.second)
    }

    /** 解析后台作用域：竞速首个成功返回后，其余解析站结果经 Channel 持续上报备用地址（不阻塞播放协程） */
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前备用地址收集协程：新一轮解析开始时取消上一轮（销毁旧 WebView、防止旧结果混入新集） */
    private var backupCollectJob: Job? = null

    /**
     * 超级解析（type 4）备用地址收集：
     * 所有解析站（JSON + WebView 嗅探）并发竞速，首个成功结果立即返回播放；
     * 其余解析站后续竞速返回的成功地址经 Channel 持续上报，作为播放失败时的备用地址，
     * 加载提示不变，全部地址失败后才提示失败。非 type 4 单解析器无备用。
     */
    override suspend fun resolveVideoWithBackups(
        parseBean: ParseBean,
        url: String,
        flag: String
    ): Pair<PlayResolver.ResolveResult, ReceiveChannel<PlayResolver.ResolveResult>> {
        // 新一轮解析：取消上一轮备用收集（销毁旧 WebView，防止连续换集 WebView 堆积拖慢新解析）
        backupCollectJob?.cancel()
        backupCollectJob = null
        if (parseBean.type == 4) {
            return resolveBySuperParseWithBackups(flag, url)
        }
        // 非 type 4：当前解析器解析为主（用户选择/默认），
        // 同时所有其他解析站并发收集备用地址：主地址解析失败或播放失败时
        // 依次尝试备用地址，全部失败才提示失败
        val channel = Channel<PlayResolver.ResolveResult>(Channel.UNLIMITED)
        val others = apiConfig.getParseBeanList().filter {
            it != parseBean && it.getUrl().isNotEmpty() && it.getUrl() != "SuperParse"
        }
        backupCollectJob = resolveScope.launch {
            try {
                coroutineScope {
                    others.forEach { bean ->
                        launch(Dispatchers.IO) {
                            requestJsonParse(bean, url)?.let { channel.send(it) }
                        }
                    }
                }
            } finally {
                channel.close()
            }
        }
        val main = resolveVideo(parseBean, url, flag)
        return main to channel
    }

    /**
     * 超级解析（type 4）备用收集：所有解析站（JSON + WebView 嗅探）并发竞速，
     * 首个成功结果立即返回播放；其余解析站后续成功结果经 Channel 上报为备用地址。
     */
    private suspend fun resolveBySuperParseWithBackups(
        flag: String,
        videoUrl: String
    ): Pair<PlayResolver.ResolveResult, ReceiveChannel<PlayResolver.ResolveResult>> {
        val (jsonBeans, sniffBeans) = collectSuperParseBeans(flag)
        if (jsonBeans.isEmpty() && sniffBeans.isEmpty()) {
            return PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_no_parse_site)) to emptyChannel()
        }
        val channel = Channel<PlayResolver.ResolveResult>(Channel.UNLIMITED)
        // 所有解析站全量并行竞速：
        // - JSON 解析站：并行请求解析接口，成功即带站名 send 进通道；
        // - 嗅探解析站：单 WebView 加载聚合 iframe 页（ServerHostImpl 生成，每帧一个
        //   嗅探站错峰加载），拦截视频请求后按请求头 Referer/Origin 反查解析站标记站名。
        // 竞速：谁先返回播放地址先播放谁；后续结果作为备用地址，全部失败才提示失败。
        val jxNameMap = sniffBeans.mapNotNull { it.name?.let { name -> it.getUrl() to name } }.toMap()
        backupCollectJob = resolveScope.launch {
            try {
                coroutineScope {
                    // JSON 解析站：并行竞速（结果带站名，供播放成功后提示具体命中源）
                    jsonBeans.forEach { bean ->
                        launch(Dispatchers.IO) {
                            requestJsonParse(bean, videoUrl)?.let {
                                channel.send(it.withSourceName(bean.name))
                            }
                        }
                    }
                    // 嗅探解析站：聚合代理页单 WebView 收集，命中按请求头反查站名
                    if (sniffBeans.isNotEmpty()) {
                        val encoded = Base64.encodeToString(
                            videoUrl.toByteArray(Charsets.UTF_8),
                            Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
                        )
                        val proxyUrl =
                            "http://127.0.0.1:${com.github.catvod.Proxy.getPort()}/proxy?go=SuperParse&flag=$flag&url=$encoded"
                        withContext(Dispatchers.Main) {
                            sniffSuperWithWebView(proxyUrl, jxNameMap) { url, headers, name ->
                                channel.trySend(PlayResolver.ResolveResult(url, headers, sourceName = name))
                            }
                        }
                    }
                }
            } finally {
                channel.close()
            }
        }
        // 竞速：首个成功结果立即返回；全部失败或超时判定解析失败
        val first = withTimeoutOrNull(SUPER_JSON_TIMEOUT_MS) {
            channel.receiveCatching().getOrNull()
        }
        if (first == null) {
            return PlayResolver.ResolveResult("", error = context.getString(uiR.string.play_err_all_parse_failed)) to emptyChannel()
        }
        return first to channel
    }

    private fun emptyChannel(): ReceiveChannel<PlayResolver.ResolveResult> =
        Channel<PlayResolver.ResolveResult>().apply { close() }

    /**
     * 聚合超级解析站：收集全部可用的解析器（除聚合器自身 url="SuperParse"）参与竞速，
     * 不做 type/ext.flag 过滤——用户手动选择可正常解析的解析站（type 1/2/3/嗅探）必须
     * 参与，否则出现"手动解析正常、超级解析失败"。任一成功即播放，其余结果作为备用地址。
     *
     * @return (JSON 解析站列表, 嗅探解析站列表)
     */
    private fun collectSuperParseBeans(flag: String): Pair<List<ParseBean>, List<ParseBean>> {
        val candidates = apiConfig.getParseBeanList().filter {
            it.getUrl().isNotEmpty() && it.getUrl() != "SuperParse"
        }
        return candidates.filter { it.type != ParseBean.TYPE_SNIFF } to
            candidates.filter { it.type == ParseBean.TYPE_SNIFF }
    }

    /**
     * 请求单个 JSON 解析接口，成功返回解析结果，失败返回 null。
     * 解析站返回 parse=1 / video:// 地址时，先嗅探出真实视频地址再返回。
     */
    private suspend fun requestJsonParse(
        bean: ParseBean,
        videoUrl: String
    ): PlayResolver.ResolveResult? {
        return try {
            // 与手动选择解析站（resolveByJson）一致：视频地址需编码后拼接，
            // 否则含中文/特殊字符时解析站请求 URL 非法，全部解析站失败导致超级解析失败
            val requestUrl = bean.getUrl() + encodeUrl(videoUrl)
            val response = okHttpHelper.get(requestUrl, buildHeaders(bean.ext))
            if (response.isEmpty()) {
                return null
            }
            val parsed = parseJsonResponse(response, videoUrl)
            if (parsed.error != null) {
                return null
            }
            // 解析站要求二次嗅探：嗅探出真实地址后再上报
            if (parsed.needSniff) {
                return resolveBySniffUrl(parsed.url, parsed.headers)
            }
            PlayResolver.ResolveResult(parsed.url, parsed.headers)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    /** 解析结果无来源名时补充解析站名称，保证播放成功后能提示来源 */
    private fun PlayResolver.ResolveResult.withSourceName(name: String?): PlayResolver.ResolveResult {
        if (sourceName != null || name.isNullOrBlank() || url.isEmpty()) return this
        return copy(sourceName = name)
    }

    /** 编码视频地址后拼接到解析站接口：整体编码避免地址内 query 截断解析站 url 参数 */
    private fun encodeUrl(url: String): String {
        if (url.isEmpty()) return url
        return try {
            java.net.URLEncoder.encode(url, "UTF-8")
        } catch (_: Throwable) {
            url
        }
    }

    /** 收集命中视频请求自带的 UA/Referer/Origin 头 + Cookie，缺失 Referer 时用解析页地址补充 */
    private fun buildHitHeaders(
        reqHeaders: Map<String, String>?,
        hitUrl: String,
        loadUrl: String
    ): Map<String, String> {
        val hitHeaders = HashMap<String, String>()
        if (reqHeaders != null) {
            for ((k, v) in reqHeaders) {
                if (k.equals("user-agent", true) ||
                    k.equals("referer", true) ||
                    k.equals("origin", true)
                ) {
                    if (v.isNotBlank()) hitHeaders[k] = v
                }
            }
        }
        // 命中时附带 WebView 已建立的 Cookie
        val cookie = CookieManager.getInstance().getCookie(hitUrl)
        if (!cookie.isNullOrEmpty()) hitHeaders["Cookie"] = cookie
        // 部分 CDN 防盗链校验 Referer，缺失时用解析页地址补充
        if (hitHeaders.keys.none { it.equals("referer", true) }) {
            hitHeaders["Referer"] = loadUrl
        }
        return hitHeaders
    }

    /**
     * 静音页面所有视频/音频元素，抑制嗅探时解析页自动播放的声音。
     * 只静音（muted）不暂停：muted 即时生效即可静音；pause 会中断页面 JS 的
     * play() 成功回调流程，可能影响依赖播放回调的解析站嗅探。
     * 500ms 轮询强制静音，兜底页面内反复重新播放的媒体。
     */
    private fun muteMedia(webView: WebView) {
        try {
            webView.evaluateJavascript(
                "(function(){" +
                    "function muteAll(doc){" +
                    "var a=doc.querySelectorAll('video,audio');" +
                    "for(var i=0;i<a.length;i++){try{a[i].muted=true;}catch(err){}}" +
                    "}" +
                    "document.addEventListener('play',function(e){try{var t=e.target;if(t){t.muted=true;}}catch(err){}},true);" +
                    "muteAll(document);" +
                    "try{for(var f=0;f<document.frames.length;f++){muteAll(document.frames[f].document);}}catch(err){}" +
                    "setInterval(function(){muteAll(document);try{for(var f=0;f<document.frames.length;f++){muteAll(document.frames[f].document);}}catch(err){}},500);" +
                    "})();",
                null
            )
        } catch (_: Throwable) {
        }
    }

    /** 是否为音频地址（解析页广告/BGM 音频自动播放的源头，返回空响应阻塞下载） */
    private fun isAudioUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("url=http") || lower.contains(".html")) return false
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()?.lowercase() ?: return false
        return path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") ||
            path.endsWith(".wav") || path.endsWith(".ogg") || path.endsWith(".opus") ||
            path.endsWith(".flac") || path.endsWith(".amr")
    }

    private suspend fun sniffWithWebView(
        loadUrl: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>>? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        val timeout = Runnable {
            // 先暂停 WebView（立即停止媒体播放/声音），再停止加载并销毁
            webView?.onPause()
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            if (cont.isActive) cont.resume(null)
        }
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 要求用户手势才能播放媒体：Chromium 原生阻止解析页自动播放（含跨域 iframe，
                // JS 静音无法穿透跨域 iframe），从源头消除嗅探杂音。
                // 不影响地址嗅探：设置 video src 即触发资源加载，shouldInterceptRequest 照常拦截
                settings.mediaPlaybackRequiresUserGesture = true
                // 默认 PC UA，headers 中显式 UA 优先
                settings.userAgentString = headers["User-Agent"] ?: AppConstants.UA_DESKTOP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                settings.blockNetworkImage = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError
                    ) {
                        // 放行证书错误，部分解析站证书不全
                        handler.proceed()
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // 静音页面自动播放的视频/音频，避免嗅探时解析站多路声音同时响起
                        muteMedia(view)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // 页面加载完成后再次注入（此时 DOM 完整，覆盖页面初始化后插入的媒体元素）
                        muteMedia(view)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        // shouldInterceptRequest 运行在 WebView IO 线程，禁止直接调用 WebView 方法，
                        // 否则抛 "A WebView method was called on thread" 崩溃；这里仅做字符串匹配，
                        // 命中后切回主线程再操作 WebView。
                        fun hitMedia(hitUrl: String, reqHeaders: Map<String, String>?): WebResourceResponse? {
                            val hitHeaders = buildHitHeaders(reqHeaders, hitUrl, loadUrl)
                            handler.removeCallbacks(timeout)
                            handler.post {
                                webView?.onPause()
                                webView?.stopLoading()
                                webView?.destroy()
                                webView = null
                                if (cont.isActive) cont.resume(hitUrl to hitHeaders)
                            }
                            // 返回空响应阻塞视频真实下载播放：仅取播放地址，不让解析页拉流出声
                            return emptyResponse()
                        }
                        val url = request.url.toString()
                        // 排除 url=http 与 .html 前缀，避免把解析页当视频
                        if (url.contains("url=http") || url.contains(".html")) return null
                        // 拦截音频请求：广告/BGM 音频自动播放是嗅探时杂音的来源，阻塞下载从源头静音
                        if (isAudioUrl(url)) return emptyResponse()
                        if (VideoParseRuler.checkIsVideoForParse(loadUrl, url) ||
                            DefaultConfig.isVideoFormat(url)
                        ) {
                            return hitMedia(url, request.requestHeaders)
                        }
                        return null
                    }
                }
                // 解析页加载时携带 ext 配置的请求头（UA 已设 settings，其余作 extra headers）
                val extraHeaders = headers.filterKeys { !it.equals("User-Agent", true) }
                if (extraHeaders.isNotEmpty()) {
                    loadUrl(loadUrl, extraHeaders)
                } else {
                    loadUrl(loadUrl)
                }
            }
            handler.postDelayed(timeout, 15_000L)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                webView?.onPause()
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        } catch (e: Throwable) {
            handler.removeCallbacks(timeout)
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * 超级解析嗅探（收集模式）：单个 WebView 加载聚合 iframe 页面
     * （ServerHostImpl 生成，每帧一个嗅探解析站错峰并行加载），
     * 拦截到的视频请求回调上报——命中不停止 WebView，继续收集后续 iframe 子页面结果，直至超时销毁。
     *
     * 关键约束（"6 个解析站 = 1 个竞速主地址 + 5 个备用地址"）：
     * - 解析站 iframe 页面会直接拉流，m3u8 播放列表与全部分片（.ts）都会被拦截；
     *   若不收敛，一次嗅探可拦截几十个分片地址，备用池被分片塞满。
     * - 分片地址跳过（单个分片无法播放），并按 CDN 域名去重：每个 CDN 只上报
     *   第一个命中地址（通常是 m3u8 播放列表），保证每个解析站只贡献一个地址。
     */
    private suspend fun sniffSuperWithWebView(
        loadUrl: String,
        jxNameMap: Map<String, String>,
        onHit: (url: String, headers: Map<String, String>, sourceName: String?) -> Unit
    ) = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        // 已上报的 CDN 域名（如 x-xxx.xxxxxx.com -> xxxxxx.com）：每域只取第一个命中
        val seenCdnDomains = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val timeout = Runnable {
            // 先暂停 WebView（立即停止媒体播放/声音），再停止加载并销毁
            webView?.onPause()
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            if (cont.isActive) cont.resume(Unit)
        }
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 要求用户手势才能播放媒体：阻止聚合页 iframe 内各解析站自动播放出声
                // （跨域 iframe 无法用 JS 静音，Chromium 手势限制是唯一可靠手段）
                settings.mediaPlaybackRequiresUserGesture = true
                // 聚合页多 iframe 共用同一 WebView，UA 全局统一（各站 ext 的 UA 无法逐帧生效）
                settings.userAgentString = AppConstants.UA_DESKTOP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                settings.blockNetworkImage = true
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError
                    ) {
                        handler.proceed()
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // 静音页面自动播放的视频/音频，避免超级解析聚合页多解析站同时出声
                        muteMedia(view)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        muteMedia(view)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        // shouldInterceptRequest 运行在 WebView IO 线程，只做纯字符串匹配，
                        // 命中后切回主线程回调上报，不停止加载继续收集
                        val url = request.url.toString()
                        // 排除 url=http 与 .html 前缀，避免把解析页/聚合页当视频
                        if (url.contains("url=http") || url.contains(".html")) return null
                        // 拦截音频请求：聚合页各解析站的广告/BGM 音频是杂音来源，阻塞下载
                        if (isAudioUrl(url)) return emptyResponse()
                        if (VideoParseRuler.checkIsVideoForParse(loadUrl, url) ||
                            DefaultConfig.isVideoFormat(url)
                        ) {
                            // 跳过 HLS 分片（单个分片无法播放，且不占用 CDN 域名名额，
                            // 保证该域第一个命中是 m3u8 播放列表等可播地址）
                            if (isHlsSegment(url)) return null
                            val host = extractHost(url)
                            val cdnDomain = host?.let { cdnDomainOf(it) }
                            if (cdnDomain == null || !seenCdnDomains.add(cdnDomain)) return null
                            val hitHeaders = buildHitHeaders(request.requestHeaders, url, loadUrl)
                            handler.post {
                                if (cont.isActive) {
                                    val sourceName = if (jxNameMap.isNotEmpty()) {
                                        findJxName(hitHeaders, jxNameMap)
                                    } else null
                                    onHit(url, hitHeaders, sourceName)
                                }
                            }
                            // 返回空响应阻塞视频真实下载播放：仅收集地址，不让解析站直接拉流出声
                            return emptyResponse()
                        }
                        return null
                    }
                }
                loadUrl(loadUrl)
            }
            handler.postDelayed(timeout, 15_000L)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                webView?.onPause()
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        } catch (e: Throwable) {
            handler.removeCallbacks(timeout)
            if (cont.isActive) cont.resume(Unit)
        }
    }

    /**
     * 反查超级解析命中来源：按请求头（Referer/Origin）的 URL 匹配解析站。
     * 解析站 iframe 直接发起视频请求时 Referer 为 iframe 完整地址（解析站URL+编码视频），
     * 前缀/host 匹配即可归属；二次跳转后 Referer 变为播放器地址，与解析站配置无映射则无法归属。
     */
    private fun findJxName(
        headers: Map<String, String>,
        jxNameMap: Map<String, String>
    ): String? {
        // Referer/Origin 为发起视频请求的页面地址，解析站页面直接发起时可命中
        for ((k, v) in headers) {
            if (k.equals("referer", true) || k.equals("origin", true)) {
                findJxNameByUrl(v, jxNameMap)?.let { return it }
            }
        }
        return null
    }

    /** 反查解析源名称：精确/前缀匹配优先，其次按 host（去端口）匹配 */
    private fun findJxNameByUrl(url: String, jxNameMap: Map<String, String>): String? {
        if (url.isEmpty() || jxNameMap.isEmpty()) return null
        val inputDomain = extractHost(url)?.substringBefore(':')
        for ((jxUrl, name) in jxNameMap) {
            if (jxUrl.isEmpty()) continue
            // 优先精确/前缀匹配：iframe 页面直接发起视频时 Referer 为「解析站URL+编码视频」
            if (url.equals(jxUrl, true) || url.startsWith(jxUrl)) return name
            // host 匹配：Referrer-Policy 将 Referer 截断为纯 origin 时仅剩 host
            val jxDomain = extractHost(jxUrl)?.substringBefore(':')
            if (inputDomain != null && jxDomain != null && jxDomain.equals(inputDomain, true)) return name
        }
        return null
    }

    /** 从 URL 中提取 host（含端口），忽略协议与前后空格 */
    private fun extractHost(url: String): String? {
        if (url.isEmpty()) return null
        val u = url.trim()
        val start = u.indexOf("://").let { if (it == -1) 0 else it + 3 }
        val end = u.indexOf('/', start)
        if (end == -1) {
            val len = u.length
            return if (start >= len) null else u.substring(start)
        }
        return if (start >= end) null else u.substring(start, end)
    }

    /**
     * 提取 CDN 域名（host 最后两段，忽略端口）：x-xxx.xxxxxx.com -> xxxxxx.com。
     * 同一 CDN 的视频地址每次请求可能落在不同子域（负载均衡），
     * 按 CDN 域名去重保证每个解析站只贡献一个播放地址（m3u8 先于分片命中，分片被去重挡掉）。
     */
    private fun cdnDomainOf(host: String): String {
        val noPort = host.substringBefore(':')
        val parts = noPort.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else noPort
    }

    /**
     * 是否为 .ts 分片地址（单个分片无法播放，超级解析收敛时跳过）。
     * 只过滤带明确分片特征的 .ts（/hls/、/seg/、/segment/、数字序号）；
     * 无后缀的 m3u8 播放列表不在此过滤范围（可能被域名去重误伤，需保留入池），
     * 其后续分片由"每 CDN 只取第一个命中（m3u8 先于分片）"的域名去重天然挡掉。
     */
    private fun isHlsSegment(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".ts") && (lower.contains("/hls/") || lower.contains("/seg/") ||
            lower.contains("/segment/") || Regex("\\d{6,}\\.ts(\\?|$)").containsMatchIn(lower))
    }

    /** 从 ext 字段解析请求头。
     * ext 为 JSON 对象字符串（{"header":{...}}），兼容 query 格式（key=value&key2=value2）。
     */
    private fun buildHeaders(ext: String): Map<String, String> {
        if (ext.isEmpty()) return emptyMap()
        // ext 为 JSON 对象字符串：{"header":{...}}
        return try {
            val extJson = JSONObject(ext)
            val headerJson = extJson.optJSONObject("header") ?: return emptyMap()
            val headers = HashMap<String, String>()
            val keys = headerJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headerJson.optString(key)
            }
            headers
        } catch (_: Throwable) {
            // 兼容 query 格式
            val headers = HashMap<String, String>()
            for (pair in ext.split("&")) {
                val idx = pair.indexOf("=")
                if (idx > 0) {
                    headers[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
                }
            }
            headers
        }
    }

    /**
     * 执行可能失败的可挂起调用：协程取消时向上抛出取消异常（不吞掉），
     * 其余异常转为失败，避免被取消的解析协程继续执行播放覆盖新请求。
     */
    private inline fun <T> runCatchingResume(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** JSON 解析响应中间结果：needSniff=true 表示地址还需 WebView 二次嗅探 */
    private data class JsonParseResult(
        val url: String,
        val headers: Map<String, String>?,
        val needSniff: Boolean,
        val error: String?
    )

    /**
     * 从 JSON 解析站响应提取播放信息：
     * - 支持 {"data":{...}} 嵌套结构（部分解析站把 url/parse/header 放在 data 段）；
     * - 支持 video:// 前缀（剥离前缀后标记需二次嗅探）；
     * - 支持 data:application 前缀（内嵌视频数据可直接播放）；
     * - 解析站返回 parse=1 时标记需 WebView 二次嗅探；
     * - 合并 data 段与顶层 header，并支持顶层 user-agent/referer 字段。
     * 响应为空、无 url、非 http 地址、
     * 或解析结果等于原始地址且非视频格式时判定解析失败返回 error。
     */
    private fun parseJsonResponse(
        response: String,
        fallbackUrl: String
    ): JsonParseResult {
        if (response.isEmpty()) return JsonParseResult("", null, false, context.getString(uiR.string.play_err_parse_site_no_response))
        return try {
            val jsonPlayData = JSONObject(response)
            val playData = jsonPlayData.optJSONObject("data") ?: jsonPlayData
            var resolvedUrl = playData.optString("url", jsonPlayData.optString("url", ""))
            if (resolvedUrl.isEmpty()) return JsonParseResult("", null, false, context.getString(uiR.string.play_err_missing_play_addr))
            if (resolvedUrl.startsWith("//")) resolvedUrl = "http:$resolvedUrl"
            var needSniff = false
            if (resolvedUrl.startsWith("video://")) {
                resolvedUrl = resolvedUrl.substring(8)
                needSniff = true
            }
            // proxy:// 前缀替换为本地代理地址：
            // 解析站返回 proxy://go=xxx 等本地代理格式地址时，必须换成
            // http://127.0.0.1:端口/proxy?go=xxx 才能请求到真实内容；
            // 仅剥离前缀会导致播放地址无效
            if (resolvedUrl.startsWith("proxy://")) {
                resolvedUrl = com.github.catvod.Proxy.getUrl(true) + "?" + resolvedUrl.removePrefix("proxy://")
            }
            if (!resolvedUrl.startsWith("http") && !resolvedUrl.startsWith("data:application")) {
                return JsonParseResult("", null, false, context.getString(uiR.string.play_err_invalid_play_addr))
            }
            needSniff = needSniff || playData.optInt("parse", jsonPlayData.optInt("parse", 0)) == 1
            // 解析站原样返回原始地址且非视频格式（且无需二次嗅探），视为解析失败
            if (resolvedUrl == fallbackUrl && !needSniff && !DefaultConfig.isVideoFormat(resolvedUrl)) {
                return JsonParseResult("", null, false, context.getString(uiR.string.play_err_no_addr_parsed))
            }
            JsonParseResult(resolvedUrl, getHeaders(jsonPlayData, playData), needSniff, null)
        } catch (_: Throwable) {
            // 服务器已响应但响应内容解析异常
            JsonParseResult("", null, false, context.getString(uiR.string.play_err_server_error))
        }
    }

    /** 合并解析站 data 段与顶层 header（支持 header/headers 字段及 user-agent/referer 平铺字段） */
    private fun getHeaders(top: JSONObject, data: JSONObject): Map<String, String>? {
        val headers = HashMap<String, String>()
        appendHeaders(headers, top.opt("header"))
        appendHeaders(headers, top.opt("headers"))
        if (data !== top) {
            appendHeaders(headers, data.opt("header"))
            appendHeaders(headers, data.opt("headers"))
        }
        // 顶层 user-agent/referer 字段（部分解析站直接平铺返回）
        val ua = data.optString("user-agent", top.optString("user-agent", "")).trim()
        if (ua.isNotEmpty()) headers["User-Agent"] = " $ua"
        val referer = data.optString("referer", top.optString("referer", "")).trim()
        if (referer.isNotEmpty()) headers["Referer"] = " $referer"
        return if (headers.isEmpty()) null else headers
    }

    /** 追加 header 字段（JSONObject 或 JSON 字符串）到目标 Map */
    private fun appendHeaders(headers: MutableMap<String, String>, raw: Any?) {
        if (raw == null || raw == JSONObject.NULL) return
        try {
            val json = when (raw) {
                is JSONObject -> raw
                is String -> {
                    val text = raw.trim()
                    if (text.isEmpty()) null else JSONObject(text)
                }
                else -> null
            } ?: return
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotEmpty()) headers[key] = json.optString(key, "")
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        /** 超级解析 JSON 并行渠道总超时 */
        const val SUPER_JSON_TIMEOUT_MS = 15_000L

        /** 创建空响应：阻塞视频真实下载播放，嗅探仅取播放地址不让解析页拉流出声 */
        fun emptyResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
