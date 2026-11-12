package com.tvbox.kotlin.feature.play.ui

import android.widget.Toast
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.ui.R as uiR

/**
 * 控制栏监听器公共实现，屏蔽 PlayFragment/PlayActivity 的宿主差异。
 *
 * 参数化差异点：
 * - [onPauseBarShow]：Activity 在暂停分支额外 mController.showPauseBar()，Fragment 为 null；
 * - [onSpeedLongPress]/[onSpeedLongPressRelease]：Activity 的长按临时快进逻辑，Fragment 为 null；
 * - [playSuccessListener]：Fragment 在播放成功/错误/完成时通知详情页，Activity 为 null；
 * - [onSelectEpisodeAction]：Activity 显示选集弹窗，Fragment 弹提示。
 */
class ControllerListenerDelegate(
    private val host: PlayHost,
    private val danmuController: DanmuController,
    private val subtitleController: SubtitleController,
    private val menuController: PlayerMenuController,
    private val session: PlaySession
) : PlayerVodControlView.VodControlListener {

    /** Activity: { mController.showPauseBar() }；Fragment: null */
    var onPauseBarShow: (() -> Unit)? = null

    /** Activity: 长按倍速临时最大档位快进；Fragment: null */
    var onSpeedLongPress: (() -> Boolean)? = null

    /** Activity: 长按松手恢复原速；Fragment: null */
    var onSpeedLongPressRelease: (() -> Unit)? = null

    /** Fragment: 播放成功状态回调（播放/错误/完成时通知宿主详情页）；Activity: null */
    var playSuccessListener: ((Boolean) -> Unit)? = null

    /** Fragment: 切集回调（上一集/下一集/自动连播成功后通知详情页同步集数选中状态）；Activity: null */
    var onEpisodeChanged: ((flag: String, episodeIndex: Int) -> Unit)? = null

    /** Activity: showEpisodeDialog；Fragment: Toast 提示 */
    var onSelectEpisodeAction: (() -> Unit)? = null

    override fun onPlayPause() {
        if (host.videoView.isPlaying) {
            // 暂停后部分内核立即回收解码器，先取位置避免读取失真
            val position = host.videoView.currentPosition
            host.videoView.pause()
            host.userPaused = true
            // 暂停即持久化进度，避免进程/界面重建时恢复位置落后于周期保存
            val url = session.currentPlayUrl
            if (position > 0 && url.isNotEmpty()) {
                host.viewModel.saveProgress(url, position)
            }
            // Activity 主动显示右上角暂停条（绕过部分内核状态回调时序导致不显示的问题）
            onPauseBarShow?.invoke()
        } else {
            host.videoView.start()
            host.userPaused = false
        }
    }

    override fun onSeekTo(position: Long) {
        host.videoView.seekTo(position)
        // 拖动/快进快退后立即落进度，避免短时退出丢 seek 结果
        val url = session.currentPlayUrl
        if (url.isNotEmpty()) {
            host.viewModel.saveProgress(url, position)
        }
    }

    override fun onPlayNext() {
        val url = host.viewModel.playNext()
        if (url.isNullOrEmpty()) {
            // 边界提示检测正倒序
            val reverse = host.viewModel.vodInfo.value?.reverseSort == true
            Toast.makeText(
                host.context,
                if (reverse) host.context.getString(uiR.string.ep_bound_reverse_first) else host.context.getString(uiR.string.ep_bound_asc_last),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // 进度键每集独立（源key+影片id+线路+集id），新集自然恢复自身进度
            session.loadAndPlay(url)
            notifyEpisodeChanged()
        }
    }

    override fun onPlayPrevious() {
        val url = host.viewModel.playPrevious()
        if (url.isNullOrEmpty()) {
            // 边界提示检测正倒序
            val reverse = host.viewModel.vodInfo.value?.reverseSort == true
            Toast.makeText(
                host.context,
                if (reverse) host.context.getString(uiR.string.ep_bound_reverse_last) else host.context.getString(uiR.string.ep_bound_asc_first),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // 进度键每集独立，新集自然恢复自身进度
            session.loadAndPlay(url)
            notifyEpisodeChanged()
        }
    }

    override fun onSpeedClick() {
        menuController.showPlaySpeedMenu()
    }

    override fun onSpeedLongPress(): Boolean {
        return onSpeedLongPress?.invoke() ?: false
    }

    override fun onSpeedLongPressRelease() {
        onSpeedLongPressRelease?.invoke()
    }

    override fun onScaleClick() {
        // 缩放模式显示在按钮文本上，无需额外提示
        host.viewModel.cycleScale()
    }

    override fun onPlayerClick() {
        menuController.showPlayerSelectDialog()
    }

    override fun onSubtitleClick() {
        subtitleController.showSubtitleDialog()
    }

    override fun onDanmuClick() {
        danmuController.showDanmuSettingDialog()
    }

    override fun onAudioClick() {
        menuController.showAudioTrackDialog()
    }

    override fun onVideoClick() {
        menuController.showVideoTrackDialog()
    }

    override fun onCastClick() {
        menuController.showCastDialog()
    }

    override fun onScreenDisplayClick() {
        menuController.showScreenDisplayMenu()
    }

    override fun onOrientationClick() {
        menuController.switchOrientation()
    }

    override fun onRetryClick() {
        session.retry()
    }

    override fun onSelectEpisode() {
        onSelectEpisodeAction?.invoke()
    }

    override fun onParseChanged(parse: ParseBean) {
        host.viewModel.changeParse(parse)
    }

    override fun onBackPressed(): Boolean = false

    override fun onPrepared() {
        subtitleController.initSubtitle()
        menuController.updateResolution()
        // 刷新控制栏音轨/视轨图标：仅对应轨道多于 1 条时显示，单轨道隐藏；
        // 杜比双层 MKV 合并后视轨数为 1，不显示视轨图标
        menuController.refreshTrackIcons()
        // 恢复记忆的音轨：重新播放同一集自动沿用上次选择的音轨（无记忆/记忆失效保持默认）
        PlayerMenuHelper.loadDefaultTrack(host.context, host.videoView, host.viewModel.getProgressKey())
    }

    override fun onPlaying() {
        // 播放成功，取消超时检测
        session.cancelBackupSwitchCheck()
        // 直接隐藏加载提示（不走 StateFlow：playState 重复为 PLAYING 时 StateFlow 不发射，
        // 换线路/换集后会残留"正在加载"转圈）
        host.hideTip()
        // 已恢复播放，清除手动暂停标记
        host.userPaused = false
        host.viewModel.updatePlayState(PlayState.PLAYING)
        // 视频真正播放后才加载弹幕，避免弹幕加载影响视频；自动加载不弹"请稍等"提示
        danmuController.loadDanmuWhenPlaying(showToast = false)
        // 解析来源提示：仅真正播放成功后提示，播放失败不提示（避免误报）
        session.consumeParseSource()?.let {
            Toast.makeText(host.context, host.context.getString(uiR.string.play_parse_from_fmt, it), Toast.LENGTH_SHORT).show()
        }
        // Fragment 通知详情页播放成功
        playSuccessListener?.invoke(true)
    }

    override fun onError(message: String) {
        session.cancelBackupSwitchCheck()
        host.viewModel.updatePlayState(PlayState.ERROR)
        // Fragment 在错误处理前通知详情页（顺序保持一致）
        playSuccessListener?.invoke(false)
        session.handlePlayError(message)
    }

    override fun onCompletion() {
        session.cancelBackupSwitchCheck()
        host.viewModel.updatePlayState(PlayState.COMPLETED)
        playSuccessListener?.invoke(false)
        // 播放完成清除当前集进度缓存：避免下次播放该集恢复临近结尾立即播完切集
        host.viewModel.clearProgress(session.currentPlayUrl)
        // 播放完成自动下一集：进度键每集独立，新集自然恢复自身进度
        val url = host.viewModel.playNext()
        if (!url.isNullOrEmpty()) {
            session.loadAndPlay(url)
            notifyEpisodeChanged()
        }
    }

    /** 切集成功后通知宿主（详情页）同步集数选中状态 */
    private fun notifyEpisodeChanged() {
        val info = host.viewModel.vodInfo.value ?: return
        val flag = info.playFlag ?: return
        onEpisodeChanged?.invoke(flag, info.playEpisodeIndex)
    }
}
