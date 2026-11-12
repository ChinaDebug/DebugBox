package com.tvbox.kotlin.feature.play.ui

import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.play.databinding.ActivityPlayBinding
import com.tvbox.kotlin.feature.play.ui.widget.LrcParser
import com.tvbox.kotlin.feature.play.ui.widget.LrcView
import com.tvbox.kotlin.feature.play.ui.widget.LyricLoader
import org.json.JSONArray
import com.tvbox.kotlin.feature.play.ui.subtitle.SubtitleRepository
import dagger.hilt.android.AndroidEntryPoint
import master.flame.danmaku.ui.widget.DanmakuView
import javax.inject.Inject

/**
 * 播放页 Activity。
 *
 * 全屏播放 [MyVideoView]，集成自定义控制器 [PlayerVodControlView]、弹幕 [DanmakuView]、
 * 字幕 [SimpleSubtitleView]，通过 [PlayViewModel] 管理播放状态、剧集、解码器、缩放、倍速，
 * 实现进度记忆、错误重试、画中画与后台播放。
 *
 * 播放/弹幕/字幕/菜单逻辑抽入 [PlaySession]、[DanmuController]、
 * [SubtitleController]、[PlayerMenuController]、[ControllerListenerDelegate]，
 * 本类保留广告净化、进度保存、画中画、通知栏/PlayService、选集弹窗等宿主专属能力与控制器装配。
 */
@AndroidEntryPoint
class PlayActivity : BaseBindingActivity<ActivityPlayBinding>() {

    @Inject
    lateinit var subtitleRepository: SubtitleRepository

    @Inject
    lateinit var m3u8Purifier: M3u8Purifier

    @Inject
    lateinit var okHttpHelper: OkHttpHelper

    companion object {
        // Intent extras 键名
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION = "position"
        const val EXTRA_VOD_ID = "vodId"
        const val EXTRA_SOURCE_KEY = "sourceKey"
        const val EXTRA_VOD_INFO = "vodInfo"
        const val EXTRA_PLAYER_CFG = "playerCfg"
        const val EXTRA_DANMU = "danmu"
        const val EXTRA_DANMU_FILE = "danmakuFilePath"
        const val EXTRA_PARSE_LIST = "parseList"

        // 画中画广播
        private const val BROADCAST_ACTION = "VOD_CONTROL"
        const val BROADCAST_ACTION_PREV = 0
        const val BROADCAST_ACTION_PLAYPAUSE = 1
        const val BROADCAST_ACTION_NEXT = 2

        /** 画中画按钮 PendingIntent 请求码基数（避免与通知栏按钮请求码冲突） */
        private const val PIP_REQUEST_CODE_BASE = 1000

        /** 画中画进入确认延迟（毫秒）：enterPictureInPictureMode 异步生效，延迟后判断是否真正进入 PiP */
        private const val PIP_CONFIRM_DELAY_MS = 500L

        /** 进度保存间隔（毫秒） */
        private const val PROGRESS_SAVE_INTERVAL = 10_000L
    }

    override val layoutId = com.tvbox.kotlin.feature.play.R.layout.activity_play

    private val viewModel: PlayViewModel by viewModels()

    private lateinit var mVideoView: MyVideoView
    private lateinit var mController: PlayerVodControlView
    private var danmakuView: DanmakuView? = null
    private var subtitleView: SimpleSubtitleView? = null

    private var playLoading: ProgressBar? = null
    private var playLoadError: ImageView? = null
    private var playLoadTip: TextView? = null

