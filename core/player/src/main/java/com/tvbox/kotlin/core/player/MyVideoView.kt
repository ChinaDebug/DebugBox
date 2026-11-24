package com.tvbox.kotlin.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvbox.kotlin.core.player.effect.anime4k.EffectCanvas
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * Media3 直控播放视图。
 *
 * 内部持有 [ExoPlayer]，自绘 Surface/Texture 渲染层，生命周期回调统一映射为
 * [PlayerConstants.STATE_*] 状态机并对外暴露监听；控制层与音频面板由宿主注入。
 * 对外 API 保持原有封装形式，业务层无需感知内核实现。
 */
class MyVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 当前播放状态（沿用 [PlayerConstants.STATE_*] 常量语义） */
    var playState: Int = PlayerConstants.STATE_IDLE
        private set

    //region 事件监听（播放器生命周期回调，由业务层转发给控制器）
    /** 播放状态变化回调 */
    var onPlayStateChange: ((Int) -> Unit)? = null
    /** 进度回调 (duration, position) */
    var onProgressChange: ((Long, Long) -> Unit)? = null
    /** 缓冲进度回调（百分比 0-100，-1 表示内核不支持） */
    var onBufferedChange: ((Int) -> Unit)? = null
    /** 实时网速回调（KB/s） */
    var onNetSpeedChange: ((Long) -> Unit)? = null
    /** 准备完成回调 */
    var onPreparedListener: (() -> Unit)? = null
    /** 播放完成回调 */
    var onCompletionListener: (() -> Unit)? = null
    /** 播放出错回调 (code, extra) */
    var onErrorListener: ((Int, Int) -> Unit)? = null
    /** 视频尺寸变化回调 (width, height) */
    var onVideoSizeChangedListener: ((Int, Int) -> Unit)? = null
    //endregion

    /** 是否纯音频播放（音频文件或媒体流无视频轨），宿主据此切换音频模式 UI */
    var audioOnly = false
        private set

    /** 纯音频模式变化回调（检测到/手动切换时触发） */
    var onAudioModeChanged: ((Boolean) -> Unit)? = null

    /** 暂停位置（毫秒）：恢复播放时位置失效的兜底来源 */
    private var pausedPosition = 0L

    /** 当前播放请求头 */
    var headers: Map<String, String> = emptyMap()
        private set

    //region 内核与渲染层
    /** 直控 ExoPlayer（未创建时为 null） */
    var exoPlayer: ExoPlayer? = null
        private set

    /** 轨道选择器：业务层做音轨/视轨/字幕切换 */
    var trackSelector: DefaultTrackSelector? = null
        private set

    /**
     * 画面增强效果链（如 Anime4K）。
     * Media3 在 prepare 时构建效果管线，须在每次开始播放前设置，播放中变更不生效。
     */
    var videoEffects: List<Effect> = emptyList()

    private var currentUrl: String? = null
    private var overrideExtension: String? = null

    /** 渲染类型（[PlayerConstants.RENDER_SURFACE]/[PlayerConstants.RENDER_TEXTURE]） */
    private var renderType: Int = PlayerConstants.RENDER_SURFACE

    /** 渲染宿主：承载按比例缩放的画面层 */
    private val renderHost: FrameLayout

    /** 比例容器：按缩放模式决定画面实际尺寸 */
    private val ratioLayout: RatioLayout

    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null

    /** 视频宽高比（0 表示未知） */
    private var videoAspect: Float = 0f

    private val speedMeter = NetworkSpeedMeter()
    private var mSpeed: Float = 1f
    //endregion

    /** 弹幕视图（手动同步 seek/pause/resume/release） */
    private var danmuView: DanmakuView? = null

    /** 暂停态 seek 后延迟补暂停的弹幕任务（DanmakuView.seekTo 走 resume 分支会移除 pause 消息） */
    private val danmuRepauseRunnable = Runnable {
        if (playState == PlayerConstants.STATE_PAUSED) danmuView?.pause()
    }

    /** 外部挂载的控制层 */
    private var mController: View? = null

    /** 音频模式面板（宿主注入：波形/歌词层），仅在纯音频模式显示 */
    private var audioPanel: View? = null

    /** 音频模式兜底检测任务：准备完成后延迟确认无视频轨 */
    private var audioCheckRunnable: Runnable? = null

    /** 首次 READY 是否已通知（区分首帧准备与后续重新缓冲） */
    private var preparedNotified = false

    /** seek 目标（毫秒）：异步跳转/重新缓冲期间进度上报用目标值修正 */
    private var lastSeekTarget = -1L
    private var seekPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            // 网速独立于播放进度刷新：加载/缓冲阶段也能拿到实时速率
            onNetSpeedChange?.invoke(speedMeter.sample())
            if (playState != PlayerConstants.STATE_IDLE &&
                playState != PlayerConstants.STATE_ERROR &&
                playState != PlayerConstants.STATE_PREPARING
            ) {
                val dur = duration.toLong()
                val rawPos = currentPosition
                // seek 未完成（重新缓冲）时用目标值覆盖，内核追上或目标超时长后解除
                val pos = if (seekPending && rawPos < lastSeekTarget && lastSeekTarget <= dur) {
                    lastSeekTarget
                } else {
                    rawPos
                }
                if (rawPos >= lastSeekTarget || lastSeekTarget > dur) {
                    seekPending = false
                }
                refreshDanmuClock(pos)
                onProgressChange?.invoke(dur, pos)
                onBufferedChange?.invoke(bufferedPercent())
            }
            mainHandler.postDelayed(this, PROGRESS_REFRESH_INTERVAL)
        }
    }

    init {
        // 播放视图无需焦点，按键完全由外部控制层处理
        isFocusable = false
        isFocusableInTouchMode = false

        renderHost = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        // 画面层按比例尺寸居中于宿主
        ratioLayout = RatioLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        }
        renderHost.addView(ratioLayout)
        addView(renderHost)
        buildRenderView()
    }

    //region 内核创建与渲染层装配
    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        // 轨道选择参数：能力保守时仍允许超限选择（避免全片无轨被拒），
        // 关闭多路自适应（电视端单路自适应更稳，且避免跨渲染器切轨失败）
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAllowMultipleAdaptiveSelections(false)
            )
        }
        val player = ExoPlayer.Builder(context, EngineRenderersFactory(context))
            .setTrackSelector(selector)
            .build()
        // 音频焦点交由内核处理；耳机拔出自动暂停
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(playerListener)
        exoPlayer = player
        trackSelector = selector
        bindRenderView(player)
        player.setPlaybackSpeed(mSpeed)
        return player
    }

    private fun bindRenderView(player: ExoPlayer) {
        surfaceView?.let { player.setVideoSurfaceView(it) }
        textureView?.let { player.setVideoTextureView(it) }
    }

    private fun buildRenderView() {
        ratioLayout.removeAllViews()
        surfaceView = null
        textureView = null
        if (renderType == PlayerConstants.RENDER_SURFACE) {
            val view = SurfaceView(context)
            ratioLayout.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            surfaceView = view
        } else {
            val view = TextureView(context)
            ratioLayout.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            textureView = view
        }
        exoPlayer?.let { bindRenderView(it) }
        ratioLayout.requestLayout()
    }

    /** 设置渲染类型（Surface/Texture），切换时重建画面层 */
    fun setRenderType(type: Int) {
        val normalized = if (type == PlayerConstants.RENDER_SURFACE) {
            PlayerConstants.RENDER_SURFACE
        } else {
            PlayerConstants.RENDER_TEXTURE
        }
        if (renderType == normalized && (surfaceView != null || textureView != null)) return
        renderType = normalized
        buildRenderView()
    }

    /**
     * 按比例容器：根据缩放模式与宽高比计算画面尺寸，FIT 留黑边、ZOOM 裁剪、FILL 拉伸。
     */
    private inner class RatioLayout(context: Context) : FrameLayout(context) {

        var resizeMode: Int = RESIZE_FIT
        var aspectRatio: Float = 0f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            var targetW = width
            var targetH = height
            val ar = if (aspectRatio > 0f) aspectRatio else videoAspect
            if (ar > 0f && width > 0 && height > 0) {
                when (resizeMode) {
                    RESIZE_FILL -> Unit
                    RESIZE_ZOOM -> {
                        if (width.toFloat() / height > ar) {
                            targetH = (width / ar).toInt()
                        } else {
                            targetW = (height * ar).toInt()
                        }
                    }
                    else -> {
                        if (width.toFloat() / height > ar) {
                            targetW = (height * ar).toInt()
                        } else {
                            targetH = (width / ar).toInt()
                        }
                    }
                }
            }
            setMeasuredDimension(targetW, targetH)
            val childW = MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY)
            val childH = MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(childW, childH)
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // 向 Anime4K 提供画布尺寸：决定是否需要 2x 放大链
            EffectCanvas.set(w, h)
        }
    }
    //endregion

    //region 播放器监听
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (playState == PlayerConstants.STATE_PAUSED) return
                    if (!preparedNotified) {
                        setPlayState(PlayerConstants.STATE_PREPARING)
                    } else {
                        setPlayState(PlayerConstants.STATE_BUFFERING)
                    }
                }
                Player.STATE_READY -> {
                    if (!preparedNotified) {
                        preparedNotified = true
                        onPreparedInternal()
                    } else if (playState != PlayerConstants.STATE_PAUSED &&
                        playState != PlayerConstants.STATE_PLAYBACK_COMPLETED
                    ) {
                        setPlayState(PlayerConstants.STATE_PLAYING)
                    }
                }
                Player.STATE_ENDED -> {
                    pausedPosition = 0
                    setPlayState(PlayerConstants.STATE_PLAYBACK_COMPLETED)
                    onCompletionListener?.invoke()
                    danmuView?.release()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!preparedNotified) return
            if (isPlaying) {
                if (playState != PlayerConstants.STATE_PLAYING) {
                    setPlayState(PlayerConstants.STATE_PLAYING)
                }
            } else if (playState == PlayerConstants.STATE_PLAYING && exoPlayer?.playWhenReady == false) {
                setPlayState(PlayerConstants.STATE_PAUSED)
            }
            // 投屏等外部直接控制内核时不会走 pause()/resume()，这里按内核播放状态同步弹幕时钟与渲染，
            // 恢复时按视频位置重新对齐，避免弹幕停在暂停点或持续走钟导致错位
            refreshDanmuClock()
            if (haveDanmu()) {
                if (isPlaying) {
                    danmuView?.resume()
                    danmuView?.seekTo(currentPosition)
                    refreshDanmuClock()
                } else {
                    danmuView?.pause()
                }
            }
        }

        override fun onRenderedFirstFrame() {
            if (playState != PlayerConstants.STATE_PAUSED) {
                setPlayState(PlayerConstants.STATE_PLAYING)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoAspect = if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                0f
            }
            ratioLayout.requestLayout()
            if (videoSize.width > 0 && videoSize.height > 0 && audioOnly) {
                setAudioMode(false)
            }
            onVideoSizeChangedListener?.invoke(videoSize.width, videoSize.height)
        }

        override fun onPlayerError(error: PlaybackException) {
            setPlayState(PlayerConstants.STATE_ERROR)
            onErrorListener?.invoke(error.errorCode, 0)
        }
    }

    /** 首次就绪：断点跳转、纯音频兜底检测 */
    private fun onPreparedInternal() {
        setPlayState(PlayerConstants.STATE_PREPARED)
        val player = exoPlayer
        if (player != null && player.playWhenReady && player.isPlaying) {
            setPlayState(PlayerConstants.STATE_PLAYING)
        }
        if (pendingStartPosition > 0) {
            seekTo(pendingStartPosition)
        }
        scheduleAudioModeCheck()
        onPreparedListener?.invoke()
    }
    //endregion

    //region 播放控制与兼容 API
    /** 设置播放地址与请求头，随后调用 [start] 开始播放 */
    fun setUrl(url: String, headers: Map<String, String>?) {
        // 切换播放目标时清除上一周期的断点/暂停位置残留
        pendingStartPosition = 0
        pausedPosition = 0
        lastSeekTarget = -1
        seekPending = false
        preparedNotified = false
        speedMeter.reset()
        setAudioMode(isAudioFileUrl(url))
        // 丢弃空值请求头（空头会被部分 CDN 拒绝）
        val filtered = headers
            ?.filter { !it.value.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
        this.headers = filtered ?: emptyMap()
        currentUrl = url
        overrideExtension = MediaSources.inferExtension(url, null)
        // 复用的播放器需停止并清空旧媒体源，避免下次 prepare 残留上一集
        exoPlayer?.apply {
            stop()
            clearMediaItems()
        }
        playState = PlayerConstants.STATE_IDLE
    }

    fun setUrl(url: String) = setUrl(url, null)

    /**
     * 设置播放地址并在准备完成后跳转到指定位置（断点续播）。
     * prepare 前 seekTo 会被内核丢弃，故记录待跳转位置到首次就绪时执行。
     */
    fun setUrl(url: String, headers: Map<String, String>?, startPosition: Long) {
        setUrl(url, headers)
        if (startPosition > 0) pendingStartPosition = startPosition
    }

    /** 准备完成后待跳转的起始位置（毫秒），仅本次播放有效 */
    private var pendingStartPosition = 0L

    /** 开始/继续播放 */
    fun start() {
        val player = exoPlayer
        if (player == null || playState == PlayerConstants.STATE_IDLE ||
            playState == PlayerConstants.STATE_ERROR
        ) {
            if (pausedPosition > 0) pendingStartPosition = pausedPosition
            startPlayLogic()
        } else {
            player.play()
            setPlayState(PlayerConstants.STATE_PLAYING)
        }
    }

    /** 装配媒体源并开始准备（内部流程） */
    private fun startPlayLogic() {
        val url = currentUrl ?: return
        val player = ensurePlayer()
        preparedNotified = false
        setPlayState(PlayerConstants.STATE_PREPARING)
        player.setMediaSource(MediaSources.create(context, url, headers, overrideExtension))
        // 效果链须在 prepare 前装配（空列表用于清除上一集残留效果）
        player.setVideoEffects(videoEffects)
        player.prepare()
        player.play()
    }

    fun pause() {
        val player = exoPlayer ?: return
        val position = player.currentPosition
        if (position > 0) pausedPosition = position
        player.pause()
        if (playState != PlayerConstants.STATE_IDLE && playState != PlayerConstants.STATE_ERROR) {
            setPlayState(PlayerConstants.STATE_PAUSED)
        }
        refreshDanmuClock()
        if (haveDanmu()) danmuView?.pause()
    }

    fun resume() {
        val player = exoPlayer ?: return
        if (player.currentPosition <= 0 && pausedPosition > 0) {
            player.seekTo(pausedPosition)
        }
        player.play()
        setPlayState(PlayerConstants.STATE_PLAYING)
        refreshDanmuClock()
        if (haveDanmu()) {
            // 暂停期间弹幕时钟已停走，恢复时必须按视频位置重新对齐，否则投屏暂停后弹幕会卡住/错位
            danmuView?.resume()
            danmuView?.seekTo(currentPosition)
            refreshDanmuClock()
        }
    }

    /**
     * 跳转到指定位置并同步弹幕时钟。
     * 暂停态下 seek 后延迟补一次 pause（DanmakuView.seekTo 走 resume 分支会移除 pause 消息）。
     */
    fun seekTo(pos: Long) {
        val player = exoPlayer ?: return
        player.seekTo(pos)
        lastSeekTarget = pos
        seekPending = true
        if (playState == PlayerConstants.STATE_PAUSED) {
            pausedPosition = pos
        }
        // 手动 seek 到其他位置时断点目标失效（onPrepared 的断点 seek 除外）
        if (pendingStartPosition > 0 && pos != pendingStartPosition) {
            pendingStartPosition = 0
        }
        refreshDanmuClock(pos)
        if (haveDanmu()) {
            danmuView?.seekTo(pos)
            if (playState == PlayerConstants.STATE_PAUSED) {
                mainHandler.removeCallbacks(danmuRepauseRunnable)
                mainHandler.postDelayed(danmuRepauseRunnable, DANMU_SEEK_REPAUSE_DELAY)
            }
        }
    }

    /** 弹幕起始位置（毫秒）：断点 seek 异步完成前 currentPosition 仍是旧值，按断点目标启动 */
    fun danmuStartPosition(): Long {
        val current = currentPosition
        return if (pendingStartPosition > 0 && current < pendingStartPosition) {
            pendingStartPosition
        } else {
            current
        }
    }

    /** 当前播放位置（毫秒） */
    val currentPosition: Long get() = exoPlayer?.currentPosition?.coerceAtLeast(0) ?: 0

    /** 视频总时长（毫秒），未知返回 0 */
    val duration: Int
        get() {
            val d = exoPlayer?.duration ?: 0L
            return if (d == C.TIME_UNSET || d < 0) 0 else d.toInt()
        }

    /** 是否正在播放 */
    val isPlaying: Boolean get() = exoPlayer?.isPlaying ?: false

    /** 视频尺寸 [宽, 高]，未就绪返回 null */
    val videoSize: IntArray?
        get() {
            val size = exoPlayer?.videoSize ?: return null
            return if (size.width > 0 && size.height > 0) {
                intArrayOf(size.width, size.height)
            } else {
                null
            }
        }

    /** 实时网速（KB/s） */
    val tcpSpeed: Long get() = speedMeter.sample()

    /** 设置播放倍速 */
    fun setSpeed(speed: Float) {
        mSpeed = speed
        exoPlayer?.setPlaybackSpeed(speed)
        // 倍速变更：按当前真实位置重建弹幕时钟基准
        refreshDanmuClock()
    }

    fun getSpeed(): Float = mSpeed

    /** 设置画面缩放类型，取值参考 [PlayerConstants.SCREEN_SCALE_*] */
    fun setScreenScaleType(scale: Int) {
        when (scale) {
            PlayerConstants.SCREEN_SCALE_16_9 -> {
                ratioLayout.resizeMode = RESIZE_FIT
                ratioLayout.aspectRatio = 16f / 9f
            }
            PlayerConstants.SCREEN_SCALE_4_3 -> {
                ratioLayout.resizeMode = RESIZE_FIT
                ratioLayout.aspectRatio = 4f / 3f
            }
            PlayerConstants.SCREEN_SCALE_MATCH_PARENT -> {
                ratioLayout.resizeMode = RESIZE_FILL
                ratioLayout.aspectRatio = 0f
            }
            PlayerConstants.SCREEN_SCALE_CENTER_CROP -> {
                ratioLayout.resizeMode = RESIZE_ZOOM
                ratioLayout.aspectRatio = 0f
            }
            else -> {
                ratioLayout.resizeMode = RESIZE_FIT
                ratioLayout.aspectRatio = 0f
            }
        }
        ratioLayout.requestLayout()
    }

    /** 挂载外部控制层（覆盖在渲染层之上，占满全屏） */
    fun setVideoController(controller: View?) {
        if (mController === controller) return
        mController?.let { removeView(it) }
        mController = controller
        if (controller != null) {
            addView(
                controller,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
    }

    /**
     * 设置/切换纯音频模式（自动检测或用户手动切换）。
     * 切换时同步音频面板显隐并回调，供宿主收起弹幕/字幕等视频元素。
     */
    fun setAudioMode(mode: Boolean) {
        if (audioOnly == mode) return
        audioOnly = mode
        audioPanel?.visibility = if (mode) View.VISIBLE else View.GONE
        onAudioModeChanged?.invoke(mode)
    }

    /** 挂载音频模式面板（波形/歌名/歌词层）：插到渲染层与控制层之间，不遮挡控制栏 */
    fun setAudioPanel(panel: View?) {
        if (audioPanel === panel) return
        audioPanel?.let { removeView(it) }
        audioPanel = panel
        if (panel != null) {
            // 面板来自宿主布局根，先脱离原父容器再挂载，避免 "already has a parent"
            (panel.parent as? ViewGroup)?.removeView(panel)
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            val controller = mController
            if (controller != null && indexOfChild(controller) > 0) {
                addView(panel, indexOfChild(controller).coerceAtLeast(0), lp)
            } else {
                addView(panel, lp)
            }
            panel.visibility = if (audioOnly) View.VISIBLE else View.GONE
        }
    }

    /** 释放播放器与弹幕资源，状态复位为 IDLE */
    fun release() {
        stopProgressLoop()
        danmuView?.release()
        danmuClockBaseMs = 0L
        danmuClockBaseUptime = 0L
        danmuClockPlaying = false
        audioCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        audioCheckRunnable = null
        audioOnly = false
        audioPanel?.visibility = View.GONE
        pausedPosition = 0
        pendingStartPosition = 0
        preparedNotified = false
        speedMeter.reset()
        exoPlayer?.let {
            it.removeListener(playerListener)
            it.release()
        }
        exoPlayer = null
        trackSelector = null
        playState = PlayerConstants.STATE_IDLE
    }
    //endregion

    //region 缓冲与音频模式检测
    /** 当前缓冲百分比（0-100），时长未知返回 -1 */
    private fun bufferedPercent(): Int {
        val player = exoPlayer ?: return -1
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return -1
        val buffered = (player.bufferedPosition * 100 / dur).toInt()
        return buffered.coerceIn(0, 100)
    }

    private fun scheduleAudioModeCheck() {
        audioCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        if (audioOnly) return
        val r = Runnable {
            audioCheckRunnable = null
            if (!audioOnly &&
                (playState == PlayerConstants.STATE_PLAYING ||
                    playState == PlayerConstants.STATE_PREPARED)
            ) {
                if (hasNoVideoTrack()) setAudioMode(true)
            }
        }
        audioCheckRunnable = r
        mainHandler.postDelayed(r, AUDIO_MODE_CHECK_DELAY_MS)
    }

    /** 是否无视频轨道：有音频轨且无视频轨判定为纯音频 */
    private fun hasNoVideoTrack(): Boolean {
        val player = exoPlayer ?: return false
        var hasVideo = false
        var hasAudio = false
        for (group in player.currentTracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> hasVideo = true
                C.TRACK_TYPE_AUDIO -> hasAudio = true
            }
        }
        return hasAudio && !hasVideo
    }
    //endregion

    //region 弹幕集成（手动同步，DanmuView 由业务层创建）
    fun setDanmuView(view: DanmakuView) {
        danmuView = view
    }

    fun getDanmuView(): DanmakuView? = danmuView

    private fun haveDanmu(): Boolean = danmuView?.isPrepared == true

    /** 弹幕时钟基准：主线程采样，弹幕线程据此推算位置（Media3 禁止跨线程访问内核） */
    @Volatile
    private var danmuClockBaseMs = 0L

    @Volatile
    private var danmuClockBaseUptime = 0L

    @Volatile
    private var danmuClockPlaying = false

    /**
     * 线程安全的播放位置推算（毫秒）：以最近一次主线程采样为基准按当前倍速推进。
     * 未采样过返回 0，调用方据此跳过校准。
     */
    fun danmuClockPosition(): Long {
        if (danmuClockBaseUptime == 0L) return 0L
        if (!danmuClockPlaying) return danmuClockBaseMs
        val elapsed = SystemClock.uptimeMillis() - danmuClockBaseUptime
        return danmuClockBaseMs + (elapsed * mSpeed).toLong()
    }

    /** 刷新弹幕时钟基准（需主线程） */
    private fun refreshDanmuClock(position: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (playState == PlayerConstants.STATE_IDLE ||
            playState == PlayerConstants.STATE_PREPARING
        ) {
            return
        }
        danmuClockBaseMs = position ?: currentPosition
        danmuClockBaseUptime = SystemClock.uptimeMillis()
        danmuClockPlaying = isPlaying
    }
    //endregion

    //region 进度刷新
    fun startProgressLoop() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    fun stopProgressLoop() {
        mainHandler.removeCallbacks(progressRunnable)
    }
    //endregion

    private fun setPlayState(state: Int) {
        if (playState != state) {
            playState = state
            onPlayStateChange?.invoke(state)
        }
    }

    companion object {
        private const val PROGRESS_REFRESH_INTERVAL = 300L
        private const val DANMU_SEEK_REPAUSE_DELAY = 80L
        private const val AUDIO_MODE_CHECK_DELAY_MS = 500L

        /** 画面缩放模式：等比适配 / 拉伸填满 / 裁剪填满 */
        private const val RESIZE_FIT = 0
        private const val RESIZE_FILL = 1
        private const val RESIZE_ZOOM = 2

        /** 纯音频扩展名：命中即按纯音频模式播放 */
        private val AUDIO_EXTS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "ape", "wma", "mka", "amr"
        )

        /** 判断 URL 是否为纯音频文件（按路径扩展名），query 参数不影响判定 */
        fun isAudioFileUrl(url: String): Boolean {
            val path = runCatching { Uri.parse(url).path }.getOrNull()
                ?: return AUDIO_EXTS.any { url.lowercase().contains(".$it") }
            return AUDIO_EXTS.any { path.lowercase().endsWith(".$it") }
        }
    }
}
