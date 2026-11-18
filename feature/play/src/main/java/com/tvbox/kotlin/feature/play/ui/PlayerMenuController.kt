package com.tvbox.kotlin.feature.play.ui

import androidx.media3.common.C
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.player.thirdparty.Kodi
import com.tvbox.kotlin.core.player.thirdparty.MXPlayer
import com.tvbox.kotlin.core.player.thirdparty.ReexPlayer
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.SelectDialog

/**
 * 控制栏菜单与投屏控制器，屏蔽 PlayFragment/PlayActivity 的宿主差异。
 *
 * 差异收敛点：context 用 [PlayHost.context]，横竖屏切换与内核选择后的重播
 * 通过 [onRestartPlay] 回调交给宿主装配（内部持有 PlaySession）。
 */
class PlayerMenuController(private val host: PlayHost) {

    /** 当前弹出的锚点菜单（倍速/屏显），关闭时恢复控制栏自动隐藏 */
    private var currentPopup: PlayerPopupMenu? = null

    /** 切换播放器内核后重新装配并播放的回调（宿主装配时绑定 session.startPlayUrl） */
    var onRestartPlay: (() -> Unit)? = null

    /** 显示播放器选择对话框，选择内核后重新装配并播放 */
    fun showPlayerSelectDialog() {
        // Media3 恒可用；MX/Reex/Kodi 检测到安装后动态加入选项
        val players = buildList {
            add(PlayerConstants.PLAYER_TYPE_MEDIA3)
            if (MXPlayer.isInstalled(host.context)) add(PlayerConstants.PLAYER_TYPE_MX)
            if (ReexPlayer.isInstalled(host.context)) add(PlayerConstants.PLAYER_TYPE_REEX)
            if (Kodi.isInstalled(host.context)) add(PlayerConstants.PLAYER_TYPE_KODI)
        }
        val current = host.viewModel.playerType.value
        val selectIdx = players.indexOf(current).takeIf { it >= 0 } ?: 0
        SelectDialog(
            host.context,
            host.context.getString(CoreUiR.string.dia_player),
            players,
            { type -> PlayerHelper.getPlayerName(type) },
            select = selectIdx
        ) { type, _ ->
            if (type != host.viewModel.playerType.value) {
                host.viewModel.switchPlayer(type)
                // 切换内核后重新装配并播放
                onRestartPlay?.invoke()
            }
        }.show()
    }

    /** 显示音轨选择对话框（Exo 内核），切换后按进度键记忆 */
    fun showAudioTrackDialog() {
        PlayerMenuHelper.showAudioTrackDialog(host.context, host.videoView, host.viewModel.getProgressKey())
    }

    /** 显示视轨选择对话框（Exo 内核） */
    fun showVideoTrackDialog() {
        PlayerMenuHelper.showVideoTrackDialog(host.context, host.videoView)
    }

    /**
     * 刷新控制栏音轨/视轨按钮显隐（onPrepared 时调用）：
     * 仅当对应轨道多于 1 条时显示图标，单轨道自动隐藏。
     * 杜比双层 MKV 的增强层被合并后视轨数为 1，不会意外显示视轨图标。
     */
    fun refreshTrackIcons() {
        val hasPlayer = PlayerMenuHelper.getExoPlayer(host.videoView) != null
        val audioCount = if (hasPlayer) PlayerMenuHelper.buildExoAudioTracks(host.videoView).size else 0
        val videoCount = if (hasPlayer) PlayerMenuHelper.buildExoVideoTracks(host.videoView).size else 0
        host.controller.setAudioTrackVisible(audioCount > 1)
        host.controller.setVideoTrackVisible(videoCount > 1)
    }

    /** 显示屏显设置锚点菜单 */
    fun showScreenDisplayMenu() {
        if (currentPopup?.isShowing() == true) {
            currentPopup?.dismiss()
            currentPopup = null
            return
        }
        currentPopup = PlayerMenuHelper.showScreenDisplayMenu(host.context, host.controller)
        currentPopup?.setOnDismissListener {
            currentPopup = null
            host.controller.resumeAutoHide()
        }
    }

    /** 横竖屏切换（横屏→竖屏，竖屏→横屏） */
    fun switchOrientation() {
        val activity = host.activity ?: return
        PlayerMenuHelper.switchOrientation(activity, host.controller)
    }

    /** 分辨率补刷次数：真实尺寸常在 PREPARED/首帧后才就绪，有限次重试避免轮询 */
    private var resolutionRetry = 0

    /** 更新左上角分辨率显示与横竖屏切换按钮；videoSize 未就绪时用轨道信息兜底并有限重试 */
    fun updateResolution() {
        val size = host.videoView.videoSize ?: readFormatSize()
        if (size != null && size[0] > 0 && size[1] > 0) {
            resolutionRetry = 0
            host.controller.setResolution("${size[0]} x ${size[1]}")
            host.controller.updateOrientationButton(size[0], size[1])
        } else if (resolutionRetry < 3) {
            resolutionRetry++
            host.controller.postDelayed({ updateResolution() }, 500)
        }
    }

    /** 从轨道信息读取首个视频 Format 的宽高，videoSize 未就绪时兜底 */
    private fun readFormatSize(): IntArray? {
        val mapped = host.videoView.trackSelector?.currentMappedTrackInfo ?: return null
        for (renderIndex in 0 until mapped.rendererCount) {
            if (mapped.getRendererType(renderIndex) != C.TRACK_TYPE_VIDEO) continue
            val groups = mapped.getTrackGroups(renderIndex)
            for (groupIndex in 0 until groups.length) {
                val group = groups.get(groupIndex)
                for (trackIndex in 0 until group.length) {
                    val format = group.getFormat(trackIndex)
                    if (format.width > 0 && format.height > 0) {
                        return intArrayOf(format.width, format.height)
                    }
                }
            }
        }
        return null
    }

    /** 从偏好读取屏显配置并应用到控制器右上角容器 */
    fun applyScreenDisplay() {
        PlayerMenuHelper.applyScreenDisplay(host.context, host.controller)
    }

    /** 显示投屏设备对话框：点击投屏图标打开弹窗时即暂停本地播放，选择设备投屏后保持暂停，直接关闭则恢复 */
    fun showCastDialog() {
        // 打开弹窗即暂停本地播放（用户可在设备列表选择目标设备）
        if (host.videoView.isPlaying) {
            host.videoView.pause()
        }
        host.viewModel.startCastDiscovery()
        var castSent = false
        val dialog = PlayCastDialog(host.context, host.viewModel.castDevices, host.viewModel.castScanning)
        dialog.refreshCallback = { host.viewModel.startCastDiscovery() }
        dialog.cancelCallback = { host.viewModel.stopCastDiscovery() }
        dialog.onDeviceSelected = { device ->
            castSent = true
            host.viewModel.castCurrentToDevice(device)
        }
        dialog.setOnDismissListener {
            host.viewModel.stopCastDiscovery()
            // 未选择设备投屏（直接关闭弹窗）时恢复本地播放；已投屏保持暂停
            if (!castSent && host.videoView.playState == PlayerConstants.STATE_PAUSED) {
                host.videoView.start()
            }
        }
        dialog.show()
    }
}
