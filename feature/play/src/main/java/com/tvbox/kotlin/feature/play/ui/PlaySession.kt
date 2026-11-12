package com.tvbox.kotlin.feature.play.ui

import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.player.thirdparty.Kodi
import com.tvbox.kotlin.core.player.thirdparty.MXPlayer
import com.tvbox.kotlin.core.player.thirdparty.ReexPlayer
import com.tvbox.kotlin.core.ui.R as uiR
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 播放核心控制器：解析、装配、超时检测、错误重试，屏蔽 PlayFragment/PlayActivity 的宿主差异。
 *
 * 行为差异：
 * - [playWithPurify]：解析成功后的净化播放入口，两宿主均装配（广告净化），
 *   仅提示实现（显示/隐藏与移除广告条数反馈）由各宿主各自完成；
 * - [startPlayUrl] 统一用 [PlayHost.postOnUi] 包裹并加 [PlayHost.isAlive] 守卫；
 * - 进度刷新任务不在此管理，保留在各自宿主中。
 */
class PlaySession(
    private val host: PlayHost,
    private val danmuController: DanmuController
) {

    companion object {
        /** 备用地址快速切换超时（毫秒）：当前地址 10s 内未播放成功即切换下一个备用地址。
         * 超时过短会把能播的地址误判为坏地址切走 */
        private const val BACKUP_SWITCH_TIMEOUT_MS = 10000L
        /** 备用池为空但备用解析仍在进行时的延迟重试间隔（毫秒） */
        private const val BACKUP_WAIT_DELAY_MS = 3000L
    }

    /** 当前播放 URL，用于重试 */
    var currentPlayUrl: String = ""
    /** 当前播放请求头 */
    var currentHeaders: Map<String, String>? = null

    /** 净化前的原始播放地址（复制/投屏/推送用）：净化成功走本地代理时 currentPlayUrl 为 127.0.0.1/m3u8，
     *  外部设备无法访问本机回环地址，需用此原始地址；净化失败回退原地址时两者一致 */
    var originalPlayUrl: String = ""
    /** 原始播放地址对应的请求头 */
    var originalHeaders: Map<String, String>? = null

    /** 解析任务 */
    private var loadJob: Job? = null

    /** 切集解析中锁定进度保存：避免旧播放器位置经 saveProgress 错写入新集进度键 */
    @Volatile
    var progressSaveLocked = false

    /** 最近一次播放错误信息：备用延迟重试时重放原始错误 */
    private var lastPlayError: String = ""

    /** 等待备用地址重试任务：备用解析仍在进行、备用池暂空时延迟后再取，重放原始错误走完整失败判定 */
    private val backupWaitRunnable = Runnable {
        handlePlayError(lastPlayError.ifEmpty { host.context.getString(uiR.string.play_err_video_error) })
    }

    /** 播放超时检测任务，卡加载超时后尝试切换备用地址 */
    private var backupSwitchRunnable: Runnable? = null

    /** 解析成功后的净化播放入口（PlayActivity 装配时传入 playWithPurify；PlayFragment 为 null 直接播放） */
    var playWithPurify: ((String, Map<String, String>?) -> Unit)? = null

    /** 待播放成功后提示的解析来源（仅真正播放成功才提示，播放失败不提示） */
    private var pendingParseSource: String? = null

    /** 消费待提示的解析来源（onPlaying 播放成功回调时调用），消费后清除 */
    fun consumeParseSource(): String? {
        val source = pendingParseSource
        pendingParseSource = null
        return source
    }

    /**
     * 通过解析器加载播放内容并开始播放。
     * 快速切换线路/集数时取消上一次解析，避免并发结果互相覆盖。
     */
    fun loadAndPlay(episodeUrl: String) {
        loadJob?.cancel()
        // 切集解析中锁定进度保存：避免旧播放器位置经 saveProgress 错写入新集进度键
        progressSaveLocked = true
        // 换集：先强制取消上一集弹幕加载，避免解析期间旧集弹幕继续加载/残留
        danmuController.cancelLoad()
        // 换集/换线路：先重置播放状态，清掉旧集残留的 PLAYING，
        // 避免弹幕兜底判定（checkDanmu 用应用层播放状态）在解析期间误加载旧集弹幕
        host.viewModel.updatePlayState(PlayState.IDLE)
        // 换集/换线路：取消上一集残留的超时检测与重试任务，避免旧播放器错误回调覆盖新集提示
        cancelBackupSwitchCheck()
        host.mainHandler.removeCallbacks(backupWaitRunnable)
        pendingParseSource = null
        host.showTip("正在解析播放地址", true, false)
        loadJob = host.scope.launch {
            try {
                val resolved = host.viewModel.loadAndResolve(episodeUrl)
                if (resolved.url.isEmpty()) {
                    // 解析失败：释放旧播放器并保持失败提示，防止旧播放器错误回调（如"视频播放出错"）
                    // 延迟到达后覆盖"所有解析站均失败"等正确提示
                    host.postOnUi {
                        if (!host.isAlive()) return@postOnUi
                        runCatching { host.videoView.release() }
                    }
                    host.showTip(resolved.error ?: "解析失败", false, true)
                    // 解析失败：无新播放器切换，直接解锁进度保存
                    progressSaveLocked = false
                    return@launch
                }
                // 记录解析来源，待播放成功后提示
                pendingParseSource = resolved.parseSource
                // 解析栏显隐：内容需要解析器，或用户已手动切换过解析器（_currentParse 非空）时保持显示。
                // 避免选择解析站后（即使本次解析失败回退直链播放）解析栏隐藏、无法再换解析站；
                // 存在可用解析器时才显示，避免空解析列表展示。
                val userPickedParse = host.viewModel.currentParse.value != null
                host.controller.showParse(
                    (resolved.needParse || userPickedParse) && host.viewModel.parseBeans.value.isNotEmpty()
                )
                // 净化播放入口由宿主实现，两模式均走广告净化
                if (playWithPurify != null) {
                    playWithPurify?.invoke(resolved.url, resolved.headers)
                } else {
                    startPlayUrl(resolved.url, resolved.headers)
                }
                // 解析完成后注入弹幕数据，弹幕在视频进入播放状态后由 onPlaying 加载。
                // 排队到 startPlayUrl 的 UI 队列之后执行，避免其异步重设地址前误判旧视频仍播放而多余加载
                host.postOnUi {
                    danmuController.checkDanmu()
                }
            } catch (e: Throwable) {
                // 解析异常：确保解锁，避免后续进度保存被永久锁定
                progressSaveLocked = false
                throw e
            }
        }
    }

    /**
     * 启动播放指定 URL。
     * 释放旧播放器，按当前配置装配新播放器并开始播放。
     * 统一用 [PlayHost.postOnUi] 切主线程并加 [PlayHost.isAlive] 存活守卫。
     * 第三方播放器（MX/Reex/Kodi，类型 >= [PlayerConstants.PLAYER_TYPE_MX]）直接调起外部应用，
     * 不走 GSY 装配与超时检测。
     */
    fun startPlayUrl(url: String, headers: Map<String, String>?) {
        if (!host.isAlive()) return
        if (url.isEmpty()) {
            host.showTip(host.context.getString(uiR.string.play_err_no_play_addr), false, true)
            return
        }
        currentPlayUrl = url
        currentHeaders = headers
        // 播放器实际装配目标集：后续保存进度键绑定该集，避免解析期间旧播放器位置错写新集键
        host.viewModel.markPlaybackStarted()
        // 净化后的本地代理地址（127.0.0.1/m3u8）不覆盖原始地址：
        // 复制/投屏/推送需用代理前的真实地址，否则外部设备无法访问本机 127.0.0.1
        val isLocalProxy = url.startsWith("http://127.0.0.1")
        if (!isLocalProxy) {
            originalPlayUrl = url
            originalHeaders = headers
        }
        host.viewModel.setPlayUrl(if (isLocalProxy) originalPlayUrl else url)

        // 第三方播放器：调用外部应用播放，不装配 GSY、不启动卡加载超时检测
        val playerType = host.viewModel.playerType.value
        if (playerType >= PlayerConstants.PLAYER_TYPE_MX) {
            host.showTip("正在加载", true, false)
            host.postOnUi {
                if (!host.isAlive()) return@postOnUi
                try {
                    host.videoView.release()
                    danmuController.cancelLoad()
                    val name = PlayerHelper.getPlayerName(playerType)
                    host.showTip("调用外部播放器${name}进行播放", true, false)
                    val ok = launchExternalPlayer(playerType, url, headers)
                    host.showTip("调用外部播放器${name}${if (ok) "成功" else "失败"}", ok, !ok)
                } catch (e: Throwable) {
                    host.showTip("播放失败：${e.message}", false, true)
                } finally {
                    // 旧播放器已释放/外部播放器已调起，解锁进度保存
                    progressSaveLocked = false
                }
            }
            return
        }

        host.showTip("正在加载", true, false)
        host.postOnUi {
            try {
                host.videoView.release()
                // release 不回调 IDLE，换集需显式重置跳过标记，否则跳过设置不再生效
                host.controller.resetSkipApplied()
                // 释放后弹幕视图已销毁，换代取消旧加载，等待新的播放状态回调重新加载
                danmuController.cancelLoad()
                // 装配内核以 ViewModel 解析结果为准（会话内临时指定/设置偏好），
                // 避免详情页下发的 playerCfg.pl 残留覆盖
                applyPlayerConfig(forcePlayerType = host.viewModel.playerType.value)
                host.videoView.startProgressLoop()
                // 起始位置：显式传入优先（详情页断点/投屏进度），否则从进度缓存恢复（断点续播）；
                // 手动"下一集"/自动连播已删除旧集进度并置位跳过恢复，避免固定 progressKey 误恢复旧集进度
                val start = host.viewModel.consumeStartPosition()
                val skipRestore = host.viewModel.consumeSkipProgressRestore()
                val restore = if (skipRestore) 0L else if (start > 0) start else host.viewModel.getSavedProgress(url)
                // 通知控制视图本次装配的起始位置：恢复进度越过片头时不再自动跳片头，
                // 避免退出重进/断点续播后进度被片头自动跳过拉回片头位置
                host.controller.setStartPosition(restore)
                if (restore > 0) {
                    // 起始位置交由 MyVideoView 在准备完成后跳转，避免 prepare 前 seekTo 被内核丢弃
                    host.videoView.setUrl(url, headers ?: emptyMap(), restore)
                    host.videoView.start()
                } else {
                    if (headers != null) {
                        host.videoView.setUrl(url, headers)
                    } else {
                        host.videoView.setUrl(url)
                    }
                    host.videoView.start()
                }
            } catch (e: Throwable) {
                host.showTip("播放失败：${e.message}", false, true)
            } finally {
                // 新播放器已装配，旧播放器已释放，解锁进度保存
                progressSaveLocked = false
            }
        }
        startBackupSwitchCheck()
    }

    // ------------------------------------------------------------------
    // 第三方播放器（MX/Reex/Kodi）
    // ------------------------------------------------------------------

    /** 调起第三方播放器播放当前地址，返回是否成功（标题拼接"影片名 : 剧集名"） */
    private fun launchExternalPlayer(type: Int, url: String, headers: Map<String, String>?): Boolean {
        val activity = host.activity ?: return false
        val title = host.viewModel.title.value
        val episodeName = host.viewModel.currentEpisode.value?.name
        val playTitle = if (episodeName.isNullOrEmpty()) title else "$title : $episodeName"
        val subtitle = host.viewModel.getSubtitle()
        val hdrs = headers ?: emptyMap()
        return when (type) {
            PlayerConstants.PLAYER_TYPE_MX -> MXPlayer.run(activity, url, playTitle, subtitle, hdrs)
            PlayerConstants.PLAYER_TYPE_REEX -> ReexPlayer.run(activity, url, playTitle, subtitle, hdrs)
            PlayerConstants.PLAYER_TYPE_KODI -> Kodi.run(activity, url, playTitle, subtitle, hdrs)
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 播放超时检测
    // ------------------------------------------------------------------

    /**
     * 启动备用快速切换超时：有备用地址或备用仍在收集中时，
     * 5s 内未播放成功即切换下一个备用地址（快速换源，处理"地址不能播"）。
     * 无备用不启动——卡加载等由播放错误回调处理。
     * 播放成功进入 onPlaying 时取消检测，不影响正常播放。
     */
    fun startBackupSwitchCheck() {
        cancelBackupSwitchCheck()
        if (!host.viewModel.hasBackupUrls() && !host.viewModel.hasPendingBackup()) return
        val runnable = Runnable {
            if (!host.isAlive()) return@Runnable
            handlePlayError("播放超时")
        }
        backupSwitchRunnable = runnable
        host.mainHandler.postDelayed(runnable, BACKUP_SWITCH_TIMEOUT_MS)
    }

    /** 取消备用快速切换超时检测 */
    fun cancelBackupSwitchCheck() {
        backupSwitchRunnable?.let { host.mainHandler.removeCallbacks(it) }
        backupSwitchRunnable = null
    }

    /** 应用播放器配置到 MyVideoView */
    fun applyPlayerConfig(forcePlayerType: Int) {
        try {
            host.viewModel.applyPlayerConfig(host.videoView, forcePlayerType)
            // 按钮右下角角标仅显示内核名（来源在播放器选择弹窗的选中项角标展示）
            host.controller.setPlayerText(PlayerHelper.getPlayerName(host.viewModel.playerType.value))
        } catch (_: Throwable) {
            // 配置应用失败忽略
        }
    }

    /** 应用倍速到当前解码器 */
    fun applySpeed(speed: Float) {
        try {
            host.videoView.setSpeed(speed)
        } catch (_: Throwable) {
            // 倍速设置失败忽略
        }
    }

    // ------------------------------------------------------------------
    // 错误重试
    // ------------------------------------------------------------------

    /**
     * 处理播放错误：
     * - 依次尝试解析时竞速收集的备用地址（快速换源），全部失败才提示失败；
     * - 备用池为空但备用解析仍在进行时延迟后重取；
     * - 其余错误一律直接显示，交由用户手动重试。
     */
    fun handlePlayError(message: String) {
        lastPlayError = message
        // 备用地址机制：解析时竞速收集的其他解析站地址依次尝试，
        // 加载提示不变，全部备用地址失败后才提示失败
        // 跳过与当前播放地址相同的备用项（同一站的首个地址可能与主地址相同，
        // 主地址播放失败后池里出现同地址重复项，再播一次无意义）
        var backup = host.viewModel.takeNextBackupUrl()
        while (backup != null && backup.url == currentPlayUrl) {
            backup = host.viewModel.takeNextBackupUrl()
        }
        if (backup != null && backup.url.isNotEmpty()) {
            pendingParseSource = backup.sourceName
            host.showTip("正在加载", true, false)
            cancelBackupSwitchCheck()
            // 启用净化时备用地址同样走净化：主地址已净化，若备用地址不净化直接播放会因坏分片再次失败，导致备用全部落空
            if (playWithPurify != null) {
                playWithPurify?.invoke(backup.url, backup.headers)
            } else {
                startPlayUrl(backup.url, backup.headers)
            }
            return
        }
        // 备用池暂无地址但其他解析站仍在解析中：延迟后重试取备用，
        // 避免首个地址失败时备用尚未就绪就立即提示失败
        if (host.viewModel.hasPendingBackup()) {
            host.showTip("正在加载", true, false)
            cancelBackupSwitchCheck()
            host.mainHandler.removeCallbacks(backupWaitRunnable)
            host.mainHandler.postDelayed(backupWaitRunnable, BACKUP_WAIT_DELAY_MS)
            return
        }
        // 其余错误（地址/网络/解析/播放器问题）一律直接显示，交由用户手动重试
        host.showTip(message, false, true)
    }

    /** 点击重试按钮：重新播放当前地址 */
    fun retry() {
        startPlayUrl(currentPlayUrl, currentHeaders)
    }
}
