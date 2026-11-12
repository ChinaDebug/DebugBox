package com.tvbox.kotlin.feature.play.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.play.databinding.FragmentPlayBinding
import com.tvbox.kotlin.feature.play.ui.subtitle.SubtitleRepository
import com.tvbox.kotlin.feature.play.ui.widget.LrcParser
import com.tvbox.kotlin.feature.play.ui.widget.LrcView
import com.tvbox.kotlin.feature.play.ui.widget.LyricLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import master.flame.danmaku.ui.widget.DanmakuView
import javax.inject.Inject

/**
 * 预览播放器 Fragment，用于详情页顶部小窗播放。
 *
 * 复用 [PlayViewModel] 与 [PlayerVodControlView]，能力与 PlayActivity 保持一致
 * （解析、弹幕、字幕、倍速、缩放、解码器切换、错误重试），但不包含投屏、
 * 通知栏、画中画等 Activity 级能力。通过 [setData] 注入播放信息。
 *
 * 播放/弹幕/字幕/菜单逻辑抽入 [PlaySession]、[DanmuController]、
 * [SubtitleController]、[PlayerMenuController]、[ControllerListenerDelegate]，
 * 本类仅保留生命周期、宿主专属能力与控制器装配。
 */
@AndroidEntryPoint
class PlayFragment : Fragment() {

    companion object {
        /** 播放进度刷新间隔（毫秒），每秒记录 */
        private const val POSITION_REFRESH_INTERVAL = 1000L

        fun newInstance(): PlayFragment = PlayFragment()
    }

    private var _binding: FragmentPlayBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlayViewModel by viewModels()

    /** 预览播放器视图（供外部访问） */
    val mVideoView: MyVideoView
        get() = binding.mVideoView

    /** 播放器控制器 */
    val vodController: PlayerVodControlView?
        get() = mController

    private lateinit var mController: PlayerVodControlView
    private var danmakuView: DanmakuView? = null
    private var subtitleView: com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView? = null

    /** 用户手动暂停标志：手动暂停后切后台不自动恢复播放；后台自动暂停/音频焦点丢失暂停则回前台自动恢复 */
    private var userPaused = false

    @Inject
    lateinit var subtitleRepository: SubtitleRepository

    @Inject
    lateinit var m3u8Purifier: M3u8Purifier

    @Inject
    lateinit var okHttpHelper: OkHttpHelper

    private var playLoading: ProgressBar? = null
    private var playLoadError: ImageView? = null
    private var playLoadTip: TextView? = null

    /** 纯音频模式是否生效：自动检测到纯音频源后进入（隐藏弹幕/字幕，显示波形+歌词面板） */
    private var audioModeActive = false

    /** 纯音频模式歌词控件 */
    private lateinit var lrcView: LrcView

    /** 歌词加载任务（切集时取消，避免旧集歌词覆盖新集） */
    private var lyricJob: Job? = null

    /** PlayService 是否已启动（预览窗后台播放：切后台保持播放并显示媒体卡片） */
    private var playServiceStarted = false

    /** 通知栏/锁屏媒体卡片控制广播接收（上一首/播放暂停/下一首/seek） */
    private var notificationControlReceiver: BroadcastReceiver? = null

    /** 全屏模式（详情页内全屏）：全屏显示标题+大波形，预览小窗隐藏标题+小波形 */
    private var fullscreenMode = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 周期刷新播放进度（每秒记录），供退出时写入历史兜底 */
    private val positionRunnable = object : Runnable {
        private var saveCounter = 0
        override fun run() {
            if (!isAdded) return
            val pos = mVideoView.currentPosition
            if (pos > 0) {
                viewModel.updatePosition(pos)
                // 30 秒节流保存进度（避免退出时丢进度）
                saveCounter++
                if (saveCounter >= 30) {
                    saveCounter = 0
                    saveCurrentProgress()
                }
            }
            mainHandler.postDelayed(this, POSITION_REFRESH_INTERVAL)
        }
    }

    /** 保存当前播放进度并写入历史 */
    private fun saveCurrentProgress() {
        // 切集解析中跳过保存，避免旧播放器位置错写入新集进度键
        if (session.progressSaveLocked) return
        val url = session.currentPlayUrl
        if (url.isEmpty()) return
        val position = mVideoView.currentPosition
        if (position > 0) {
            viewModel.saveProgress(url, position)
            viewModel.updatePosition(position)
            viewModel.saveVodRecord(position)
        }
    }

