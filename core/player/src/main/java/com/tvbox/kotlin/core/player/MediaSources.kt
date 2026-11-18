package com.tvbox.kotlin.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import com.tvbox.kotlin.core.player.iso.IsoDataSource
import com.tvbox.kotlin.core.player.iso.IsoExtractorsFactory
import com.tvbox.kotlin.core.player.iso.IsoPlaylistState
import com.tvbox.kotlin.core.player.iso.IsoThroughput
import com.tvbox.kotlin.core.player.iso.M2tsDataSource
import com.tvbox.kotlin.core.player.iso.withPlaylistTargets
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Media3 媒体源工厂：按扩展名/URL 特征选择 HLS、DASH 或渐进式容器。
 *
 * Http(s) 源统一使用 OkHttp 数据源（请求头可控、默认允许跨协议重定向）；
 * 本地与自定义 scheme 源交由默认工厂处理，避免 OkHttp 不支持 file/content scheme。
 * 磁力边下边播（[BT_LOCAL_FLAG]=1）使用宽松读超时等待慢速做种源首个数据块。
 */
object MediaSources {

    /** 磁力本地流查询标识（与 BtStreamServer.playUrl 对齐） */
    private const val BT_LOCAL_FLAG = "bt"

    /** 普通网络源读/连超时（秒） */
    private const val DEFAULT_TIMEOUT_SECONDS = 15L

    /** 磁力边下边播读/连超时（秒）：需等待慢源首个数据块，远大于普通源 */
    private const val BT_LOCAL_TIMEOUT_SECONDS = 120L

    private val HTTP_CLIENT: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** ISO 探测与读取的读超时更宽松：BD 原盘单次读取数据量大，慢链路易触发短超时 */
    private val ISO_HTTP_CLIENT: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 推断媒体扩展名（m3u8/mpd/null）。
     * 显式覆盖优先；路径已带标准后缀时依赖默认推断；无后缀但 URL 含协议特征时强制指定，
     * 避免 Exo 按路径推断失败后按普通文件探测。
     */
    fun inferExtension(url: String, overrideExtension: String?): String? {
        overrideExtension?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        if (url.startsWith("data:")) return null
        val lower = url.lowercase()
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase()
        if (path != null) {
            if (path.endsWith(".m3u8")) return "m3u8"
            if (path.endsWith(".mpd")) return "mpd"
        }
        return when {
            lower.contains("m3u8") || lower.contains("type=hls") || lower.contains("format=hls") -> "m3u8"
            lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd") ||
                lower.contains("type=dash") || lower.contains("format=dash") -> "mpd"
            else -> null
        }
    }

