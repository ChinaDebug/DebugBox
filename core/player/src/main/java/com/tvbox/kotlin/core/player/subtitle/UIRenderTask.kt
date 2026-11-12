package com.tvbox.kotlin.core.player.subtitle

import android.os.Handler
import android.os.Looper
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle

/**
 * 字幕 UI 渲染任务。将字幕切换事件投递到主线程触发回调，
 * 保证视图更新始终在主线程执行，避免并发渲染。
 */
class UIRenderTask(listener: SubtitleEngine.OnSubtitleChangeListener?) : Runnable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var subtitle: Subtitle? = null
    private var listener: SubtitleEngine.OnSubtitleChangeListener? = listener

    /** 更新监听器，引擎在 setOnSubtitleChangeListener 时同步 */
    fun updateListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        mainHandler.removeCallbacks(this)
        this.listener = listener
    }

    /** 设置当前字幕并投递到主线程执行 */
    fun execute(subtitle: Subtitle?) {
        this.subtitle = subtitle
        mainHandler.removeCallbacks(this)
        mainHandler.post(this)
    }

    override fun run() {
        listener?.onSubtitleChanged(subtitle)
    }
}