    /** 播放成功状态回调（播放/错误/完成时通知宿主详情页） */
    var playSuccessListener: ((Boolean) -> Unit)? = null

    /** 切集回调（上一集/下一集/自动连播成功后通知宿主详情页同步集数选中状态） */
    var episodeChangedListener: ((flag: String, episodeIndex: Int) -> Unit)? = null

    /** 视图就绪前调用 setData 时暂存的播放数据，onViewCreated 后重放 */
    private var pendingData: Bundle? = null

    // ------------------------------------------------------------------
    // 能力控制器装配
    // ------------------------------------------------------------------

    /** 播放宿主实现：屏蔽 Fragment/Activity 差异 */
    private val host = object : PlayHost {
        override val context: Context get() = requireContext()
        override val activity: Activity? get() = requireActivity()
        override val videoView: MyVideoView get() = mVideoView
        override val controller: PlayerVodControlView get() = mController
        override val viewModel: PlayViewModel get() = this@PlayFragment.viewModel
        override val mainHandler: Handler get() = this@PlayFragment.mainHandler
        override val scope: CoroutineScope get() = viewLifecycleOwner.lifecycleScope
        override val danmakuView: DanmakuView? get() = this@PlayFragment.danmakuView
        override val subtitleView: com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView?
            get() = this@PlayFragment.subtitleView

        override var userPaused: Boolean
            get() = this@PlayFragment.userPaused
            set(value) {
                this@PlayFragment.userPaused = value
            }

        override fun isAlive(): Boolean = isAdded

        override fun showTip(message: String, loading: Boolean, error: Boolean) {
            this@PlayFragment.showTip(message, loading, error)
        }

        override fun hideTip() {
            this@PlayFragment.hideTip()
        }

        override fun postOnUi(runnable: () -> Unit) {
            view?.post { runnable() }
        }
    }

