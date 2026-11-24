package com.tvbox.kotlin.core.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.tvbox.kotlin.core.common.LOG

/**
 * 直控内核渲染器配置（设置项 0~3）。
 * 0=硬解优先、1=软解优先、2=仅硬解、3=仅软解；
 * 在创建 ExoPlayer 前调用 [apply]，[EngineRenderersFactory] 构建时读取。
 */
object PlayerEngineConfig {

    const val MODE_HARDWARE_FIRST = 0
    const val MODE_SOFTWARE_FIRST = 1
    const val MODE_HARDWARE_ONLY = 2
    const val MODE_SOFTWARE_ONLY = 3

    @Volatile
    private var mode: Int = MODE_HARDWARE_FIRST

    fun apply(rendererMode: Int) {
        // 备份恢复/旧版本可能写入越界档位，统一归一到已知模式，避免落入未知分支
        mode = rendererMode.coerceIn(MODE_HARDWARE_FIRST, MODE_SOFTWARE_ONLY)
    }

    val currentMode: Int get() = mode

    /** 仅软解：移除全部 MediaCodec 渲染器 */
    val isSoftwareOnly: Boolean get() = mode == MODE_SOFTWARE_ONLY
}

/**
 * 渲染器工厂：按 [PlayerEngineConfig] 装配硬解/软解渲染器。
 *
 * 视频：MediaCodec 渲染器带编码 profile 校验（硬解不支持时回落软解）；
 * 软解渲染器为本模块自编译 FFmpeg（类名与官方扩展不同，需显式加入）。
 * 音频：与视频解码模式解耦，全部模式优先 FFmpeg 软解；原生库不可用时保留硬解兜底。
 */
internal class EngineRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    private companion object {
        const val TAG = "EngineRenderersFactory"
    }

    private val softOnly = PlayerEngineConfig.isSoftwareOnly

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(
            when (PlayerEngineConfig.currentMode) {
                PlayerEngineConfig.MODE_SOFTWARE_FIRST -> EXTENSION_RENDERER_MODE_PREFER
                PlayerEngineConfig.MODE_HARDWARE_ONLY -> EXTENSION_RENDERER_MODE_OFF
                else -> EXTENSION_RENDERER_MODE_ON
            }
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out
        )
        // 自编译 FFmpeg 视频渲染器：官方扩展名反射加载不到，需显式创建
        val ffmpegVideo = if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF &&
            FfmpegLibrary.isAvailable()
        ) {
            try {
                ExperimentalFfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs, eventHandler, eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                )
            } catch (t: Throwable) {
                LOG.w(TAG, "FFmpeg 视频渲染器创建失败：${t.message}")
                null
            }
        } else {
            null
        }

        if (softOnly) {
            // 仅软解完全依赖 FFmpeg：不可用时保留硬解，避免完全没有画面
            if (ffmpegVideo != null) {
                out.removeAll { it is MediaCodecVideoRenderer }
            } else {
                LOG.w(TAG, "FFmpeg 视频软解不可用，仅软解模式回退硬解")
            }
        } else {
            // 默认 MediaCodec 渲染器替换为带 profile 校验的版本
            val idx = out.indexOfFirst { it is MediaCodecVideoRenderer }
            if (idx >= 0) {
                out.removeAt(idx)
                out.add(
                    idx,
                    CapabilityAwareMediaCodecVideoRenderer(
                        context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                        eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                    )
                )
            }
        }
        if (ffmpegVideo != null) {
            // 软解优先插到硬解之前，其余模式追加在后
            val insertAt = if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
                out.indexOfFirst { it is MediaCodecVideoRenderer }.takeIf { it >= 0 } ?: out.size
            } else {
                out.size
            }
            out.add(insertAt, ffmpegVideo)
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // 音频固定装配扩展渲染器（FFmpeg 软解），不能沿用工厂的 OFF：
        // 仅硬解模式只约束视频，若音频也关闭扩展将无软解兜底，杜比/DTS 音轨会无声
        super.buildAudioRenderers(
            context, EXTENSION_RENDERER_MODE_ON, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out
        )
        // 反射创建成功不代表可解码：原生库缺失时 supportsFormat 恒为不支持，
        // 此时必须保留 MediaCodec 音频兜底，避免所有格式完全无声
        val ffmpegAudioReady = out.any { it is FfmpegAudioRenderer } && FfmpegLibrary.isAvailable()
        if (ffmpegAudioReady) {
            out.removeAll { it is MediaCodecAudioRenderer }
        } else {
            LOG.w(TAG, "FFmpeg 音频软解不可用，音频回退硬解（杜比/DTS 音轨可能无声）")
        }
    }

    /**
     * 带 profile 校验的 MediaCodec 视频渲染器：
     * 硬件解码器不含当前编码 profile（如 HEVC Main10 10bit）时返回不支持，
     * 轨道选择器据此改用 FFmpeg 软解，避免硬解黑屏。
     *
     * 同时开启可重放缓存：保留上一帧，画面参数变更后可用
     * [androidx.media3.effect.VideoFrameProcessor.REDRAW] 重跑效果链，
     * 暂停状态下也能立刻看到画质变化（对标 AVBox ReplayableCacheVideoRenderer）。
     */
    private class CapabilityAwareMediaCodecVideoRenderer(
        context: Context,
        mediaCodecSelector: MediaCodecSelector,
        allowedJoiningTimeMs: Long,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int
    ) : MediaCodecVideoRenderer(
        context, mediaCodecSelector, allowedJoiningTimeMs,
        eventHandler, eventListener, maxDroppedFramesToNotify
    ) {

        override fun createPlaybackVideoGraphWrapper(
            context: Context,
            videoFrameReleaseControl: VideoFrameReleaseControl
        ): PlaybackVideoGraphWrapper =
            PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
                .setEnablePlaylistMode(true)
                .setClock(clock)
                .setEnableReplayableCache(true)
                .build()

        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
            val support = super.supportsFormat(mediaCodecSelector, format)
            val supportMask = support and RendererCapabilities.FORMAT_SUPPORT_MASK
            if (supportMask == C.FORMAT_UNSUPPORTED_TYPE ||
                supportMask == C.FORMAT_UNSUPPORTED_SUBTYPE ||
                supportMask == C.FORMAT_UNSUPPORTED_DRM
            ) {
                return support
            }
            // 仅按 profile 校验硬件解码器，不校验 level/帧率/尺寸，避免能力报告不完整导致误判；
            // 排除软件解码器（OMX.google.* 声明所有 profile，会掩盖硬件能力不足）
            return try {
                val mimeType = format.sampleMimeType
                val profileLevel = MediaCodecUtil.getCodecProfileAndLevel(format)
                if (mimeType == null || profileLevel == null || profileLevel.first == Format.NO_VALUE) {
                    support
                } else {
                    val profile = profileLevel.first
                    val infos = MediaCodecUtil.getDecoderInfos(mimeType, false, false)
                    if (infos.any { info ->
                            !info.softwareOnly && info.getProfileLevels().any { it.profile == profile }
                        }) {
                        support
                    } else {
                        RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                    }
                }
            } catch (_: Throwable) {
                support
            }
        }
    }
}
