package com.tvbox.kotlin.core.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Message
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.shuyu.gsyvideoplayer.cache.ICacheManager
import com.shuyu.gsyvideoplayer.model.GSYModel
import com.shuyu.gsyvideoplayer.model.VideoOptionModel
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

/**
 * 可配置的 Exo 内核管理类。
 * GSY 的 [Exo2PlayerManager.initVideoPlayer] 内部直接 new IjkExo2MediaPlayer（私有字段），
 * 无法注入子类，重写：改用 [OkHttpHlsExo2MediaPlayer] 并注入自定义渲染器工厂。
 * 渲染器模式（[applyConfig]，设置界面 0~3）：0=ON（硬解优先）、1=PREFER（软解优先）、
 * 2=OFF（仅硬解）、3=仅软解（仅 FFmpeg 视频渲染器）。
 */
class ConfigurableExo2PlayerManager : Exo2PlayerManager() {

    companion object {
        @Volatile
        private var rendererMode: Int = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON

        /** 仅软解标志：仅使用 FFmpeg 视频渲染器，禁用 MediaCodec 视频渲染器 */
        @Volatile
        private var softOnly: Boolean = false

        /** 在 [com.shuyu.gsyvideoplayer.player.PlayerFactory.setPlayManager] 前调用，initVideoPlayer 时读取 */
        fun applyConfig(rendererMode: Int) {
            softOnly = rendererMode == 3
            this.rendererMode = when (rendererMode) {
                1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                2 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
        }

        /** 当前是否仅硬解模式（设置 2=OFF）：音频无 FFmpeg 软解兜底，杜比/DTS 音轨会解码失败报 4001 */
        val isOnlyHardwareMode: Boolean
            get() = rendererMode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

        /** 杜比/DTS 家族音频 MIME：供仅硬解模式的音轨能力预检（该族格式硬件解码器声明支持但初始化可能失败） */
        fun isDolbyOrDts(mime: String?): Boolean = when (mime) {
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS -> true
            else -> false
        }
    }

    override fun initVideoPlayer(
        context: Context?,
        msg: Message?,
        optionModelList: MutableList<VideoOptionModel>?,
        cacheManager: ICacheManager?
    ) {
        val ctx = context ?: return
        val gsyModel = (msg?.obj as? GSYModel) ?: return
        if (cacheManager == null) return
        try {
            val player = OkHttpHlsExo2MediaPlayer(ctx)
            setField("context", ctx.applicationContext)
            setField("mediaPlayer", player)
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            if (getField("dummySurface") == null) {
                setField("dummySurface", PlaceholderSurface.newInstanceV17(ctx, false))
            }
            player.setLooping(gsyModel.isLooping())
            player.setPreview(gsyModel.getMapHeadData() != null && gsyModel.getMapHeadData().size > 0)
            if (gsyModel.isCache() && cacheManager != null) {
                cacheManager.doCacheLogic(ctx, player, gsyModel.getUrl(), gsyModel.getMapHeadData(), gsyModel.getCachePath())
            } else {
                player.setCache(gsyModel.isCache())
                player.setCacheDir(gsyModel.getCachePath())
                player.setOverrideExtension(gsyModel.getOverrideExtension())
                player.setDataSource(ctx, android.net.Uri.parse(gsyModel.getUrl()), gsyModel.getMapHeadData())
            }
            if (player.getMediaSource() == null) {
                release()
                return
            }
            if (gsyModel.getSpeed() != 1f && gsyModel.getSpeed() > 0f) {
                player.setSpeed(gsyModel.getSpeed(), 1f)
            }
            initSuccess(gsyModel)
            // prepare 前配置渲染器 factory，prepareAsyncInternal 读取
            player.setRendererFactory(FfmpegFirstRenderersFactory(ctx.applicationContext, rendererMode, softOnly))
        } catch (e: Throwable) {
            com.tvbox.kotlin.core.common.LOG.e(
                "ConfigurableExo2PlayerManager", "initVideoPlayer 失败", e
            )
            release()
        }
    }

    private fun setField(name: String, value: Any?) {
        val f = Exo2PlayerManager::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(this, value)
    }

    private fun getField(name: String): Any? {
        val f = Exo2PlayerManager::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(this)
    }

    /**
     * 渲染器工厂：渲染器顺序由 rendererMode 决定（对应设置 0=ON 硬解优先、1=PREFER 软解优先、
     * 2=OFF 仅硬解）；softOnly（设置 3=仅软解）时移除全部 MediaCodec 渲染器，仅保留 FFmpeg；
     * 其余模式将默认 MediaCodec 渲染器替换为带编码 profile 校验的版本。
     */
    private class FfmpegFirstRenderersFactory(
        context: Context,
        rendererMode: Int,
        private val softOnly: Boolean
    ) : DefaultRenderersFactory(context) {

        init {
            // 开启解码器回退，并应用用户选择的渲染器模式
            setEnableDecoderFallback(true)
            setExtensionRendererMode(rendererMode)
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
            // 仅软解：移除全部 MediaCodec 视频渲染器，只保留 FFmpeg 软解渲染器
            if (softOnly) {
                out.removeAll { it is MediaCodecVideoRenderer }
                return
            }
            // 将默认 MediaCodec 渲染器替换为带 profile 校验的版本（硬解/软解优先共用）
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
            super.buildAudioRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                audioSink, eventHandler, eventListener, out
            )
            // 非仅硬解（ON/PREFER/仅软解）：音频只保留 FFmpeg 软解渲染器（单渲染器）。
            // 若 MediaCodec 与 Ffmpeg 并存，杜比/DTS 组会被分给 Ffmpeg、普通组分给 MediaCodec，
            // 切换音轨即跨音频渲染器，media3 不允许两个音频渲染器同时激活；
            // 统一软解（音频开销极小、音质无损）换取切换稳定，杜比/DTS 天然软解不报 4001
            if (extensionRendererMode != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) {
                out.removeAll { it is MediaCodecAudioRenderer }
            }
        }
    }

    /**
     * 带 profile 校验的 MediaCodec 视频渲染器：
     * 硬件解码器不支持当前编码 profile（如 HEVC Main10 10bit）时返回不支持，
     * 轨道选择器据此改用 FFmpeg 软解，避免硬解黑屏。
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

        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
            val support = super.supportsFormat(mediaCodecSelector, format)
            val supportMask = support and RendererCapabilities.FORMAT_SUPPORT_MASK
            if (supportMask == C.FORMAT_UNSUPPORTED_TYPE ||
                supportMask == C.FORMAT_UNSUPPORTED_SUBTYPE ||
                supportMask == C.FORMAT_UNSUPPORTED_DRM
            ) {
                return support
            }
            // 仅按 profile 校验硬件解码器：硬件不含该 profile 时返回不支持，回落 FFmpeg 软解；
            // 不校验 level/帧率/尺寸，避免能力报告不完整导致误判。
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
