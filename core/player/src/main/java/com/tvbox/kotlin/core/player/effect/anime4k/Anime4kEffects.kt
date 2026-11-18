package com.tvbox.kotlin.core.player.effect.anime4k

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.exoplayer.ExoPlayer
import com.tvbox.kotlin.core.player.MyVideoView

/**
 * Anime4K 效果入口：按偏好构建 Media3 视频效果链。
 *
 * 关闭时返回空列表；HDR 源由链内自动降级为直通（不对 HDR 做 SDR 域增强）。
 * 直播源由调用方决定不启用（分辨率频繁变化时重建 GL 链代价高）。
 */
object Anime4kEffects {

    fun build(context: Context): List<Effect> {
        Anime4kSettings.load(context)
        val effects = ArrayList<Effect>()
        // 画面调节常驻：参数全默认时 shader 内部直接直通，但保持效果链非空。
        // 一旦下发空列表，Media3 会销毁并重建渲染管线，切换预设时会黑屏 1~2 秒
        effects.add(PictureColorEffect())
        if (Anime4kSettings.enabled()) {
            effects.add(Anime4kEffect().apply { tier = Anime4kTier.current(context) })
        } else {
            Anime4kStatus.onDisabled()
        }
        return effects
    }

    /**
     * 按当前偏好重建效果链并应用到播放器。
     * 暂停态不产生新帧，重建后需借缓存帧重跑一次才能看到变化。
     */
    fun applyTo(videoView: MyVideoView) {
        val effects = build(videoView.context)
        videoView.videoEffects = effects
        val player = videoView.exoPlayer ?: return
        player.setVideoEffects(effects)
        redrawPausedFrame(player)
    }

    /** 暂停时让渲染器用缓存帧重跑一遍效果链，立即呈现参数变化 */
    fun redrawPausedFrame(player: ExoPlayer) {
        if (player.isPlaying || player.playbackState != Player.STATE_READY) return
        runCatching { player.setVideoEffects(VideoFrameProcessor.REDRAW) }
    }
}
