package com.tvbox.kotlin.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.shuyu.gsyvideoplayer.R
import com.shuyu.gsyvideoplayer.player.IPlayerManager
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import master.flame.danmaku.ui.widget.DanmakuView
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/**
 * 基于 GSYVideoPlayer 的统一播放视图。
 *
 * 底层内核由 GSY 的 PlayerManager 体系提供 Media3(Exo)，
 * 并将 GSY 生命周期回调映射为统一状态机（[PlayerConstants.STATE_*]），
 * 通过监听器暴露给控制层与业务层。
 */
class MyVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : GSYVideoPlayer(context, attrs, defStyleAttr) {

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
    /** 实时网速回调（KB/s，GSY TrafficStats 统计）：进度循环中无条件刷新，加载/缓冲阶段也能实时拿到速率 */
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

    /** 是否纯音频播放（音频文件或媒体流无视频轨），宿主据此切换音频模式 UI（波形/歌名/歌词） */
    var audioOnly = false
        private set

    /** 纯音频模式变化回调（检测到/手动切换时触发） */
    var onAudioModeChanged: ((Boolean) -> Unit)? = null

    /** 音频模式面板（宿主注入：覆盖视频画面的波形/歌词层），仅在纯音频模式显示 */
    private var audioPanel: View? = null

    /**
     * 后台播放开关：切后台（Window Surface 销毁）时保持播放（GSY 在 surface 销毁后会自动暂停，
     * 此标志开启时由 [onSurfaceDestroyed] 补恢复，音频继续输出）。由业务层在启用后台播放时置位。
     */
    var backgroundPlayEnabled = false

    /**
     * 暂停位置（毫秒）。
     * GSY 恢复播放依赖内部 mCurrentPosition 回跳，但该值仅在暂停时处于播放态才记录，
     * 且长时间暂停后内核位置可能失效为 0；此字段作为恢复位置的兜底来源。
     */
    private var pausedPosition = 0L

    /** 音频模式兜底检测任务：onPrepared 后延迟确认无视频轨 */
    private var audioCheckRunnable: Runnable? = null

    /** 当前播放请求头 */
    var headers: Map<String, String> = emptyMap()

    /** Surface 切换错误抑制截止时间戳：Surface 销毁后一段时间内的 onError 视为 Surface 切换伪错误 */
    private var surfaceErrorGuardUntil = 0L

    /** seek 目标（毫秒）：seek 后内核异步跳转、重新缓冲期间 getCurrentPosition 仍返回旧位置，
     *  进度上报时若内核位置落后于该目标则用目标值，保证进度条指示与影片实际跳转位置一致 */
    private var lastSeekTarget = -1L
    /** seek 是否尚未被内核追上（内核位置 < 目标时为 true）；一旦内核位置到达目标即解除，
     *  避免 seek 目标超过媒体时长被 clamp 后进度条长期锁死在目标位置 */
    private var seekPending = false

    /** 当前播放倍速（私有字段，对外经 override 的 getSpeed/setSpeed 暴露，避免与 GSY 同名方法 JVM 冲突） */
    private var mSpeed: Float = 1f

    init {
        // 播放视图无需焦点，按键完全由外部控制层处理，避免抢占详情页预览窗拦截层的焦点
        isFocusable = false
        isFocusableInTouchMode = false
        // 音频焦点丢失时不释放播放器，仅暂停，回前台保持暂停画面不重载
        setReleaseWhenLossAudio(false)
    }

    /** 弹幕视图（手动同步 seek/pause/resume/release） */
    private var danmuView: DanmakuView? = null

    /**
     * 暂停态 seek 后重新暂停弹幕的延迟任务。
     * DanmakuFlameMaster 的 DrawHandler 处理 seek 消息时会走 resume 分支，
     * 第一步 removeMessages(PAUSE) 会把紧随其后的 pause 消息从队列删除，
     * 导致暂停态 seek 后弹幕恢复渲染；须等 seek 消息处理完成后再补一次 pause。
     */
    private val danmuRepauseRunnable = Runnable {
        if (playState == PlayerConstants.STATE_PAUSED) danmuView?.pause()
    }

