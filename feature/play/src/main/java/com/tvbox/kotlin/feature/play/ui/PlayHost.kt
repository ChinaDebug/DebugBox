package com.tvbox.kotlin.feature.play.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView
import kotlinx.coroutines.CoroutineScope
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 播放宿主抽象：屏蔽 Fragment/Activity 差异，供弹幕/字幕/菜单/播放核心复用。
 *
 * 差异收敛点：
 * - [context]/[activity]：Fragment 为 requireContext()/requireActivity()，Activity 为 this；
 * - [scope]：Fragment 为 viewLifecycleOwner.lifecycleScope，Activity 为 lifecycleScope；
 * - [isAlive]：Fragment 为 isAdded，Activity 为 !isFinishing && !isDestroyed；
 * - [postOnUi]：Fragment 为 view?.post，Activity 为 runOnUiThread。
 */
interface PlayHost {
    val context: Context
    val activity: Activity?
    val videoView: MyVideoView
    val controller: PlayerVodControlView
    val viewModel: PlayViewModel
    val mainHandler: Handler
    val scope: CoroutineScope
    val danmakuView: DanmakuView?
    val subtitleView: SimpleSubtitleView?

    /** 用户手动暂停标志：手动暂停后切后台不自动恢复播放；后台自动暂停/音频焦点丢失暂停则回前台自动恢复 */
    var userPaused: Boolean

    /** 宿主是否存活（Activity: !isFinishing && !isDestroyed；Fragment: isAdded） */
    fun isAlive(): Boolean

    fun showTip(message: String, loading: Boolean, error: Boolean)
    fun hideTip()

    /** 切回主线程执行（Activity: runOnUiThread；Fragment: view?.post） */
    fun postOnUi(runnable: () -> Unit)
}
