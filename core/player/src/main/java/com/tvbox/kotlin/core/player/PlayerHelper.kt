package com.tvbox.kotlin.core.player

import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器工厂与配置中枢。
 *
 * 系统播放内核已彻底移除，内置内核恒定装配 Exo（Media3），
 * 外部播放器（MX/Reex/Kodi）由业务层另行调起；扩展新内核时在此追加装配。
 */
@Singleton
class PlayerHelper @Inject constructor(
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "PlayerHelper"

        fun getPlayerName(playType: Int): String = when (playType) {
            PlayerConstants.PLAYER_TYPE_EXO -> "Exo"
            PlayerConstants.PLAYER_TYPE_MX -> "MX"
            PlayerConstants.PLAYER_TYPE_REEX -> "Reex"
            PlayerConstants.PLAYER_TYPE_KODI -> "Kodi"
            else -> "Exo"
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
     * 应用 GSY 全局播放配置到 [videoView]。
     *
     * @param videoView 目标视图（用于应用画面缩放）
     * @param playerCfg 播放器配置 JSON，包含 pl/sc 字段；为 null 时完全使用偏好
     * @param forcePlayerType 强制播放器类型，>=0 时覆盖配置
     */
    @JvmOverloads
    fun updateCfg(
        videoView: MyVideoView,
        playerCfg: JSONObject? = null,
        forcePlayerType: Int = -1
    ) {
        var playerType = PlayerConstants.normalizePlayType(
            appPreferences.get(PlayerConstants.KEY_PLAY_TYPE, PlayerConstants.PLAYER_TYPE_EXO)
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

        // 装配播放内核：系统内核已移除，恒定装配 GSY Exo(Media3)；
        // 上述 playerType 归一结果（偏好/pl/强制）不再参与内核选择，仅为兼容历史档位保留。
        // 渲染器模式偏好须在 setPlayManager 前应用（ConfigurableExo2PlayerManager.initVideoPlayer 时读取）
        // 0=硬解优先（MediaCodec 优先，平台不支持的格式走 FFmpeg 软解兜底）
        ConfigurableExo2PlayerManager.applyConfig(
            appPreferences.get(AppConstants.EXO_RENDERER_MODE, 0)
        )
        PlayerFactory.setPlayManager(ConfigurableExo2PlayerManager::class.java)

        // 渲染类型：GSY 仅 Texture/Surface 两类，其余回落 TextureView
        GSYVideoType.setRenderType(
            if (renderType == PlayerConstants.RENDER_SURFACE) {
                GSYVideoType.SURFACE
            } else {
                GSYVideoType.TEXTURE
            }
        )

        // 画面缩放
        videoView.setScreenScaleType(scale)
    }
}
