package com.tvbox.kotlin.core.player.subtitle

import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle

/**
 * 字幕引擎统一接口，定义字幕加载、刷新、生命周期与回调。
 */
interface SubtitleEngine {

    /** 设置字幕路径并触发加载（本地或远程） */
    fun setSubtitlePath(path: String?)

    /** 设置字幕整体偏移（毫秒），正数延后、负数提前 */
    fun setSubtitleDelay(milliseconds: Int)

    /** 设置当前播放项的字幕缓存键，用于记忆字幕路径 */
    fun setPlaySubtitleCacheKey(cacheKey: String?)

    fun getPlaySubtitleCacheKey(): String?

    /** 开启字幕刷新任务 */
    fun start()

    /** 暂停刷新 */
    fun pause()

    /** 恢复刷新 */
    fun resume()

    /** 停止刷新 */
    fun stop()

    /** 重置：停止刷新并清空已加载字幕 */
    fun reset()

    /** 销毁：释放线程与字幕资源 */
    fun destroy()

    /** 绑定播放器，刷新任务据此读取当前位置 */
    fun bindToPlayer(mediaPlayer: MyVideoView?)

    fun setOnSubtitlePreparedListener(listener: OnSubtitlePreparedListener?)

    fun setOnSubtitleChangeListener(listener: OnSubtitleChangeListener?)

    fun setOnSubtitleLoadListener(listener: OnSubtitleLoadListener?)

    /** 字幕准备完成回调 */
    fun interface OnSubtitlePreparedListener {
        fun onSubtitlePrepared(subtitles: List<Subtitle>?)
    }

    /** 字幕切换回调 */
    fun interface OnSubtitleChangeListener {
        fun onSubtitleChanged(subtitle: Subtitle?)
    }

    /** 字幕加载状态回调 */
    interface OnSubtitleLoadListener {
        fun onSuccess()
        fun onError(error: String)
    }
}