    /** 已处理的解析器实例：collectStarted 重订阅会重新发射当前值，同一实例跳过避免重播 */
    private var handledParse: ParseBean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 进度定时保存任务 */
    private val progressSaveRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            mainHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL)
        }
    }

    /** 画中画广播接收 */
    private var pipActionReceiver: BroadcastReceiver? = null

    /** 后台播放通知控制广播接收 */
    private var notificationControlReceiver: BroadcastReceiver? = null

    /** PlayService 是否已启动，避免重复启动 */
    private var playServiceStarted = false

    /** 是否已保存历史 */
    private var historySaved = false

    /** 投屏接收模式：由投屏请求直接携带播放地址进入，不记历史/进度，控制栏精简 */
    private var isCastMode = false

    /** 纯音频模式是否生效：自动检测到纯音频源后进入（隐藏弹幕/字幕，显示波形+歌词面板） */
    private var audioModeActive = false

    /** 纯音频模式歌词控件 */
    private lateinit var lrcView: LrcView

    /** 歌词加载任务（切集时取消，避免旧集歌词覆盖新集） */
    private var lyricJob: Job? = null

    /** 用户手动暂停标志：手动暂停后切后台不自动恢复播放；后台自动暂停/音频焦点丢失暂停则回前台自动恢复 */
    private var userPaused = false

    /** 倍速长按临时快进前的原速 */
    private var speedBeforeLongPress = 1.0f
    /** 是否处于倍速长按临时快进中 */
    private var isLongPressSpeeding = false

    // ------------------------------------------------------------------
    // 能力控制器装配
    // ------------------------------------------------------------------

    /** 播放宿主实现：屏蔽 Fragment/Activity 差异 */
    private val host = object : PlayHost {
        override val context: Context get() = this@PlayActivity
        override val activity: Activity? get() = this@PlayActivity
        override val videoView: MyVideoView get() = mVideoView
        override val controller: PlayerVodControlView get() = mController
        override val viewModel: PlayViewModel get() = this@PlayActivity.viewModel
        override val mainHandler: Handler get() = this@PlayActivity.mainHandler
        override val scope: CoroutineScope get() = lifecycleScope
        override val danmakuView: DanmakuView? get() = this@PlayActivity.danmakuView
        override val subtitleView: SimpleSubtitleView? get() = this@PlayActivity.subtitleView

        override var userPaused: Boolean
            get() = this@PlayActivity.userPaused
            set(value) {
                this@PlayActivity.userPaused = value
            }

        override fun isAlive(): Boolean = !isFinishing && !isDestroyed

        override fun showTip(message: String, loading: Boolean, error: Boolean) {
            this@PlayActivity.showTip(message, loading, error)
        }

        override fun hideTip() {
            this@PlayActivity.hideTip()
        }

        override fun postOnUi(runnable: () -> Unit) {
            runOnUiThread { runnable() }
        }
    }

    private lateinit var danmuController: DanmuController
    private lateinit var subtitleController: SubtitleController
    private lateinit var menuController: PlayerMenuController
    private lateinit var session: PlaySession
    private lateinit var controllerDelegate: ControllerListenerDelegate

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun initView(savedInstanceState: Bundle?) {
        // 全屏沉浸式；默认横屏由 Manifest screenOrientation=sensorLandscape 保证，
        // 此处不再强制 LANDSCAPE，否则点击横竖屏切换按钮进入竖屏后 Activity 重建会被拉回横屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        mVideoView = binding.mVideoView
        danmakuView = binding.danmaku
        // 弹幕覆盖全屏时不拦截触摸，保证点击画面能唤出控制栏（触摸唤出）
        danmakuView?.isClickable = false
        playLoading = binding.playLoading
        playLoadError = binding.playLoadError
        playLoadTip = binding.playLoadTip

        // 初始化控制器
        mController = PlayerVodControlView(this)
        mVideoView.setVideoController(mController)
        danmakuView?.let { mVideoView.setDanmuView(it) }
        subtitleView = mController.subtitleView

        // 纯音频模式：面板注入播放器内部（渲染层与控制层之间），自动检测纯音频源后切换
        lrcView = binding.lrcView
        mVideoView.setAudioPanel(binding.audioPanel)
        mVideoView.onAudioModeChanged = { audio -> onAudioModeChange(audio) }
        // 进入全屏播放页：停止可能残留的服务内后台音频播放，避免与页面播放器叠加
        com.tvbox.kotlin.core.server.PlayService.stopBackgroundAudio(this)

        // 装配能力控制器
        danmuController = DanmuController(host)
        subtitleController = SubtitleController(host, subtitleRepository).apply {
            // 保留差异：全屏播放器关闭字幕带「字幕已关闭」提示
            showCloseSubtitleToast = true
        }
        menuController = PlayerMenuController(host)
        session = PlaySession(host, danmuController).apply {
            // 保留差异：解析成功后走广告净化再播放
            playWithPurify = this@PlayActivity::playWithPurify
        }
        menuController.onRestartPlay = {
            session.startPlayUrl(session.currentPlayUrl, session.currentHeaders)
        }
        controllerDelegate = ControllerListenerDelegate(
            host, danmuController, subtitleController, menuController, session
        ).apply {
            // 保留差异：暂停时主动显示右上角暂停条（绕过部分内核状态回调时序导致不显示的问题）
            onPauseBarShow = { mController.showPauseBar() }
            // 保留差异：长按倍速临时以最大档位快进（不修改配置），松手恢复原速
            onSpeedLongPress = {
                if (!mVideoView.isPlaying) {
                    false
                } else {
                    speedBeforeLongPress = viewModel.speed.value
                    isLongPressSpeeding = true
                    viewModel.switchSpeed(PlayViewModel.SPEED_OPTIONS.last())
                    true
                }
            }
            onSpeedLongPressRelease = {
                if (isLongPressSpeeding) {
                    isLongPressSpeeding = false
                    val restore = if (speedBeforeLongPress > 0) speedBeforeLongPress else 1.0f
                    viewModel.switchSpeed(restore)
                }
            }
            // 保留差异：选集弹窗
            onSelectEpisodeAction = { showEpisodeDialog() }
        }
        mController.setListener(controllerDelegate)

        // 控制器由宿主转发 MyVideoView 的播放状态与进度回调
        mVideoView.onPlayStateChange = { state ->
            mController.setPlayState(state)
            mController.onPlayStateChanged(state)
            // 视频尺寸可能在 PREPARED/PLAYING 时才就绪，此时更新分辨率与横竖屏切换按钮
            // （onPlayStateChanged，避免 onVideoSizeChanged 偶发不触发导致竖屏按钮不显示）
            if (state == PlayerConstants.STATE_PREPARED || state == PlayerConstants.STATE_PLAYING) {
                menuController.updateResolution()
            }
            if (state == PlayerConstants.STATE_PLAYING) {
                // 播放即启动媒体服务：通知栏/锁屏媒体卡片全程可用（onStop 仍兜底启动，幂等）
                ensurePlayServiceRunning()
                // 纯音频模式：进入播放态后按当前播放地址尝试加载歌词
                // （爬虫 playerContent 直接返回 lrc 时已由 currentLrc 观察显示，仅源未提供时降级同名 .lrc）
                if (audioModeActive && viewModel.currentLrc.value.isEmpty()) {
                    loadLyric(session.currentPlayUrl)
                }
            }
            // 画中画模式下播放/暂停状态变化：刷新控制按钮图标，并立即同步媒体会话/通知
            // （避免通知栏与锁屏卡片状态滞后，系统按过期状态发送相反控制导致"莫名停止/恢复"）
            if (state == PlayerConstants.STATE_PLAYING || state == PlayerConstants.STATE_PAUSED) {
                updatePipActions()
                syncMediaSessionNow()
            }
        }
        mVideoView.onProgressChange = { duration, position ->
            mController.setProgress(duration.toInt(), position.toInt())
            // 同步总时长，进度保存依赖其判断（未获取到时长前不落盘）
            viewModel.updateDuration(duration)
            // 歌词跟随播放进度高亮当前行
            if (audioModeActive) lrcView.updateProgress(position)
            // 媒体锁屏卡片：同步播放状态/进度（仅媒体服务运行中）
            if (playServiceStarted) {
                com.tvbox.kotlin.core.server.PlayService.syncPlayback(
                    this,
                    viewModel.title.value.ifEmpty { "TVBox" },
                    viewModel.currentEpisode.value?.name.orEmpty(),
                    duration,
                    position,
                    mVideoView.isPlaying
                )
            }
        }
        // 实时更新网速显示（独立于进度刷新，加载/缓冲阶段也持续更新）
        mVideoView.onNetSpeedChange = { speed ->
            mController.setNetSpeed(speed)
        }
        // 缓冲进度同步到进度条缓冲层
        mVideoView.onBufferedChange = { percent ->
            mController.setBufferedProgress(percent)
        }
        // 视频尺寸就绪后更新分辨率显示与横竖屏切换按钮
        mVideoView.onVideoSizeChangedListener = { _, _ -> menuController.updateResolution() }

        // 返回键处理
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mController.handleBackPressed()) return
                saveCurrentProgress()
                viewModel.saveVodRecord(mVideoView.currentPosition)
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun initData() {
        // 解析 Intent extras
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        val vodId = intent.getStringExtra(EXTRA_VOD_ID)
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        val vodInfo = intent.getSerializableExtra(EXTRA_VOD_INFO) as? VodInfo
        val playerCfg = intent.getStringExtra(EXTRA_PLAYER_CFG)
        val danmu = intent.getStringExtra(EXTRA_DANMU)?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_DANMU_FILE)?.let { path ->
                // 投屏弹幕经临时文件传递（避免 Intent/Binder 大小限制），读后删除
                try {
                    java.io.File(path).takeIf { it.exists() }?.let { f ->
                        val content = f.readText(Charsets.UTF_8)
                        f.delete()
                        content
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        val parseListJson = intent.getStringExtra(EXTRA_PARSE_LIST)

        // 加载解析器列表（由 :app 通过 Intent 注入），使用 JSONArray 解析避免引入 gson 依赖
        if (!parseListJson.isNullOrEmpty()) {
            try {
                val array = JSONArray(parseListJson)
                val list = ArrayList<ParseBean>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val bean = ParseBean()
                    bean.name = obj.optString("name")
                    bean.setUrl(obj.optString("url"))
                    bean.ext = obj.optString("ext", "")
                    bean.type = obj.optInt("type", 0)
                    bean.setDefault(obj.optBoolean("isDefault", false))
                    list.add(bean)
                }
                viewModel.setParseBeans(list)
            } catch (_: Throwable) {
                // 解析器列表解析失败忽略
            }
        }

        // 投屏接收模式：无影片信息但携带播放地址（isCastMode 判定）
        if (vodInfo == null && url.isNotEmpty()) {
            isCastMode = true
        }

        viewModel.loadPlayInfo(
            url,
            title,
            position,
            vodId,
            sourceKey,
            vodInfo,
            playerCfg
        )
        // 绑定片头片尾记忆键，恢复该影片上次设置的跳过时间
        mController.setSkipTimeKey(viewModel.skipTimeKey())
        if (danmu != null) {
            viewModel.setDanmu(danmu)
            danmuController.checkDanmu()
        }

        // 标题
        mController.setTitle(title)

        // 投屏接收模式精简控制栏（隐藏投屏/上下集/播放器切换/解析栏/片头片尾等）
        if (isCastMode) {
            mController.setCastMode(true)
        }

        // 收集 ViewModel 状态
        observeViewModel()

        // 应用屏显配置（右上角网速/进度时间/系统时间/电量显隐）
        menuController.applyScreenDisplay()

        // 启动播放
        if (url.isNotEmpty()) {
            if (isCastMode) {
                // 投屏接收：直接播放发送端给出的直链，不做广告净化（净化属发送端本地处理，
                // 对投屏直链再净化会因下载/本地服务链路导致播放失败）
                session.startPlayUrl(url, viewModel.getPlayerCfgHeaders())
            } else {
                // 直链播放携带 playerCfg 中的请求头（如网盘 WebDAV 认证）
                playWithPurify(url, viewModel.getPlayerCfgHeaders())
            }
            // 直链播放时若有弹幕来源则按播放状态检查
            danmuController.checkDanmu()
        } else if (vodInfo != null) {
            val episodeUrl = viewModel.currentEpisode.value?.url
            if (!episodeUrl.isNullOrEmpty()) {
                session.loadAndPlay(episodeUrl)
            } else {
                showTip(getString(uiR.string.play_err_no_video_addr), false, true)
            }
        } else {
            showTip(getString(uiR.string.play_err_no_play_addr), false, true)
        }
    }

    /**
     * 播放入口：净化 m3u8（移除 404 坏分片 + 广告）后播放，逻辑抽入 [M3u8Purifier]（详情页预览共用）。
     */
    private fun playWithPurify(url: String, headers: Map<String, String>?) {
        // 记录净化前的原始地址：净化成功走本地代理后 currentPlayUrl 为 127.0.0.1/m3u8，
        // 复制/投屏/推送需用此原始地址，外部设备才能访问
        session.originalPlayUrl = url
        session.originalHeaders = headers
        showTip("正在过滤广告", true, false)
        lifecycleScope.launch {
            m3u8Purifier.onAdRemoved = { count ->
                if (count > 0) {
                    Toast.makeText(
                        this@PlayActivity,
                        "已移除 $count 条视频广告",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            m3u8Purifier.purify(url, headers) { playUrl, playHeaders ->
                hideTip()
                session.startPlayUrl(playUrl, playHeaders)
            }
        }
    }

    /** 处理解析器切换 */
    private fun handleParseChange(parse: ParseBean) {
        val currentEpisodeUrl = viewModel.currentEpisode.value?.url
        if (!currentEpisodeUrl.isNullOrEmpty()) {
            session.loadAndPlay(currentEpisodeUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        registerPipReceiver()
        registerNotificationControlReceiver()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL)
        // 回前台复位后台播放续播标志（surface 已重建，播放/暂停回归正常逻辑）
        mVideoView.backgroundPlayEnabled = false
        // 回前台恢复播放：非用户手动暂停且播放器处于暂停态（后台暂停/音频焦点被抢占自动暂停）时自动恢复，
        // 避免切后台回前台画面黑屏、需手动操作或误判为重新加载（onResume resume）
        if (!userPaused && mVideoView.playState == PlayerConstants.STATE_PAUSED) {
            mVideoView.resume()
        }
        // 回前台隐藏控制栏，避免控制栏按钮焦点框残留
        mController.hideBottom()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(progressSaveRunnable)
        saveCurrentProgress()
        // 切后台前隐藏控制栏并清空按钮焦点，回前台时不会残留白色焦点边框
        mController.hideBottom()
        // 后台播放类型为 0（关闭）时切后台暂停播放，回前台自动恢复；
        // 开启后台播放时置位 surface 续播标志：GSY 在 surface 销毁（切后台）时仍会自动暂停，
        // MyVideoView 据此补恢复，保持音频继续输出
        mVideoView.backgroundPlayEnabled = viewModel.backgroundPlayType() != 0
        if (viewModel.backgroundPlayType() == 0 && mVideoView.isPlaying) {
            mVideoView.pause()
        }
        // 画中画类型兜底：正常应经 onUserLeaveHint 进入 PiP（画面由系统保留）；
        // 若设备/系统未进入 PiP（如模拟器异常），延迟暂停，避免误表现为"只有音频无画面"
        if (viewModel.backgroundPlayType() == 2) {
            mainHandler.post {
                if (!isFinishing && !isDestroyed && mVideoView.isPlaying && !isInPictureInPictureMode) {
                    mVideoView.pause()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 后台播放类型为 0（关闭）时不出后台：不启动播放服务
        if (viewModel.backgroundPlayType() == 0) return
        // 后台播放：不主动暂停，由系统决定是否进入 PiP
        // 启动 PlayService 维持进程存活并显示通知
        ensurePlayServiceRunning()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterPipReceiver()
        unregisterNotificationControlReceiver()
        stopPlayService()
        lyricJob?.cancel()
        mController.clearListener()
        // 退出界面：强制取消弹幕加载（阻塞网络任务结束后凭代际丢弃结果，不操作已销毁的视图）
        danmuController.cancelLoad()
        viewModel.stopProtocolTasks()
        try {
            mVideoView.release()
        } catch (_: Throwable) {
            // 释放异常忽略，避免影响退出流程
        }
    }

    // ------------------------------------------------------------------
    // 状态收集
    // ------------------------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            // 爬虫异步生成弹幕后经 GET /action?do=refresh 回传弹幕地址，收到后立即加载
            EventHub.events.collect { event ->
                if (event is TypedEvent && event.type == ControlManager.TYPE_DANMU_REFRESH) {
                    val url = event.payload as? String ?: return@collect
                    if (url.isEmpty() || url == viewModel.getDanmu()) return@collect
                    viewModel.setDanmu(url)
                    danmuController.checkDanmu()
                }
            }
        }
        collectStarted(viewModel.playerType) { type ->
            session.applyPlayerConfig(forcePlayerType = type)
        }
        collectStarted(viewModel.scaleType) { scale ->
            mVideoView.setScreenScaleType(scale)
            mController.setScaleText(PlayerHelper.getScaleName(scale))
        }
        collectStarted(viewModel.speed) { speed ->
            session.applySpeed(speed)
            mController.setSpeedText(speed)
        }
        collectStarted(viewModel.playState) { state ->
            when (state) {
                PlayState.PLAYING -> {
                    hideTip()
                    // 投屏接收模式：播放成功后再次应用控制栏精简，防止被其他逻辑覆盖
                    if (isCastMode) mController.setCastMode(true)
                    // 首次进入播放状态时尝试记录（内部有 position>0 守卫，
                    // 刚开播时可能为 0 而不记录，后续由 saveCurrentProgress/退出时补记）。
                    // 投屏接收模式不写历史
                    if (!historySaved && !isCastMode) {
                        historySaved = true
                        viewModel.saveVodRecord(mVideoView.currentPosition)
                    }
                }
                // 错误提示由 handlePlayError 展示具体原因
                else -> Unit
            }
        }
        collectStarted(viewModel.currentEpisode) { episode ->
            episode?.name?.let { mController.setTitle("${viewModel.title.value} : $it") }
            // 剧集切换后更新通知显示
            updatePlayServiceInfo()
        }
        // 爬虫 playerContent 直接返回的 LRC 歌词：解析成功后即时显示（含视频/音频模式）
        collectStarted(viewModel.currentLrc) { lrc ->
            if (lrc.isNotEmpty() && !isFinishing && !isDestroyed) {
                lrcView.setLyric(LrcParser.parse(lrc))
            }
        }
        collectStarted(viewModel.title) { title ->
            if (title.isNotEmpty()) mController.setTitle(title)
        }
        collectStarted(viewModel.parseBeans) { list ->
            mController.setParseList(list)
        }
        collectStarted(viewModel.currentParse) { parse ->
            // 解析器切换后重新播放（仅在解析器实例实际变化时触发。
            // collectStarted 基于 repeatOnLifecycle(STARTED)，切后台回前台重订阅
            // 会重新发射当前值，同一解析器重复触发会 loadAndPlay 从头重播）
            if (parse != null && parse !== handledParse) {
                handledParse = parse
                handleParseChange(parse)
            }
        }
        collectStarted(viewModel.error) { msg ->
            if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        // 解析阶段提示：常规解析 / 嗅探 / 超级解析
        collectStarted(viewModel.resolveStage) { stage ->
            when (stage) {
                PlayViewModel.RESOLVE_STAGE_PARSE -> showTip("正在解析播放地址", true, false)
                PlayViewModel.RESOLVE_STAGE_SNIFF -> showTip("正在嗅探播放地址", true, false)
                PlayViewModel.RESOLVE_STAGE_SUPER -> showTip("超级解析中", true, false)
            }
        }
    }

    // ------------------------------------------------------------------
    // 进度保存
    // ------------------------------------------------------------------

    /** 保存当前播放进度（投屏接收模式不保存进度与历史） */
    private fun saveCurrentProgress() {
        if (isCastMode) return
        // 切集解析中跳过保存，避免旧播放器位置错写入新集进度键
        if (session.progressSaveLocked) return
        val url = session.currentPlayUrl
        if (url.isEmpty()) return
        val position = mVideoView.currentPosition
        if (position > 0) {
            viewModel.saveProgress(url, position)
            // 同步 ViewModel 进度并写入历史（内部有 position>0 守卫）
            viewModel.updatePosition(position)
            viewModel.saveVodRecord(position)
        }
    }

    // ------------------------------------------------------------------
    // 提示显示
    // ------------------------------------------------------------------

    /** 显示加载/错误提示 */
    private fun showTip(message: String, loading: Boolean, error: Boolean) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            playLoadTip?.text = message
            playLoadTip?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            playLoading?.visibility = if (loading) View.VISIBLE else View.GONE
            playLoadError?.visibility = if (error) View.VISIBLE else View.GONE
        }
    }

    /** 隐藏提示 */
    private fun hideTip() {
        runOnUiThread {
            playLoadTip?.visibility = View.GONE
            playLoading?.visibility = View.GONE
            playLoadError?.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // 系统UI
    // ------------------------------------------------------------------

    /**
     * 全屏沉浸式：隐藏状态栏与导航栏，并让内容延伸到刘海/挖孔区域。
     * Android 11+ 必须用 WindowInsetsController + setDecorFitsSystemWindows(false)，
     * 仅靠旧的 SYSTEM_UI_FLAG 无法隐藏底部导航栏、内容也无法延伸到刘海区（会留黑边）；
     * Android 9/10 需将 layoutInDisplayCutoutMode 置为 SHORT_EDGES 才允许内容进刘海。
     */
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // 必须关闭系统窗口适配，否则全屏内容无法延伸到刘海/挖孔区域
                window.setDecorFitsSystemWindows(false)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                // Android 9/10：允许内容延伸到刘海区域（默认 DEFAULT 模式会保留黑边）
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 画中画
    // ------------------------------------------------------------------

    @TargetApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPip()) {
            enterPipMode()
        }
    }

    /** 是否应进入画中画 */
    private fun shouldEnterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isFinishing || isDestroyed) return false
        // 通过 ViewModel 读取画中画偏好，避免直接持有 AppPreferences
        if (!viewModel.isPipEnabled()) return false
        return mVideoView.isPlaying
    }

    /** 进入画中画模式 */
    @TargetApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder().apply {
            val videoSize = mVideoView.videoSize
            if (videoSize != null && videoSize[0] > 0 && videoSize[1] > 0) {
                setAspectRatio(Rational(videoSize[0], videoSize[1]))
            } else {
                setAspectRatio(Rational(16, 9))
            }
            // 画中画控制按钮：上一首/播放暂停/下一首，点击经 BROADCAST_ACTION 广播分发到本页
            setActions(buildPipActions())
        }.build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: Throwable) {
            // 进入画中画失败忽略
        }
    }

    /** 构造画中画控制按钮（上一首/播放暂停/下一首），播放暂停图标随播放状态切换 */
    @TargetApi(Build.VERSION_CODES.O)
    private fun buildPipActions(): List<RemoteAction> {
        val playing = mVideoView.isPlaying
        return listOf(
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "上一首", "上一首",
                pipActionPendingIntent(BROADCAST_ACTION_PREV)
            ),
            RemoteAction(
                Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                "播放/暂停", "播放/暂停",
                pipActionPendingIntent(BROADCAST_ACTION_PLAYPAUSE)
            ),
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "下一首", "下一首",
                pipActionPendingIntent(BROADCAST_ACTION_NEXT)
            )
        )
    }

    /** 画中画按钮广播 PendingIntent（复用 [BROADCAST_ACTION] 与 pipActionReceiver 分发） */
    private fun pipActionPendingIntent(actionCode: Int): PendingIntent {
        val intent = Intent(BROADCAST_ACTION).apply {
            setPackage(packageName)
            putExtra("action", actionCode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, PIP_REQUEST_CODE_BASE + actionCode, intent, flags)
    }

    /** 播放状态变化时刷新画中画按钮（播放/暂停图标切换），仅画中画模式下生效 */
    @TargetApi(Build.VERSION_CODES.O)
    private fun updatePipActions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isInPictureInPictureMode) return
        try {
            setPictureInPictureParams(
                PictureInPictureParams.Builder().apply {
                    val videoSize = mVideoView.videoSize
                    if (videoSize != null && videoSize[0] > 0 && videoSize[1] > 0) {
                        setAspectRatio(Rational(videoSize[0], videoSize[1]))
                    } else {
                        setAspectRatio(Rational(16, 9))
                    }
                    setActions(buildPipActions())
                }.build()
            )
        } catch (_: Throwable) {
            // 更新失败忽略
        }
    }

    /** 注册画中画广播接收 */
    private fun registerPipReceiver() {
        if (pipActionReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        pipActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || intent.action != BROADCAST_ACTION) return
                when (intent.getIntExtra("action", -1)) {
                    BROADCAST_ACTION_PREV -> controllerDelegate.onPlayPrevious()
                    BROADCAST_ACTION_PLAYPAUSE -> controllerDelegate.onPlayPause()
                    BROADCAST_ACTION_NEXT -> controllerDelegate.onPlayNext()
                }
            }
        }
        val filter = IntentFilter(BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
        }
    }

    /** 注销画中画广播接收 */
    private fun unregisterPipReceiver() {
        pipActionReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        pipActionReceiver = null
    }

    // ------------------------------------------------------------------
    // 纯音频模式与歌词
    // ------------------------------------------------------------------

    /** 纯音频模式切换：隐藏/恢复视频元素（弹幕/字幕），并加载同名歌词 */
    private fun onAudioModeChange(audio: Boolean) {
        audioModeActive = audio
        if (audio) {
            // 纯音频无画面：隐藏弹幕/字幕并停止弹幕加载，避免空画面上的悬浮元素
            danmakuView?.visibility = View.GONE
            subtitleView?.visibility = View.GONE
            danmuController.cancelLoad()
            binding.audioTitle.text = buildAudioTitle()
            loadAudioPoster()
            // 爬虫 playerContent 未直接提供歌词时，降级尝试同名 .lrc
            if (viewModel.currentLrc.value.isEmpty()) loadLyric(session.currentPlayUrl)
        } else {
            danmakuView?.visibility = View.VISIBLE
            subtitleView?.visibility = View.VISIBLE
            // 退出音频模式补一次弹幕加载：进入音频模式时已 cancelLoad 且视频未重播，
            // onPlaying 不会再触发加载，不补会导致弹幕一直不显示；
            // 同源走已下载内容缓存解析，不产生额外网络请求
            danmuController.loadDanmuWhenPlaying(showToast = false)
        }
    }

    /** 加载当前歌曲海报作为纯音频背景（无图/加载失败时保留黑色兜底） */
    private fun loadAudioPoster() {
        val pic = viewModel.vodInfo.value?.pic
        if (pic.isNullOrBlank()) return
        CoilUtils.load(this, binding.audioPoster, pic, placeholder = 0, error = 0)
    }

    /** 音频模式标题：影片名 + 剧集名 */
    private fun buildAudioTitle(): String {
        val title = viewModel.title.value.ifEmpty { "正在播放" }
        val episode = viewModel.currentEpisode.value?.name
        return if (episode.isNullOrEmpty()) title else "$title - $episode"
    }

    /**
     * 按播放地址加载同名 LRC 歌词（路径扩展名替换为 .lrc），
     * 失败静默（多数源不提供歌词，不打扰播放）。
     */
    private fun loadLyric(url: String) {
        if (url.isBlank()) return
        lyricJob?.cancel()
        lyricJob = LyricLoader.load(lifecycleScope, okHttpHelper, session.currentHeaders, url) { parsed ->
            if (!isFinishing && !isDestroyed) lrcView.setLyric(parsed)
        }
    }

    // ------------------------------------------------------------------
    // 后台播放通知
    // ------------------------------------------------------------------

    /** 启动 PlayService 显示播放通知，保持进程存活 */
    private fun ensurePlayServiceRunning() {
        if (playServiceStarted) {
            updatePlayServiceInfo()
            return
        }
        try {
            com.tvbox.kotlin.core.server.PlayService.start(this, buildVideoInfo())
            playServiceStarted = true
        } catch (_: Throwable) {
            // 启动失败忽略，不影响播放
        }
    }

    /** 更新通知显示的视频信息（如切换剧集后） */
    private fun updatePlayServiceInfo() {
        if (!playServiceStarted) return
        try {
            com.tvbox.kotlin.core.server.PlayService.start(this, buildVideoInfo())
        } catch (_: Throwable) {
            // 通知更新失败忽略
        }
    }

    /** 播放/暂停状态切换时立即同步媒体会话与通知（进度回调每秒才同步，暂停瞬间不等待） */
    private fun syncMediaSessionNow() {
        if (!playServiceStarted) return
        com.tvbox.kotlin.core.server.PlayService.syncPlayback(
            this,
            viewModel.title.value.ifEmpty { "TVBox" },
            viewModel.currentEpisode.value?.name.orEmpty(),
            mVideoView.duration.toLong().coerceAtLeast(0),
            mVideoView.currentPosition.toLong().coerceAtLeast(0),
            mVideoView.isPlaying
        )
    }

    /** 停止 PlayService */
    private fun stopPlayService() {
        if (!playServiceStarted) return
        try {
            com.tvbox.kotlin.core.server.PlayService.stop(this)
        } catch (_: Throwable) {
            // 停止失败忽略
        }
        playServiceStarted = false
    }

    /** 构造 PlayService 所需的视频信息字符串 `title&&subtitle` */
    private fun buildVideoInfo(): String {
        val title = viewModel.title.value.ifEmpty { "TVBox" }
        val episode = viewModel.currentEpisode.value?.name ?: ""
        return "$title&&$episode"
    }

    /** 注册通知栏控制广播接收 */
    private fun registerNotificationControlReceiver() {
        if (notificationControlReceiver != null) return
        notificationControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || intent.action != com.tvbox.kotlin.core.server.PlayService.ACTION_PLAY_CONTROL) return
                when (intent.getIntExtra(
                    com.tvbox.kotlin.core.server.PlayService.EXTRA_CONTROL_ACTION, -1
                )) {
                    com.tvbox.kotlin.core.server.PlayService.ACTION_PREV -> controllerDelegate.onPlayPrevious()
                    com.tvbox.kotlin.core.server.PlayService.ACTION_PLAY_PAUSE -> controllerDelegate.onPlayPause()
                    com.tvbox.kotlin.core.server.PlayService.ACTION_NEXT -> controllerDelegate.onPlayNext()
                    // 媒体会话 seek 回调：锁屏/系统媒体中心拖动进度条
                    com.tvbox.kotlin.core.server.PlayService.ACTION_SEEK -> {
                        val pos = intent.getLongExtra(
                            com.tvbox.kotlin.core.server.PlayService.EXTRA_SEEK_POSITION, 0L
                        )
                        if (pos > 0) mVideoView.seekTo(pos)
                    }
                }
            }
        }
        val filter = IntentFilter(com.tvbox.kotlin.core.server.PlayService.ACTION_PLAY_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notificationControlReceiver, filter)
        }
    }

    /** 注销通知栏控制广播接收 */
    private fun unregisterNotificationControlReceiver() {
        notificationControlReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        notificationControlReceiver = null
    }

    // ------------------------------------------------------------------
    // 按键事件
    // ------------------------------------------------------------------

    /** 遥控按键优先交给播放控制器处理（唤出/隐藏控制栏、播放暂停、快进快退等） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mController.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                controllerDelegate.onPlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                controllerDelegate.onPlayNext()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                controllerDelegate.onPlayPrevious()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // 选集弹窗
    // ------------------------------------------------------------------

    /** 显示选集弹窗，选择剧集后切换并播放 */
    private fun showEpisodeDialog() {
        val info = viewModel.vodInfo.value
        if (info == null || info.isSeriesEmpty()) {
            Toast.makeText(this, "无剧集信息", Toast.LENGTH_SHORT).show()
            return
        }
        val currentFlag = info.playFlag ?: ""
        val currentIdx = info.playEpisodeIndex
        // 克隆避免弹窗内修改原数据
        val snapshot = info.clone() as VodInfo
        PlayEpisodeDialog(this, snapshot, currentFlag, currentIdx)
            .setThemeColor(getThemeColor())
            .onEpisodeSelected { flag, episodeIndex ->
                // 先保存当前集进度（此刻集索引仍是旧集，进度键对应正确）
                saveCurrentProgress()
                val url = viewModel.selectEpisodeInFlag(flag, episodeIndex)
                if (!url.isNullOrEmpty()) {
                    // 换集恢复目标集真实进度（进度键随实际播放集，各集独立）
                    session.loadAndPlay(url)
                } else {
                    Toast.makeText(this, getString(uiR.string.play_err_no_video_addr), Toast.LENGTH_SHORT).show()
                }
            }
            .onReverse {
                viewModel.reverseSeries()
            }
            .show()
    }

    /** 读取当前主题色 */
    private fun getThemeColor(): Int {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorAccent))
        return try {
            ta.getColor(0, 0xFFFFFFFF.toInt())
        } finally {
            ta.recycle()
        }
    }
}
