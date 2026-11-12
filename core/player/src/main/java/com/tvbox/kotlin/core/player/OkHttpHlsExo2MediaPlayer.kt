package com.tvbox.kotlin.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import tv.danmaku.ijk.media.exo2.ExoSourceManager
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import java.util.concurrent.TimeUnit

/**
 * 用 OkHttp 做 HLS 数据源的 Exo 内核。
 *
 * 部分源的分片 302 到 CDN（http→https 跨协议），OkHttp 默认允许跨协议重定向且
 * 请求特征正常；GSY 默认的 DefaultHttpDataSource 需额外元数据头并会被服务器拒绝。
 * 非 HLS 类型走 GSY 原逻辑。坏分片由上游 M3u8Purifier 净化移除，
 * 此处注入 [HlsTolerantLoadErrorHandlingPolicy] 兜底净化无法覆盖的间歇性 404。
 *
 * 磁力本地流（BtStreamServer 的 /bt?bt=1，边下边播）单独套用宽松读/连超时：
 * 慢速做种源首个数据块可能要远超默认 8s 才就绪，按默认超时会误判失败；
 * 该识别只针对磁力标识 URL，不影响普通视频与音乐的默认超时。
 */
class OkHttpHlsExo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {

    override fun setDataSource(context: Context, uri: Uri) {
        mDataSource = uri.toString()
        // 磁力本地流标识：BtStreamServer 生成 /bt?bt=1，需宽松超时等待边下边播的数据块
        val isBtLocal = uri.getQueryParameter(BT_LOCAL_FLAG) == "1"
        mMediaSource = when {
            ExoSourceManager.inferContentType(uri, overrideExtension) == C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(okHttpFactory(mHeaders))
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(HlsTolerantLoadErrorHandlingPolicy())
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            isBtLocal -> {
                // 宽松超时 OkHttp 数据源 + 默认媒体源工厂（自动探测 mp4/mkv/音频容器）
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(okHttpFactory(mHeaders, BT_LOCAL_TIMEOUT_SECONDS))
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            else -> mExoHelper.getMediaSource(mDataSource, isPreview, isCache, isLooping, mCacheDir, overrideExtension)
        }
    }

    /**
     * 构造 OkHttp 数据源工厂：过滤 GSY 元数据头（控制重定向开关，不能作为实际请求头发送），
     * User-Agent 单独设置；[timeoutSeconds] 控制读/连超时，磁力本地流用更长值。
     */
    private fun okHttpFactory(headers: Map<String, String>, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OkHttpDataSource.Factory {
        val cleanHeaders = headers.filterKeys { !it.equals("allowCrossProtocolRedirects", true) }
        val ua = cleanHeaders["User-Agent"]
        val restHeaders = cleanHeaders.filterKeys { !it.equals("User-Agent", true) }
        val client = OK_HTTP_CLIENT.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return OkHttpDataSource.Factory(client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(restHeaders)
    }

    private companion object {
        /** 磁力本地流查询标识（与 BtStreamServer.playUrl 对齐） */
        const val BT_LOCAL_FLAG = "bt"

        /** 普通 HLS 的读/连超时（秒） */
        const val DEFAULT_TIMEOUT_SECONDS = 15L

        /** 磁力本地流读/连超时（秒）：边下边播需等待慢源首个数据块，远大于普通源 */
        const val BT_LOCAL_TIMEOUT_SECONDS = 120L

        val OK_HTTP_CLIENT = okhttp3.OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
