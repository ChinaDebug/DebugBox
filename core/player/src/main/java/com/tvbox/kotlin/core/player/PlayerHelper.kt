package com.tvbox.kotlin.core.player

import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kEffects
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器配置中枢。
 *
 * 内置内核恒定使用 Media3 直控（ExoPlayer），外部播放器（MX/Reex/Kodi）由业务层另行调起。
 * 播放前把渲染类型、渲染器模式与画面缩放应用到目标视图。
 */
@Singleton
class PlayerHelper @Inject constructor(
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "PlayerHelper"

        fun getPlayerName(playType: Int): String = when (playType) {
            PlayerConstants.PLAYER_TYPE_MEDIA3 -> "Media3"
            PlayerConstants.PLAYER_TYPE_MX -> "MX"
            PlayerConstants.PLAYER_TYPE_REEX -> "Reex"
            PlayerConstants.PLAYER_TYPE_KODI -> "Kodi"
            else -> "Media3"
        }

        fun getRenderName(renderType: Int): String =
            if (renderType == PlayerConstants.RENDER_SURFACE) "SurfaceView" else "TextureView"

        fun getScaleName(screenScaleType: Int): String = when (screenScaleType) {
            PlayerConstants.SCREEN_SCALE_16_9 -> "16:9"
            PlayerConstants.SCREEN_SCALE_4_3 -> "4:3"
            PlayerConstants.SCREEN_SCALE_MATCH_PARENT -> "填充"
            PlayerConstants.SCREEN_SCALE_ORIGINAL -> "原始"
            PlayerConstants.SCREEN_SCALE_CENTER_CROP -> "裁剪"
            else -> "默认"
        }

        /** 沿因果链向上追溯最多 10 层，返回根因信息 */
        fun getRootCauseMessage(th: Throwable): String {
            var cur = th
            for (i in 0 until 10) {
                val cause = cur.cause ?: return cur.localizedMessage ?: ""
                cur = cause
            }
            return cur.localizedMessage ?: ""
        }
    }

    /**
     * 应用播放配置到 [videoView]。
     *
     * @param videoView 目标视图（应用渲染类型与画面缩放）
     * @param playerCfg 播放器配置 JSON，包含 pl/sc 字段；为 null 时完全使用偏好
     * @param forcePlayerType 强制播放器类型，>=0 时覆盖配置
     * @param enableAnime4k 是否允许画面增强（直播源传 false：分辨率频繁变化，GL 链重建代价高）
     */
    @JvmOverloads
    fun updateCfg(
        videoView: MyVideoView,
        playerCfg: JSONObject? = null,
        forcePlayerType: Int = -1,
        enableAnime4k: Boolean = true
    ) {
        var playerType = PlayerConstants.normalizePlayType(
            appPreferences.get(PlayerConstants.KEY_PLAY_TYPE, PlayerConstants.PLAYER_TYPE_MEDIA3)
        )
        val renderType = appPreferences.get(PlayerConstants.KEY_PLAY_RENDER, PlayerConstants.RENDER_SURFACE)
        var scale = appPreferences.get(PlayerConstants.KEY_PLAY_SCALE, PlayerConstants.SCREEN_SCALE_DEFAULT)
        try {
            playerCfg?.let {
                playerType = PlayerConstants.normalizePlayType(it.optInt("pl", playerType))
                scale = it.optInt("sc", scale)
            }
        } catch (e: Exception) {
            com.tvbox.kotlin.core.common.LOG.e(TAG, "解析 playerCfg 失败", e)
        }
        if (forcePlayerType >= 0) playerType = PlayerConstants.normalizePlayType(forcePlayerType)

        // 渲染器模式须在创建 Media3 播放器前应用（EngineRenderersFactory 构建时读取）
        // 0=硬解优先、1=软解优先、2=仅硬解、3=仅软解
        PlayerEngineConfig.apply(appPreferences.get(AppConstants.MEDIA3_RENDERER_MODE, 0))

        // 渲染类型与画面缩放：由视图自行重建渲染层/调整比例容器
        videoView.setRenderType(renderType)
        videoView.setScreenScaleType(scale)

        // 画面增强：非直播源始终装配效果链，开启/关闭由效果内部按偏好决定直通或增强，
        // 避免切换开关时重建视频管线导致视频与弹幕重载
        videoView.videoEffects = if (enableAnime4k) {
            Anime4kEffects.build(videoView.context)
        } else {
            emptyList()
        }
    }
}