    /** 音轨切换进行中标志：PlayerMenuHelper 切换音轨时置位，onError(4001) 据此判定为切换引发（仅硬解兜底提示） */
    @Volatile
    var audioTrackSwitching = false

    /** 外部挂载的控制层 */
    private var mController: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            // 网速独立于播放进度刷新：加载/缓冲（PREPARING/BUFFERING）阶段
            // 进度回调被排除，但迷你顶栏/缓冲网速仍需实时速率
            onNetSpeedChange?.invoke(getNetSpeed())
            if (playState != PlayerConstants.STATE_IDLE &&
                playState != PlayerConstants.STATE_ERROR &&
                playState != PlayerConstants.STATE_PREPARING
            ) {
                val dur = getDuration()
                val rawPos = getCurrentPositionWhenPlaying()
                // seek 目标修正：内核异步 seek 未完成（重新缓冲）时 getCurrentPosition 返回旧位置，
                // 用 seek 目标覆盖，保证进度条指示与实际跳转位置一致；
                // 内核到达目标或目标超时长（会被 clamp）后解除
                val pos = if (seekPending && rawPos < lastSeekTarget && lastSeekTarget <= dur) {
                    lastSeekTarget
                } else {
                    rawPos
                }
                if (rawPos >= lastSeekTarget || lastSeekTarget > dur) {
                    seekPending = false
                }
                // 弹幕时钟基准与进度同源刷新（pos 已含 seek 目标修正），
                // 弹幕线程据此按倍速推算位置，校准倍速/缓冲造成的时钟漂移
                refreshDanmuClock(pos)
                onProgressChange?.invoke(dur, pos)
                onBufferedChange?.invoke(getBufferedPercent())
            }
            mainHandler.postDelayed(this, PROGRESS_REFRESH_INTERVAL)
        }
    }

    /** 缓冲百分比反射方法缓存：IPlayerManager.getBufferedPercentage（GSY v13 各内核均实现） */
    private var bufferedMethod: java.lang.reflect.Method? = null

    /**
     * 获取当前缓冲百分比（0-100），内核不支持或异常时返回 -1。
     * 反射调用避免对 GSY 内部接口的直接依赖，接口变动时自动降级不影响播放。
     */
    private fun getBufferedPercent(): Int {
        return try {
            val manager = getGSYVideoManager() ?: return -1
            val method = bufferedMethod
                ?: manager.javaClass.getMethod("getBufferedPercentage").also { bufferedMethod = it }
            (method.invoke(manager) as? Number)?.toInt() ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    //region GSY 抽象方法实现（播放逻辑与 UI 空实现，UI 由外部控制器接管）
    /**
     * 使用同名覆盖的精简布局（模块内资源优先于 GSY 库），
     * 仅保留渲染必需控件，移除 GSY 自带左上角返回按钮与中央播放/暂停按钮。
     */
    override fun getLayoutId(): Int = R.layout.video_layout_standard

    /** 开始播放：置为准备状态后走 GSY 内部 prepare 流程 */
    override fun startPlayLogic() {
        setPlayState(PlayerConstants.STATE_PREPARING)
        prepareVideo()
    }

    // GSY 内置控制器 UI 全部留空，避免与自定义控制器冲突
    override fun showWifiDialog() = Unit
    override fun showProgressDialog(
        deltaX: Float,
        seekTime: String,
        seekTimePosition: Long,
        totalTime: String,
        totalTimeDuration: Long
    ) = Unit

    override fun dismissProgressDialog() = Unit
    override fun showVolumeDialog(deltaY: Float, volumePercent: Int) = Unit
    override fun dismissVolumeDialog() = Unit
    override fun showBrightnessDialog(percent: Float) = Unit
    override fun dismissBrightnessDialog() = Unit
    override fun onClickUiToggle(e: MotionEvent) = Unit
    override fun hideAllWidget() = Unit
    override fun changeUiToNormal() = Unit
    override fun changeUiToPreparingShow() = Unit
    override fun changeUiToPlayingShow() = Unit
    override fun changeUiToPauseShow() = Unit
    override fun changeUiToError() = Unit
    override fun changeUiToCompleteShow() = Unit
    override fun changeUiToPlayingBufferingShow() = Unit
    //endregion

    //region 兼容 API
    /** 设置播放地址与请求头，随后调用 [start] 开始播放 */
    fun setUrl(url: String, headers: Map<String, String>?) {
        // 未显式指定断点时清除上一播放周期的断点残留，避免换集后 onPrepared 误 seek 到旧集位置
        pendingStartPosition = 0
        // 切换播放目标时清空暂停位置
        pausedPosition = 0
        // 按 URL 扩展名预判纯音频（mp3/flac/m4a/aac 等），立即切换音频模式 UI；
        // 无视频轨的媒体流（如纯音频 m3u8）由 onPrepared 后兜底检测
        setAudioMode(isAudioFileUrl(url))
        // 丢弃空值请求头（空头会被部分 CDN 拒绝，空 UA 回退播放器默认 UA）
        val filtered = headers
            ?.filter { !it.value.isNullOrBlank() }
            ?.takeIf { it.isNotEmpty() }
        this.headers = filtered ?: emptyMap()
        // 扩展名在 query 中的 m3u8/dash 地址需强制按对应类型播放（Exo 按路径扩展名推断会误判）
        setOverrideExtension(inferOverrideExtension(url))
        // 注入 allowCrossProtocolRedirects 元数据头供 GSY Exo 内核启用跨协议重定向（非 HLS 路径）
        setUp(url, false, null, this.headers + ("allowCrossProtocolRedirects" to "true"), null)
    }

    /**
     * 推断需覆盖的流扩展名：
     * - 路径已有 .m3u8/.mpd 扩展名时返回 null（默认推断即可识别）；
     * - 路径无扩展名但 URL 含 m3u8/hls 或 mpd/dash 字样时返回对应扩展名强制按 HLS/DASH 播放。
     */
    private fun inferOverrideExtension(url: String): String? {
        if (url.startsWith("data:")) return null
        val lower = url.lowercase()
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull()?.lowercase()
        if (path != null && (path.endsWith(".m3u8") || path.endsWith(".mpd"))) return null
        return when {
            lower.contains("m3u8") || lower.contains("type=hls") || lower.contains("format=hls") -> "m3u8"
            lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd") ||
                lower.contains("type=dash") || lower.contains("format=dash") -> "mpd"
            else -> null
        }
    }

    fun setUrl(url: String) = setUrl(url, null)

    /**
     * 设置播放地址并在准备完成后跳转到指定起始位置（断点续播）。
     * [startPosition] 大于 0 时记录待跳转位置，由 [onPrepared] 在底层内核
     * 准备完成后执行 seekTo；直接调用 seekTo 在 prepare 完成前会被内核丢弃，
     * 导致断点续播总是从头开始。
     */
    fun setUrl(url: String, headers: Map<String, String>?, startPosition: Long) {
        // 先走无断点重载清除旧残留，再设置本次断点
        setUrl(url, headers)
        if (startPosition > 0) pendingStartPosition = startPosition
    }

    /** 准备完成后待跳转的起始位置（毫秒），仅本次播放有效 */
    private var pendingStartPosition = 0L

    /** 开始/继续播放 */
    fun start() {
        when (playState) {
            PlayerConstants.STATE_PREPARED, PlayerConstants.STATE_PAUSED,
            PlayerConstants.STATE_BUFFERING, PlayerConstants.STATE_BUFFERED,
            PlayerConstants.STATE_PLAYING -> onVideoResume()
            else -> {
                // 内核已释放/复位时重新装配，起始位置取暂停位置
                if (pausedPosition > 0) pendingStartPosition = pausedPosition
                startPlayLogic()
            }
        }
    }

    fun pause() = onVideoPause()

    fun resume() = onVideoResume()

    /**
     * 跳转到指定位置并同步弹幕时钟（弹幕已加载时），保证拖动进度条后弹幕与视频同步。
     * 暂停态下额外同步 [mCurrentPosition]（GSY 恢复播放前会 seekTo 该字段，不写则
     * 恢复播放跳回暂停前位置）并在 seek 完成后延迟补一次 pause（DanmakuView.seekTo
     * 走 resume 分支会移除队列中的 pause 消息，直接 pause 无效）。
     */
    override fun seekTo(pos: Long) {
        super.seekTo(pos)
        // 记录 seek 目标：内核异步跳转/重新缓冲期间进度上报用目标值修正，避免进度条指示回跳
        lastSeekTarget = pos
        seekPending = true
        // 暂停态同步位置缓存：GSY 恢复播放前会按 mCurrentPosition 回跳
        if (playState == PlayerConstants.STATE_PAUSED) {
            mCurrentPosition = pos
            pausedPosition = pos
        }
        // 手动 seek 到其他位置时断点目标失效（onPrepared 的断点 seek 除外），避免弹幕误按旧断点同步
        if (pendingStartPosition > 0 && pos != pendingStartPosition) {
            pendingStartPosition = 0
        }
        // 按 seek 目标重建弹幕时钟基准：不必等下一次进度刷新，弹幕立即从新位置校准
        refreshDanmuClock(pos)
        if (haveDanmu()) {
            danmuView?.seekTo(pos)
            if (playState == PlayerConstants.STATE_PAUSED) {
                // seek 消息走 resume 分支会恢复弹幕渲染，等处理完成后再补 pause
                mainHandler.removeCallbacks(danmuRepauseRunnable)
                mainHandler.postDelayed(danmuRepauseRunnable, DANMU_SEEK_REPAUSE_DELAY)
            }
        }
    }

    /** 弹幕起始位置（毫秒）：断点 seek 异步完成前 currentPosition 仍是旧值，弹幕按断点目标启动 */
    fun danmuStartPosition(): Long {
        val current = getCurrentPositionWhenPlaying()
        return if (pendingStartPosition > 0 && current < pendingStartPosition) {
            pendingStartPosition
        } else {
            current
        }
    }

    /** 当前播放位置（毫秒） */
    val currentPosition: Long get() = getCurrentPositionWhenPlaying()

    /** 是否正在播放 */
    val isPlaying: Boolean get() = getGSYVideoManager()?.isPlaying() ?: false

    /** 底层播放内核（GSY IPlayerManager，当前为 Exo(Media3) 内核） */
    val mediaPlayer: IPlayerManager?
        get() = getGSYVideoManager()?.getPlayer()

    /** 视频尺寸 [宽, 高]，未就绪返回 null */
    val videoSize: IntArray?
        get() {
            val w = getGSYVideoManager()?.getVideoWidth() ?: 0
            val h = getGSYVideoManager()?.getVideoHeight() ?: 0
            return if (w > 0 && h > 0) intArrayOf(w, h) else null
        }

    /** 实时网速（字节/秒） */
    val tcpSpeed: Long get() = getNetSpeed().toLong()

    /** 设置播放倍速（GSY 内部同时作用于已创建与后续播放器） */
    override fun setSpeed(speed: Float) {
        mSpeed = speed
        super.setSpeed(speed)
        // 倍速变更：按当前真实位置重建弹幕时钟基准，避免推算沿用旧倍速产生偏差
        refreshDanmuClock()
    }

    override fun getSpeed(): Float = mSpeed

    /** 设置画面缩放类型，取值参考 [PlayerConstants.SCREEN_SCALE_*] */
    fun setScreenScaleType(scale: Int) {
        val gsyType = when (scale) {
            PlayerConstants.SCREEN_SCALE_16_9 -> GSYVideoType.SCREEN_TYPE_16_9
            PlayerConstants.SCREEN_SCALE_4_3 -> GSYVideoType.SCREEN_TYPE_4_3
            // 拉伸填满
            PlayerConstants.SCREEN_SCALE_MATCH_PARENT -> GSYVideoType.SCREEN_MATCH_FULL
            // 原始比例完整显示
            PlayerConstants.SCREEN_SCALE_ORIGINAL -> GSYVideoType.SCREEN_TYPE_DEFAULT
            // 裁边填满
            PlayerConstants.SCREEN_SCALE_CENTER_CROP -> GSYVideoType.SCREEN_TYPE_FULL
            else -> GSYVideoType.SCREEN_TYPE_DEFAULT
        }
        GSYVideoType.setShowType(gsyType)
    }

    /** 挂载外部控制层（覆盖在渲染层之上，占满全屏） */
    fun setVideoController(controller: View?) {
        if (mController === controller) return
        mController?.let { removeView(it) }
        mController = controller
        if (controller != null) {
            addView(
                controller,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /**
     * 设置/切换纯音频模式（宿主在自动检测到纯音频或用户手动切换时调用）。
     * 切换时同步音频面板显隐并回调 [onAudioModeChanged]，供宿主收起弹幕/字幕等视频元素。
     */
    fun setAudioMode(mode: Boolean) {
        if (audioOnly == mode) return
        audioOnly = mode
        audioPanel?.visibility = if (mode) View.VISIBLE else View.GONE
        onAudioModeChanged?.invoke(mode)
    }

    /**
     * 挂载音频模式面板（波形/歌名/歌词层）。
     * 面板插入到渲染层与控制层之间：覆盖无画面的视频 surface，且不遮挡控制栏。
     */
    fun setAudioPanel(panel: View?) {
        if (audioPanel === panel) return
        audioPanel?.let { removeView(it) }
        audioPanel = panel
        if (panel != null) {
            // 面板经 dataBinding 位于宿主布局根中（已有父容器），先脱离原父容器再挂载到播放器内部，
            // 否则 addView 抛 "The specified child already has a parent" 导致闪退
            (panel.parent as? ViewGroup)?.removeView(panel)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val controller = mController
            // 控制层已挂载时插到其之前，避免覆盖控制栏；未挂载时追加到末尾
            if (controller != null && indexOfChild(controller) > 0) {
                addView(panel, indexOfChild(controller).coerceAtLeast(0), lp)
            } else {
                addView(panel, lp)
            }
            panel.visibility = if (audioOnly) View.VISIBLE else View.GONE
        }
    }
    //endregion

    //region 弹幕集成（手动同步，DanmuView 由业务层创建）
    fun setDanmuView(view: DanmakuView) {
        danmuView = view
    }

    fun getDanmuView(): DanmakuView? = danmuView

    private fun haveDanmu(): Boolean = danmuView?.isPrepared == true

    /**
     * 弹幕时钟基准：主线程采样（进度刷新/seek/倍速变更时刷新），
     * 弹幕线程据此推算播放位置，避免在弹幕线程访问内核（Media3 要求主线程访问，否则抛异常）。
     */
    @Volatile
    private var danmuClockBaseMs = 0L

    @Volatile
    private var danmuClockBaseUptime = 0L

    @Volatile
    private var danmuClockPlaying = false

    /**
     * 线程安全的播放位置推算（毫秒），供弹幕时钟校准：
     * 以主线程最近一次采样为基准按当前倍速线性推进（倍速播放时弹幕时间轴随视频走），
     * 暂停时返回采样值（弹幕时钟冻结）。未采样过返回 0，调用方据此跳过校准。
     */
    fun danmuClockPosition(): Long {
        if (danmuClockBaseUptime == 0L) return 0L
        if (!danmuClockPlaying) return danmuClockBaseMs
        val elapsed = SystemClock.uptimeMillis() - danmuClockBaseUptime
        return danmuClockBaseMs + (elapsed * mSpeed).toLong()
    }

    /** 刷新弹幕时钟基准（需主线程：会读取内核进度与播放状态） */
    private fun refreshDanmuClock(position: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        // IDLE/PREPARING 阶段进度无意义（恒为 0），不采样：避免弹幕时钟被拉回开头
        if (playState == PlayerConstants.STATE_IDLE || playState == PlayerConstants.STATE_PREPARING) return
        danmuClockBaseMs = position ?: getCurrentPositionWhenPlaying()
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

    //region GSY 生命周期 → 状态机映射
    override fun onPrepared() {
        // 先置 PREPARED 再调 super：GSY 的 onPrepared 内部自动播放走 startAfterPrepared()
        // （直接 VideoManager.start()，不经过 onVideoResume），若停留 PREPARED 则主动同步为
        // PLAYING，避免状态卡在 PREPARED 导致 OK/回车无法暂停；若 GSY 未自动播放
        // （mStartAfterPrepared=false 会走 onVideoPause，状态已切为 PAUSED）则保持
        setPlayState(PlayerConstants.STATE_PREPARED)
        super.onPrepared()
        if (playState == PlayerConstants.STATE_PREPARED) {
            setPlayState(PlayerConstants.STATE_PLAYING)
        }
        // 内核就绪后再跳转断点位置（prepare 前 seekTo 会被内核丢弃）；
        // 断点位置保留至弹幕加载完成供 danmuStartPosition 使用，手动 seek/下次 setUrl 失效
        if (pendingStartPosition > 0) {
            seekTo(pendingStartPosition)
        }
        // 复位音轨切换标志（新播放周期开始，onError 不再归属上次切换）
        audioTrackSwitching = false
        // 纯音频兜底检测：视频源在 prepare 后很快有视频尺寸，纯音频源（无视频轨）尺寸恒为 0
        scheduleAudioModeCheck()
        onPreparedListener?.invoke()
    }

    /**
     * 纯音频兜底检测：URL 无音频扩展名但媒体流无视频轨（如纯音频 m3u8）时，
     * onPrepared 后基于底层轨道信息判定纯音频，进入音频模式。
     * 用 Exo 轨道判断：视频源必有 video track（与解码能力无关，硬解不支持的视频仍有视频轨），
     * 不会把慢解码/硬解失败"只有声音没画面"的视频误判为音频而闪现音频面板；
     * 非 Exo 内核无轨道接口时不判定（保持默认布局）。
     */
    private fun scheduleAudioModeCheck() {
        audioCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        if (audioOnly) return
        val r = Runnable {
            audioCheckRunnable = null
            if (!audioOnly &&
                (playState == PlayerConstants.STATE_PLAYING || playState == PlayerConstants.STATE_PREPARED)
            ) {
                if (hasNoVideoTrack()) setAudioMode(true)
            }
        }
        audioCheckRunnable = r
        mainHandler.postDelayed(r, AUDIO_MODE_CHECK_DELAY_MS)
    }

    /**
     * 是否无视频轨道（纯音频媒体流）：Exo 内核反射读取内部 ExoPlayer 的轨道信息，
     * 有音频轨且无视频轨判定为纯音频；非 Exo 内核或读取失败返回 false（不判定）。
     */
    private fun hasNoVideoTrack(): Boolean {
        return try {
            val manager = getGSYVideoManager() ?: return false
            val pm = manager.getPlayer() as? IjkExo2MediaPlayer ?: return false
            val field = IjkExo2MediaPlayer::class.java.getDeclaredField("mInternalPlayer")
            field.isAccessible = true
            val exo = field.get(pm) as? ExoPlayer ?: return false
            var hasVideo = false
            var hasAudio = false
            for (group in exo.currentTracks.groups) {
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> hasVideo = true
                    C.TRACK_TYPE_AUDIO -> hasAudio = true
                }
            }
            hasAudio && !hasVideo
        } catch (_: Throwable) {
            false
        }
    }

    override fun onVideoPause() {
        // GSY 仅在 isPlaying 时记录 mCurrentPosition，缓冲边界可能漏记，此处主动补记
        val position = getCurrentPositionWhenPlaying()
        super.onVideoPause()
        if (position > 0) {
            pausedPosition = position
            if (mCurrentPosition <= 0) mCurrentPosition = position
        }
        setPlayState(PlayerConstants.STATE_PAUSED)
        if (haveDanmu()) danmuView?.pause()
    }

    override fun onVideoResume() {
        // 内核位置读回 0 时以暂停位置修正，避免 GSY 按 0 回跳
        if (pausedPosition > 0 && getCurrentPositionWhenPlaying() <= 0) {
            mCurrentPosition = pausedPosition
        }
        super.onVideoResume()
        setPlayState(PlayerConstants.STATE_PLAYING)
        // 弹幕时钟恢复：已启动则 resume；未启动（弹幕 prepare 完成后由加载回调
        // 按当前进度 start）跳过，避免重复 start 重置弹幕时钟导致从头开始
        if (haveDanmu()) {
            danmuView?.resume()
        }
    }

    override fun onAutoCompletion() {
        super.onAutoCompletion()
        // 自然播完清空暂停位置，后续重播从头开始
        pausedPosition = 0
        setPlayState(PlayerConstants.STATE_PLAYBACK_COMPLETED)
        onCompletionListener?.invoke()
        danmuView?.release()
    }

    override fun onError(what: Int, extra: Int) {
        if (System.currentTimeMillis() < surfaceErrorGuardUntil) {
            return
        }
        // 4001 = 解码器初始化失败：仅硬解模式下切到无硬件解码器的音轨（如杜比/DTS）会在此失败。
        // PlayerMenuHelper 已按设备硬件能力预检拦截，此处为预检遗漏（能力声明与实际初始化不符）时的兜底提示
        if (what == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && audioTrackSwitching &&
            ConfigurableExo2PlayerManager.isOnlyHardwareMode
        ) {
            audioTrackSwitching = false
            Toast.makeText(
                context,
                "当前仅硬解模式不支持该音轨（杜比/DTS），请切换硬解优先或软解优先",
                Toast.LENGTH_SHORT
            ).show()
            // 继续走下方正常错误流程
        }
        super.onError(what, extra)
        setPlayState(PlayerConstants.STATE_ERROR)
        onErrorListener?.invoke(what, extra)
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface?): Boolean {
        if (playState != PlayerConstants.STATE_IDLE && playState != PlayerConstants.STATE_ERROR) {
            surfaceErrorGuardUntil = System.currentTimeMillis() + SURFACE_ERROR_GUARD_MS
        }
        return super.onSurfaceDestroyed(surface)
    }

    override fun onVideoSizeChanged() {
        super.onVideoSizeChanged()
        val w = getGSYVideoManager()?.getVideoWidth() ?: 0
        val h = getGSYVideoManager()?.getVideoHeight() ?: 0

        if (w > 0 && h > 0 && audioOnly) {
            setAudioMode(false)
        }
        onVideoSizeChangedListener?.invoke(w, h)
    }

    override fun onInfo(what: Int, extra: Int) {
        super.onInfo(what, extra)
        when (what) {
            MEDIA_INFO_BUFFERING_START, MEDIA_INFO_BUFFERING_START_LEGACY ->
                // 暂停状态下忽略缓冲回调，避免覆盖暂停状态
                if (playState != PlayerConstants.STATE_PAUSED) {
                    setPlayState(PlayerConstants.STATE_BUFFERING)
                }
            MEDIA_INFO_BUFFERING_END, MEDIA_INFO_VIDEO_RENDERING_START ->
                // 部分内核暂停后仍会回调 VIDEO_RENDERING_START，若强制置 PLAYING
                // 会把暂停状态覆盖掉，导致暂停条（右上角快进快退迷你条）被隐藏
                if (playState != PlayerConstants.STATE_PAUSED) {
                    setPlayState(PlayerConstants.STATE_PLAYING)
                }
        }
    }

    override fun release() {
        stopProgressLoop()
        danmuView?.release()
        // 复位弹幕时钟基准：避免下一次播放采样前沿用上一集的进度校准弹幕
        danmuClockBaseMs = 0L
        danmuClockBaseUptime = 0L
        danmuClockPlaying = false
        // 清理音轨切换标志，避免退出/释放后残留标志误触发恢复
        audioTrackSwitching = false
        // 清理纯音频检测任务并复位音频模式（宿主已销毁，不触发回调，仅隐藏面板）
        audioCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        audioCheckRunnable = null
        audioOnly = false
        audioPanel?.visibility = View.GONE
        // 释放后清空暂停位置，避免影响下一播放目标
        pausedPosition = 0
        // 释放后复位状态机：GSY 的 release 不会重置内部状态，
        // 若不复位 playState，下一次 start() 会误走 onVideoResume 空分支而卡在加载
        playState = PlayerConstants.STATE_IDLE
        super.release()
    }

    private fun setPlayState(state: Int) {
        if (playState != state) {
            playState = state
            onPlayStateChange?.invoke(state)
        }
    }
    //endregion

    companion object {
        private const val PROGRESS_REFRESH_INTERVAL = 300L

        /** Surface 切换错误抑制窗口（毫秒）：Surface 销毁后该时段内的 onError 视为切换伪错误忽略 */
        private const val SURFACE_ERROR_GUARD_MS = 3_000L
        /** 暂停态 seek 后重新暂停弹幕的延迟（毫秒）：
         *  需晚于 DrawHandler 处理完 seek 消息（其 resume 分支会移除 pause 消息） */
        private const val DANMU_SEEK_REPAUSE_DELAY = 80L

        /** 纯音频兜底检测延迟（毫秒）：onPrepared 后轨道信息已就绪（demux 阶段，不依赖解码），
         *  只需短暂等待 GSY 状态稳定即可基于轨道判断，纯音频流可快速进入音频模式 */
        private const val AUDIO_MODE_CHECK_DELAY_MS = 500L

        /** 后台播放 surface 销毁后补恢复的延迟（毫秒）：等待 GSY 完成暂停/释放 surface 引用后再恢复 */
        private const val SURFACE_PAUSE_RESUME_DELAY_MS = 120L

        /** 纯音频扩展名：命中即按纯音频模式播放（无需等待视频尺寸判定） */
        private val AUDIO_EXTS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "ape", "wma", "mka", "amr"
        )

        /** 判断 URL 是否为纯音频文件（按路径扩展名），query 参数不影响判定 */
        fun isAudioFileUrl(url: String): Boolean {
            val path = runCatching { Uri.parse(url).path }.getOrNull()
                ?: return AUDIO_EXTS.any { url.lowercase().contains(".$it") }
            return AUDIO_EXTS.any { path.lowercase().endsWith(".$it") }
        }

        /** MediaPlayer / Exo 标准缓冲事件码 */
        private const val MEDIA_INFO_BUFFERING_START = 701
        private const val MEDIA_INFO_BUFFERING_END = 702
        /** 旧内核 / 兼容值 */
        private const val MEDIA_INFO_BUFFERING_START_LEGACY = 2
        private const val MEDIA_INFO_VIDEO_RENDERING_START = 3
    }
}