    /** 构建媒体源：[headers] 为业务层请求头，[overrideExtension] 为强制扩展名（可为空） */
    fun create(
        context: Context,
        url: String,
        headers: Map<String, String>,
        overrideExtension: String?
    ): MediaSource {
        val uri = Uri.parse(url)
        val ext = inferExtension(url, overrideExtension)
        if (isIsoSource(url, headers, ext)) {
            return createIsoMediaSource(context, url, headers)
        }
        if (!isNetworkUrl(url)) {
            return DefaultMediaSourceFactory(context).createMediaSource(MediaItem.fromUri(uri))
        }
        val btLocal = uri.getQueryParameter(BT_LOCAL_FLAG) == "1"
        return when {
            ext == "m3u8" -> HlsMediaSource.Factory(okHttpFactory(headers))
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(HlsTolerantLoadErrorHandlingPolicy())
                .createMediaSource(MediaItem.fromUri(uri))
            ext == "mpd" -> DashMediaSource.Factory(okHttpFactory(headers))
                .createMediaSource(MediaItem.fromUri(uri))
            btLocal -> DefaultMediaSourceFactory(context)
                .setDataSourceFactory(okHttpFactory(headers, BT_LOCAL_TIMEOUT_SECONDS))
                .createMediaSource(MediaItem.fromUri(uri))
            else -> DefaultMediaSourceFactory(context)
                .setDataSourceFactory(okHttpFactory(headers))
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    /** ISO/BDMV 判定：扩展名、ISO 标记头、路径后缀任一命中 */
    private fun isIsoSource(url: String, headers: Map<String, String>, ext: String?): Boolean {
        if (ext == "iso") return true
        if (headers.keys.any { it.equals(HttpHeaders.HEADER_ISO, true) }) return true
        return url.substringBefore('?').substringBefore('#').endsWith(".iso", true)
    }

    /**
     * ISO 源：解出主播放列表后按顺序拼接分段。
     *
     * 播放列表解析在首个数据源打开时才完成，第二个及后续分段待选择回调到达后追加，
     * 避免准备阶段阻塞主线程。
     */
    private fun createIsoMediaSource(
        context: Context,
        url: String,
        headers: Map<String, String>
    ): MediaSource {
        IsoThroughput.beginSession()
        IsoPlaylistState.reset()
        val uri = Uri.parse(url).let {
            if (it.scheme == null) Uri.fromFile(File(url)) else it
        }
        val mediaItem = MediaItem.fromUri(uri)
        val upstream = isoUpstreamFactory(context, headers)
        val concatRef = AtomicReference<ConcatenatingMediaSource>()
        val appended = AtomicBoolean(false)
        val first = isoProgressiveSource(
            mediaItem,
            IsoDataSource.factory(upstream, 0L) { selection ->
                if (!selection.multiSegment) return@factory
                if (!appended.compareAndSet(false, true)) return@factory
                val concat = concatRef.get() ?: return@factory
                val children = selection.withPlaylistTargets().drop(1).map { segment ->
                    isoProgressiveSource(
                        mediaItem,
                        IsoDataSource.factory(upstream, segment)
                    )
                }
                concat.addMediaSources(children)
            }
        )
        val concat = ConcatenatingMediaSource(
            false, true, ShuffleOrder.DefaultShuffleOrder(0), first
        )
        concatRef.set(concat)
        return concat
    }

    /** ISO 上游数据源：OkHttp 处理网络，DefaultDataSource 兜底 file/content scheme */
    private fun isoUpstreamFactory(
        context: Context,
        headers: Map<String, String>
    ): DataSource.Factory =
        DefaultDataSource.Factory(context, okHttpDataSource(headers, ISO_HTTP_CLIENT))

    /** ISO 分段源：ISO 数据源按 UDF 区块读取，再由 M2TS 适配器按 192 字节包输出 */
    private fun isoProgressiveSource(
        mediaItem: MediaItem,
        isoFactory: IsoDataSource.Factory
    ): MediaSource = ProgressiveMediaSource.Factory(
        M2tsDataSource.Factory(isoFactory),
        IsoExtractorsFactory()
    ).createMediaSource(mediaItem)

    private fun isNetworkUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /**
     * 构造 OkHttp 数据源工厂：[timeoutSeconds] 控制读/连超时。
     */
    private fun okHttpFactory(
        headers: Map<String, String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): OkHttpDataSource.Factory {
        val client = if (timeoutSeconds == DEFAULT_TIMEOUT_SECONDS) {
            HTTP_CLIENT
        } else {
            HTTP_CLIENT.newBuilder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
        }
        return okHttpDataSource(headers, client)
    }

    /** User-Agent 单独设置，内部标记头由 [HttpHeaders.toRequestHeaders] 过滤 */
    private fun okHttpDataSource(
        headers: Map<String, String>,
        client: okhttp3.OkHttpClient
    ): OkHttpDataSource.Factory {
        val requestHeaders = HttpHeaders.toRequestHeaders(headers)
        val ua = requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val restHeaders = requestHeaders.filterKeys { !it.equals("User-Agent", true) }
        return OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(restHeaders)
    }
}
