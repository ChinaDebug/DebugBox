package com.tvbox.kotlin.core.player

import android.view.Surface

/**
 * 统一播放器抽象接口。
 *
 * Exo 与 System 两套实现均实现本接口，向控制层屏蔽底层差异。
 * 接口方法仅描述业务层关心的能力，底层生命周期由 [MyVideoView]
 * （GSYVideoPlayer 封装）统一驱动，实现类需保证空安全与线程安全。
 */
interface IPlayer {

    /** 开始播放 */
    fun play()

    /** 暂停播放 */
    fun pause()

    /** 跳转到指定位置（毫秒） */
    fun seekTo(positionMs: Long)

    /** 释放播放器资源 */
    fun release()

    /** 绑定渲染 Surface，传 null 解绑 */
    fun setSurface(surface: Surface?)

    /** 视频总时长（毫秒），未准备完成返回 0 */
    fun getDuration(): Long

    /** 当前播放位置（毫秒） */
    fun getCurrentPosition(): Long

    /** 设置播放倍速，1f 为正常速度 */
    fun setSpeed(speed: Float)

    /** 设置画面缩放类型，取值参考 [PlayerConstants] 的 SCREEN_SCALE_* 常量 */
    fun setScale(scale: Int)

    /** 选择指定轨道，trackId 由 [TrackInfo] 提供；传 [TrackInfo.INVALID_TRACK_ID] 表示关闭对应轨道 */
    fun selectTrack(trackId: Int)

    /** 是否正在播放 */
    fun isPlaying(): Boolean
}
