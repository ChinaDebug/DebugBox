package com.tvbox.kotlin.core.player

/**
 * 播放器模块常量集中定义。
 * 包含播放器类型、渲染类型、缩放类型以及偏好键名，
 * 键名与 HawkConfig 保持一致以便配置迁移。
 */
object PlayerConstants {

    // 播放器类型（设置点播首选映射统一：2 Exo/3 MX/4 Reex/5 Kodi；0/1 旧档位归一为 Exo）
    const val PLAYER_TYPE_EXO = 2
    const val PLAYER_TYPE_MX = 3
    const val PLAYER_TYPE_REEX = 4
    const val PLAYER_TYPE_KODI = 5

    /**
     * 归一化历史播放器类型值：旧档位 0(系统)/1(历史 IJK) 已随系统内核移除，
     * 一律归一为 Exo，其余值原样返回。
     */
    fun normalizePlayType(type: Int): Int = if (type in 0..1) PLAYER_TYPE_EXO else type

    // 渲染类型
    const val RENDER_TEXTURE = 0
    const val RENDER_SURFACE = 1

    // 播放状态机（业务层沿用历史状态数值语义）
    const val STATE_ERROR = -1
    const val STATE_IDLE = 0
    const val STATE_PREPARING = 1
    const val STATE_PREPARED = 2
    const val STATE_PLAYING = 3
    const val STATE_PAUSED = 4
    const val STATE_BUFFERING = 5
    const val STATE_BUFFERED = 6
    const val STATE_PLAYBACK_COMPLETED = 7
    const val STATE_START_ABORT = 8

    // 画面缩放类型（数值与 GSYVideoType.SCREEN_TYPE_* 保持一致，可直接映射）
    const val SCREEN_SCALE_DEFAULT = 0
    const val SCREEN_SCALE_16_9 = 1
    const val SCREEN_SCALE_4_3 = 2
    const val SCREEN_SCALE_MATCH_PARENT = 3
    const val SCREEN_SCALE_ORIGINAL = 4
    const val SCREEN_SCALE_CENTER_CROP = 5

    // 偏好键名（便于 AppPreferences 统一存取）
    const val KEY_PLAY_TYPE = "play_type"
    const val KEY_PLAY_RENDER = "play_render"
    const val KEY_PLAY_SCALE = "play_scale"
}