    private lateinit var danmuController: DanmuController
    private lateinit var subtitleController: SubtitleController
    private lateinit var menuController: PlayerMenuController
    private lateinit var session: PlaySession
    private lateinit var controllerDelegate: ControllerListenerDelegate

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initPlayer()
        observeViewModel()
        // 应用屏显偏好（右上角网速/进度时间等）
        PlayerMenuHelper.applyScreenDisplay(requireContext(), mController)
        // 视图就绪前 setData 缓存的播放数据在此重放，避免详情页快速进入时播放链路丢失
        pendingData?.let { pendingData = null; setData(it) }
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private fun initPlayer() {
        danmakuView = binding.danmaku
        // 弹幕覆盖全屏时不拦截触摸，保证点击画面能唤出控制栏（触摸唤出）
        danmakuView?.isClickable = false
        playLoading = binding.playLoading
        playLoadError = binding.playLoadError
        playLoadTip = binding.playLoadTip

        mController = PlayerVodControlView(requireContext()).apply {
            // 预览小窗：隐藏迷你顶栏（片名/网速），保持画面干净
            setPreviewMode(true)
        }
        mVideoView.setVideoController(mController)
        danmakuView?.let { mVideoView.setDanmuView(it) }
        subtitleView = mController.subtitleView

        // 纯音频模式：面板注入播放器内部（渲染层与控制层之间），自动检测纯音频源后切换（小窗/全屏共用）
        lrcView = binding.lrcView
        mVideoView.setAudioPanel(binding.audioPanel)
        mVideoView.onAudioModeChanged = { audio -> onAudioModeChange(audio) }
        // 音频面板初始为预览窗样式（隐藏标题、小波形）
        applyAudioPanelStyle()
        // 进入详情页播放：停止可能残留的服务内后台音频播放，避免与预览播放器叠加
        com.tvbox.kotlin.core.server.PlayService.stopBackgroundAudio(requireContext())

        // 装配能力控制器
        danmuController = DanmuController(host)
        subtitleController = SubtitleController(host, subtitleRepository)
        menuController = PlayerMenuController(host)
        session = PlaySession(host, danmuController).apply {
            // m3u8 净化（移除 404 坏分片 + 广告），与全屏共用 M3u8Purifier
            playWithPurify = { url, headers ->
                // 记录净化前的原始地址：净化成功走本地代理后 currentPlayUrl 为 127.0.0.1/m3u8，
                // 详情页复制地址需用此原始地址
                session.originalPlayUrl = url
                session.originalHeaders = headers
                showTip("正在过滤广告", true, false)
                lifecycleScope.launch {
                    m3u8Purifier.onAdRemoved = { count ->
                        if (count > 0) {
                            Toast.makeText(
                                requireContext(),
                                "已移除 $count 条视频广告",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    m3u8Purifier.purify(url, headers) { playUrl, playHeaders ->
                        hideTip()
                        startPlayUrl(playUrl, playHeaders)
                    }
                }
            }
        }
        menuController.onRestartPlay = {
            session.startPlayUrl(session.currentPlayUrl, session.currentHeaders)
        }
        controllerDelegate = ControllerListenerDelegate(
            host, danmuController, subtitleController, menuController, session
        ).apply {
            // 预览播放成功/失败时通知详情页（每次读取最新回调，详情页可随时更换）
            playSuccessListener = { success -> this@PlayFragment.playSuccessListener?.invoke(success) }
            // 预览窗切集（上一集/下一集/自动连播）后通知详情页同步集数选中状态
            onEpisodeChanged = { flag, index ->
                this@PlayFragment.episodeChangedListener?.invoke(flag, index)
            }
            // 保留差异：预览小窗无选集弹窗，提示使用详情页选集
            onSelectEpisodeAction = {
                Toast.makeText(requireContext(), "请使用详情页选集", Toast.LENGTH_SHORT).show()
            }
        }
        mController.setListener(controllerDelegate)

        // 控制器由宿主转发 MyVideoView 的播放状态与进度回调
        mVideoView.onPlayStateChange = { state ->
            mController.setPlayState(state)
            mController.onPlayStateChanged(state)
            // 视频尺寸可能在 PREPARED/PLAYING 时才就绪，此时更新分辨率显示
            if (state == PlayerConstants.STATE_PREPARED || state == PlayerConstants.STATE_PLAYING) {
                menuController.updateResolution()
            }
            // 纯音频模式：进入播放态后按当前播放地址尝试加载同名歌词
            if (state == PlayerConstants.STATE_PLAYING && audioModeActive) {
                // 爬虫 playerContent 未直接提供歌词时，降级尝试同名 .lrc
                if (viewModel.currentLrc.value.isEmpty()) loadLyric(session.currentPlayUrl)
            }
            // 播放/暂停状态切换立即同步媒体会话/通知（预览窗后台播放时通知栏与锁屏状态保持一致）
            if (state == PlayerConstants.STATE_PLAYING || state == PlayerConstants.STATE_PAUSED) {
                syncMediaSessionNow()
            }
        }
        mVideoView.onProgressChange = { duration, position ->
            mController.setProgress(duration.toInt(), position.toInt())
            // 同步总时长，进度保存依赖其判断（未获取到时长前不落盘）
            viewModel.updateDuration(duration)
            // 歌词跟随播放进度高亮当前行
            if (audioModeActive) lrcView.updateProgress(position)
            // 预览窗后台播放：同步媒体会话状态（锁屏/系统媒体中心）
            if (playServiceStarted) {
                com.tvbox.kotlin.core.server.PlayService.syncPlayback(
                    requireContext(),
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

        // 启动周期进度刷新（原 startPlayUrl 中启动，现由宿主统一启动，避免每次播放重复 post）
        mainHandler.removeCallbacks(positionRunnable)
        mainHandler.post(positionRunnable)
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

    /** 播放/暂停状态切换时立即同步媒体会话与通知（进度回调每秒才同步，暂停瞬间不等待） */
    private fun syncMediaSessionNow() {
        if (!playServiceStarted) return
        com.tvbox.kotlin.core.server.PlayService.syncPlayback(
            requireContext(),
            viewModel.title.value.ifEmpty { "TVBox" },
            viewModel.currentEpisode.value?.name.orEmpty(),
            mVideoView.duration.toLong().coerceAtLeast(0),
            mVideoView.currentPosition.toLong().coerceAtLeast(0),
            mVideoView.isPlaying
        )
    }

    /** 加载当前歌曲海报作为纯音频背景（无图/加载失败时保留黑色兜底） */
    private fun loadAudioPoster() {
        val pic = viewModel.vodInfo.value?.pic
        if (pic.isNullOrBlank()) return
        CoilUtils.load(requireContext(), binding.audioPoster, pic, placeholder = 0, error = 0)
    }

    /** 音频模式标题：影片名 + 剧集名 */
    private fun buildAudioTitle(): String {
        val title = viewModel.title.value.ifEmpty { "正在播放" }
        val episode = viewModel.currentEpisode.value?.name
        return if (episode.isNullOrEmpty()) title else "$title - $episode"
    }

    /** 按播放地址加载同名 LRC 歌词（路径扩展名替换为 .lrc），失败静默 */
    private fun loadLyric(url: String) {
        if (url.isBlank()) return
        lyricJob?.cancel()
        lyricJob = LyricLoader.load(
            viewLifecycleOwner.lifecycleScope, okHttpHelper, session.currentHeaders, url
        ) { parsed ->
            if (isAdded) lrcView.setLyric(parsed)
        }
    }

    // ------------------------------------------------------------------
    // 外部接口（详情页调用）
    // ------------------------------------------------------------------

    /**
     * 注入播放数据并开始播放。
     * @param bundle 包含 sourceKey / VodInfo / playerCfg / parseList 等键
     */
    fun setData(bundle: Bundle) {
        // 视图未就绪（session 尚未初始化）时缓存，onViewCreated 后重放，避免播放链路丢失
        if (!::session.isInitialized) {
            pendingData = bundle
            return
        }
        val sourceKey = bundle.getString("sourceKey")
        val info = bundle.getSerializable("VodInfo") as? VodInfo
        val playerCfg = bundle.getString("playerCfg")
        val parseListJson = bundle.getString("parseList")

        // 注入解析器列表
        if (!parseListJson.isNullOrEmpty()) {
            try {
                val array = org.json.JSONArray(parseListJson)
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

        viewModel.loadPlayInfo(
            url = "",
            title = info?.name,
            position = 0L,
            vodId = info?.id,
            sourceKey = sourceKey,
            vodInfo = info,
            playerCfgJson = playerCfg
        )
        // 绑定片头片尾记忆键，恢复该影片上次设置的跳过时间
        mController.setSkipTimeKey(viewModel.skipTimeKey())

        if (info != null) {
            val episodeUrl = viewModel.currentEpisode.value?.url
            if (!episodeUrl.isNullOrEmpty()) {
                session.loadAndPlay(episodeUrl)
            } else {
                showTip(requireContext().getString(uiR.string.play_err_no_video_addr), false, true)
            }
        } else {
            showTip(requireContext().getString(uiR.string.play_err_no_play_addr), false, true)
        }
    }

    /**
     * 同步 VodInfo 数据（排序切换等场景，updateVodInfo 只同步数据不重新播放）。
     * 剧集/线路真正切换播放由 loadAndPlay 驱动，这里不触发重播。
     */
    fun updateVodInfo(info: VodInfo) {
        viewModel.loadPlayInfo(
            url = "",
            title = info.name,
            position = 0L,
            vodId = info.id,
            sourceKey = info.sourceKey,
            vodInfo = info,
            playerCfgJson = info.playerCfg
        )
        // 排序等场景重新绑定，跳过时间按影片保持
        mController.setSkipTimeKey(viewModel.skipTimeKey())
    }

    /** 返回键处理，先关闭控制器再退出 */
    fun onBackPressed(): Boolean = mController.handleBackPressed()

    /** 按键分发，委托给播放器控制器处理 */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return isAdded && mController.dispatchKeyEvent(event)
    }

    /** 清理消息队列 */
    fun clearHandlerMessages() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** 保存观看记录（由详情页在退出时调用，仅播放成功且有进度后记录） */
    fun saveVodRecord() {
        val pos = mVideoView.currentPosition
        // 兜底：播放器可能处于非播放状态导致 getCurrentPosition 返回 0，
        // 使用 ViewModel 最近一次更新（周期任务）的进度
        val fallback = viewModel.currentPosition.value
        viewModel.saveVodRecord(if (pos > 0) pos else fallback)
    }

    /** 获取当前正在播放的真实地址（净化前的原始地址，详情页复制/推送用），无播放返回空字符串 */
    fun getPlayUrl(): String = session.originalPlayUrl.ifEmpty { session.currentPlayUrl }

    /** 是否正在播放（宿主判断画中画进入条件用） */
    fun isPlayingNow(): Boolean = mVideoView.isPlaying

    /** 后台播放类型：0=关闭 1=开启 2=画中画（宿主判断画中画进入条件用） */
    fun backgroundPlayType(): Int = viewModel.backgroundPlayType()

    /**
     * 详情页全屏/小窗切换时由宿主调用，调整音频面板样式：
     * 全屏显示标题+大波形，预览小窗隐藏标题+小波形。
     */
    fun setFullscreenMode(full: Boolean) {
        if (fullscreenMode == full) return
        fullscreenMode = full
        applyAudioPanelStyle()
    }

    /** 按全屏状态应用音频面板样式（标题显隐 + 波形尺寸） */
    private fun applyAudioPanelStyle() {
        val b = _binding ?: return
        b.audioTitle.visibility = if (fullscreenMode) View.VISIBLE else View.GONE
        val wave = b.audioWave
        val density = resources.displayMetrics.density
        val lp = wave.layoutParams
        lp.width = ((if (fullscreenMode) 160 else 96) * density).toInt()
        lp.height = ((if (fullscreenMode) 40 else 24) * density).toInt()
        wave.layoutParams = lp
    }

    // ------------------------------------------------------------------
    // 状态收集
    // ------------------------------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playerType.collect { type ->
                session.applyPlayerConfig(forcePlayerType = type)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scaleType.collect { scale ->
                mVideoView.setScreenScaleType(scale)
                mController.setScaleText(PlayerHelper.getScaleName(scale))
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.speed.collect { speed ->
                session.applySpeed(speed)
                mController.setSpeedText(speed)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playState.collect { state ->
                when (state) {
                    PlayState.PLAYING -> hideTip()
                    // 错误提示由 handlePlayError 展示具体原因
                    else -> Unit
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentEpisode.collect { episode ->
                episode?.name?.let { name ->
                    val title = viewModel.title.value
                    mController.setTitle(if (title.isNotEmpty()) "$title : $name" else name)
                }
                // 切歌/切集后同步媒体服务通知（后台播放时通知栏卡片/锁屏立即显示新歌）
                if (playServiceStarted) {
                    com.tvbox.kotlin.core.server.PlayService.start(requireContext(), buildVideoInfo())
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // 爬虫 playerContent 直接返回的 LRC 歌词：解析成功后即时显示（含视频/音频模式）
            viewModel.currentLrc.collect { lrc ->
                if (lrc.isNotEmpty() && isAdded) {
                    lrcView.setLyric(LrcParser.parse(lrc))
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.parseBeans.collect { list ->
                mController.setParseList(list)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentParse.collect { parse ->
                if (parse != null) {
                    val episodeUrl = viewModel.currentEpisode.value?.url
                    if (!episodeUrl.isNullOrEmpty()) session.loadAndPlay(episodeUrl)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collect { msg ->
                if (msg.isNotEmpty()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
        // 解析阶段提示：常规解析 / 嗅探 / 超级解析
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.resolveStage.collect { stage ->
                when (stage) {
                    PlayViewModel.RESOLVE_STAGE_PARSE -> showTip("正在解析播放地址", true, false)
                    PlayViewModel.RESOLVE_STAGE_SNIFF -> showTip("正在嗅探播放地址", true, false)
                    PlayViewModel.RESOLVE_STAGE_SUPER -> showTip("超级解析中", true, false)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 提示显示
    // ------------------------------------------------------------------

    private fun showTip(message: String, loading: Boolean, error: Boolean) {
        val view = view ?: return
        view.post {
            if (!isAdded) return@post
            playLoadTip?.text = message
            playLoadTip?.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            playLoading?.visibility = if (loading) View.VISIBLE else View.GONE
            playLoadError?.visibility = if (error) View.VISIBLE else View.GONE
        }
    }

    private fun hideTip() {
        val view = view ?: return
        view.post {
            playLoadTip?.visibility = View.GONE
            playLoading?.visibility = View.GONE
            playLoadError?.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // 注册通知栏/锁屏媒体卡片控制广播（后台播放时按钮事件经 PlayService 广播分发到本页）
        registerNotificationControlReceiver()
    }

    /** 注册通知栏/锁屏媒体卡片控制广播接收（与 PlayActivity 一致，PlayService 广播分发） */
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
            requireContext().registerReceiver(notificationControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(notificationControlReceiver, filter)
        }
    }

    /** 注销通知栏/锁屏媒体卡片控制广播接收 */
    private fun unregisterNotificationControlReceiver() {
        notificationControlReceiver?.let {
            runCatching { requireContext().unregisterReceiver(it) }
        }
        notificationControlReceiver = null
    }

    override fun onPause() {
        super.onPause()
        // 切后台前保存进度
        saveCurrentProgress()
        // 切后台前隐藏控制栏，回前台时不会残留白色焦点边框
        mController.hideBottom()
        // 后台播放开启（非画中画）时切后台不暂停：置位 surface 续播标志（GSY 在 surface 销毁时会自动暂停，
        // MyVideoView 据此补恢复保持音频播放）；关闭时暂停预览，回前台由 onResume 自动恢复
        val bgEnabled = viewModel.backgroundPlayType() == 1
        mVideoView.backgroundPlayEnabled = bgEnabled
        if (viewModel.backgroundPlayType() == 2) {
            // 画中画类型：是否进入 PiP 由宿主（全屏播放页 PlayActivity / 详情页 DetailActivity）决定。
            // 延迟确认（PiP 异步生效）：已进入 PiP（画面由系统保留）则保持播放；预览窗未进 PiP（无该能力）则暂停，
            // 避免误表现为"只有音频无画面"
            view?.postDelayed({
                val inPip = requireActivity().isInPictureInPictureMode
                if (isAdded && mVideoView.isPlaying && !inPip) {
                    mVideoView.pause()
                }
            }, 500L)
        } else if (!bgEnabled && mVideoView.isPlaying) {
            mVideoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回前台复位后台播放续播标志（surface 已重建，播放/暂停回归正常逻辑）
        mVideoView.backgroundPlayEnabled = false
        // 回前台恢复播放：非用户手动暂停且播放器处于暂停态（后台暂停/音频焦点被抢占）时自动恢复，
        // 避免回前台黑屏、需手动操作或误判为重新加载
        if (!userPaused && mVideoView.playState == PlayerConstants.STATE_PAUSED) {
            mVideoView.resume()
        }
        // 回前台隐藏控制栏，避免控制栏按钮焦点框残留
        mController.hideBottom()
    }

    override fun onStop() {
        super.onStop()
        // 预览窗后台播放：仅"后台播放"类型（非画中画）时启动媒体服务；
        // 画中画类型预览窗无该能力，切后台由 onPause 暂停，避免误表现为"只有音频无画面"
        if (viewModel.backgroundPlayType() == 1 && mVideoView.isPlaying && !playServiceStarted) {
            playServiceStarted = true
            com.tvbox.kotlin.core.server.PlayService.start(requireContext(), buildVideoInfo())
        }
    }

    /** 构造媒体服务所需的信息字符串 `title&&subtitle` */
    private fun buildVideoInfo(): String {
        val title = viewModel.title.value.ifEmpty { "TVBox" }
        val episode = viewModel.currentEpisode.value?.name.orEmpty()
        return "$title&&$episode"
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        mController.clearListener()
        unregisterNotificationControlReceiver()
        viewModel.stopProtocolTasks()
        lyricJob?.cancel()
        // 退出详情页：纯音频 + 后台播放开启 + 播放中 → 移交服务内播放器继续后台播放（歌曲保持）；
        // 视频/未开启后台播放 → 正常停止服务
        val keepBackground = audioModeActive && viewModel.backgroundPlayType() != 0 && mVideoView.isPlaying
        if (keepBackground) {
            com.tvbox.kotlin.core.server.PlayService.startBackgroundAudio(
                requireContext(),
                session.currentPlayUrl,
                session.currentHeaders,
                mVideoView.currentPosition,
                viewModel.title.value.ifEmpty { "TVBox" },
                viewModel.currentEpisode.value?.name.orEmpty()
            )
        } else if (playServiceStarted) {
            playServiceStarted = false
            com.tvbox.kotlin.core.server.PlayService.stop(requireContext())
        }
        // 退出界面：强制取消弹幕加载（阻塞网络任务结束后凭代际丢弃结果，不操作已销毁的视图）
        danmuController.cancelLoad()
        // 退出前保存进度
        saveCurrentProgress()
        try {
            mVideoView.release()
        } catch (_: Throwable) {
            // 释放异常忽略
        }
        super.onDestroyView()
        _binding = null
    }
}
