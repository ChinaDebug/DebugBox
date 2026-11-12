package com.tvbox.kotlin.feature.play.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.SubtitleHelper
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.widget.BatteryView
import com.tvbox.kotlin.feature.play.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放控制视图。
 */
class PlayerVodControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MSG_REFRESH_TIME = 1000
        private const val TIME_REFRESH_INTERVAL = 1000L
        /** 进度条最大值 */
        private const val SEEK_BAR_MAX = 1000

        /** 网速平滑保持时长（毫秒）：下载分片间隙的短瞬保持上次速率，避免闪动归 0（保持实时性） */
        private const val NET_SPEED_HOLD_MS = 1000L

        /** 卡顿缓冲检测阈值（毫秒）：播放中进度停滞超过该时长且存在下载速率时判定缓冲 */
        private const val STALL_DETECT_MS = 1000L

        /** 缓冲已满阈值（百分比）：低于该值视为缓冲数据不足，可判定真实缓冲 */
        private const val BUFFER_FULL_THRESHOLD = 100

        /** 控制栏显示消息 */
        private const val MSG_SHOW_MENU = 1002
        /** 控制栏隐藏消息 */
        private const val MSG_HIDE_MENU = 1003

        /** 手势调音量的系统 IPC 提交间隔（毫秒）：限频避免频繁 setStreamVolume 掉帧 */
        private const val VOLUME_COMMIT_INTERVAL = 80L
        /** 控制栏无操作自动隐藏时长（毫秒） */
        private const val CONTROL_BAR_SHOW_DURATION = 8000L
        /** 锁屏按钮自动隐藏时长（毫秒） */
        private const val LOCK_BTN_SHOW_DURATION = 3000L
        /** 控制栏显隐动画时长（毫秒） */
        private const val ANIM_DURATION = 250L
        /** 遥控左右键快进/快退步长（毫秒） */
        private const val SEEK_STEP = 10_000L
        /** 方向键长按快进：首按基础跳转（毫秒） */
        private const val SLIDE_BASE_STEP = 10_000L
        /** 方向键长按快进：按住期间起始推进速度（毫秒/秒），长按 1 秒约跳 30 秒 */
        private const val SLIDE_SPEED_PER_SEC = 20_000L
        /** 方向键长按快进：推进速度加速率（毫秒/秒/秒），按住越久推进越快 */
        private const val SLIDE_SPEED_ACCEL_PER_SEC_SQ = 6_000L
        /** 方向键长按快进：推进定时器间隔（毫秒），长按期间按该周期刷新跳转进度 */
        private const val SLIDE_TICK_INTERVAL = 100L
        /** 倍速长按判定延迟（毫秒） */
        private const val SPEED_LONG_PRESS_DELAY = 500L
        /** 片头/片尾跳过时间上限（秒），超过回绕为 0（600 = 10 分钟） */
        private const val MAX_SKIP_SECONDS = 600

        /** 横屏手势模式：尚未判定方向 */
        private const val GESTURE_NONE = 0
        /** 横屏手势模式：水平滑动快进/快退 */
        private const val GESTURE_SEEK = 1
        /** 横屏手势模式：左侧小范围区域上下滑动调节亮度 */
        private const val GESTURE_BRIGHTNESS = 2
        /** 横屏手势模式：右侧小范围区域上下滑动调节音量 */
        private const val GESTURE_VOLUME = 3
        /** 横屏手势模式：垂直滑动发生在屏幕中间区域，不响应亮度/音量 */
        private const val GESTURE_IGNORE = 4
        /** 横屏左右区域宽度占比：左 1/4 内上下滑动调亮度、右 1/4 内调音量，中间 1/2 垂直滑动不响应 */
        private const val GESTURE_SIDE_RATIO = 0.25f
        /** 水平滑动进度范围（毫秒）*/
        private const val SEEK_GESTURE_RANGE = 120_000L
        /** 屏幕边沿忽略手势的宽度（dp），避免顶部下拉通知栏/底部导航手势区误触亮度音量 */
        private const val GESTURE_EDGE_DP = 40
    }

    /** 控制事件回调，由宿主 Activity 实现 */
    interface VodControlListener {
        /** 播放/暂停切换 */
        fun onPlayPause()
        /** 跳转到指定位置（毫秒） */
        fun onSeekTo(position: Long)
        /** 下一集 */
        fun onPlayNext()
        /** 上一集 */
        fun onPlayPrevious()
        /** 点击倍速按钮 */
        fun onSpeedClick()
        /** 倍速按钮长按：进入临时最大档位快进，返回 true 表示已进入（松手时恢复原速并消费点击事件） */
        fun onSpeedLongPress(): Boolean
        /** 倍速按钮长按松手：恢复原速 */
        fun onSpeedLongPressRelease()
        /** 点击缩放按钮 */
        fun onScaleClick()
        /** 点击解码器按钮 */
        fun onPlayerClick()
        /** 点击字幕按钮 */
        fun onSubtitleClick()
        /** 点击弹幕按钮 */
        fun onDanmuClick()
        /** 点击音轨按钮 */
        fun onAudioClick()
        /** 点击视轨按钮 */
        fun onVideoClick()
        /** 点击投屏按钮 */
        fun onCastClick()
        /** 点击屏显按钮 */
        fun onScreenDisplayClick()
        /** 点击横竖屏切换按钮（仅竖屏画面视频时显示） */
        fun onOrientationClick()
        /** 点击重试按钮 */
        fun onRetryClick()
        /** 点击选集 */
        fun onSelectEpisode()
        /** 切换解析器 */
        fun onParseChanged(parse: ParseBean)
        /** 返回键处理，返回 true 表示控制器已消费 */
        fun onBackPressed(): Boolean
        /** 播放器准备完成 */
        fun onPrepared()
        /** 开始播放 */
        fun onPlaying()
        /** 播放出错 */
        fun onError(message: String)
        /** 播放完成 */
        fun onCompletion()
    }

    private var listener: VodControlListener? = null

    // 顶部信息栏
    private var tvTitleTop: TextView? = null
    private var tvPlaySpeedTopHide: TextView? = null
    /** 迷你顶栏网速单位 TextView（随数值切换 KB/s / MB/s） */
    private var tvMpbsTopHide: TextView? = null

    /** 上次非 0 网速（KB/s）与时间戳：用于平滑下载暂停的短瞬归 0 */
    private var lastNetSpeedKb = 0L
    private var lastNetSpeedTime = 0L
    private var tvResolution: TextView? = null
    private var tvTopRContainer: LinearLayout? = null
    private var tvSeekTime: TextView? = null
    private var tvSystemTime: TextView? = null
    private var tvSysTime: TextView? = null
    private var batteryView: BatteryView? = null

    // 屏显容器子项（网速数值与 Mbps 单位）
    private var netSpeedTopR: TextView? = null
    private var mpbsTopR: TextView? = null

    // 网速显示容器：迷你顶栏（缓冲/加载时显示），与屏显网速互斥显示
    private var tvSpeedTopHide: LinearLayout? = null

    /** 屏显容器是否至少有一个子项开启（applyScreenDisplay 计算，缓冲时临时隐藏） */
    private var screenDisplayAnyOn = false

    // 横竖屏切换按钮（视频为竖屏画面时显示）
    private var landscapePortraitBtn: View? = null
    private var lvPortraitImg: ImageView? = null

    // 顶部 / 底部控制栏容器
    private var topContainer: View? = null
    private var bottomContainer: View? = null
    /** 迷你顶栏（top_container_hide），仅在加载/缓冲时显示 */
    private var topContainerHide: View? = null

    // 加载与暂停浮层，通过 tag 标识
    private var loadingView: View? = null
    private var loadingViewHide: View? = null
    private var pauseContainer: View? = null
    private var pauseProgressText: TextView? = null
    private var videoPauseBar: ProgressBar? = null
    private var pauseIcon: ImageView? = null

    // 手势弹窗
    private var dialogVolume: View? = null
    private var dialogBrightness: View? = null
    private var volumeProgressbar: ProgressBar? = null
    private var brightnessProgressbar: ProgressBar? = null
    private var slideInfoText: TextView? = null
    private var progressContainer: View? = null
    private var progressText: TextView? = null
    private var videoProgressbar: ProgressBar? = null
    private var progressIcon: ImageView? = null

    // 底部控制栏
    private var currTime: TextView? = null
    private var totalTime: TextView? = null
    private var timeEnd: TextView? = null
    private var seekBar: SeekBar? = null
    private var playPauseView: View? = null
    private var playPauseImg: ImageView? = null
    private var playPrev: View? = null
    private var playNext: View? = null
    private var playSpeed: View? = null
    private var playSpeedTxt: TextView? = null
    private var playRetry: View? = null
    private var playScale: View? = null
    private var playScaleTxt: TextView? = null
    private var playPlayer: View? = null
    private var playPlayerTxt: TextView? = null
    private var playSubtitle: View? = null
    private var llDanmuSetting: View? = null
    private var playAudio: View? = null
    private var playVideo: View? = null
    private var playCast: View? = null
    private var screenDisplay: View? = null

    // 片头片尾跳过按钮
    private var playTimeStart: TextView? = null
    private var playTimeEnd: TextView? = null
    private var playTimeReset: TextView? = null
    private var playTimeStep: TextView? = null
    private var playTimeDivider: View? = null

    // 片头片尾步长（秒），点击循环 5→30，长按重置 5，持久化 PLAY_TIME_STEP
    private var skipStepSec = 5

    // 字幕视图
    var subtitleView: SimpleSubtitleView? = null
        private set

    // 解析栏
    private var parseRoot: View? = null

    /** 投屏接收模式标记：横屏恢复按钮时不覆盖 setCastMode 的隐藏 */
    private var isCastMode = false

    /** 音轨是否多于 1 条（控制音轨按钮显隐，竖屏/投屏时始终隐藏） */
    private var audioTrackVisible = false
    /** 视轨是否多于 1 条（控制视轨按钮显隐，竖屏/投屏时始终隐藏） */
    private var videoTrackVisible = false

    // 解析器列表数据与适配器
    private val parseItems = mutableListOf<ParseBean>()
    private var parseAdapter: ParseListAdapter? = null

    // 锁屏按钮
    private var lockView: View? = null
    private var isLocked = false

    // 时间刷新 Handler，收到消息刷新后继续调度下一次，实现每秒循环
    private val timeHandler: Handler = Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_REFRESH_TIME) {
            refreshSystemTime()
            timeHandler.sendEmptyMessageDelayed(MSG_REFRESH_TIME, TIME_REFRESH_INTERVAL)
            true
        } else {
            false
        }
    }

    // 控制栏显隐 Handler，消息排队避免连续操作时动画互相打断
    private val controlHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_SHOW_MENU -> showTopBottom(true)
            MSG_HIDE_MENU -> showTopBottom(false)
        }
        false
    }

    /** 控制栏自动隐藏任务 */
    private val hideBottomRunnable = Runnable { hideBottom() }

    /** 倍速长按进入快进标记，松手时消费点击事件避免弹出菜单 */
    private var speedLongPressActive = false

    /** 返回键唤出控制栏时的键来源标记：UP 键唤出后聚焦片头按钮，DOWN 键唤出后聚焦暂停按钮 */
    private var isKeyUp = false

    /** 当前视频是否为竖屏画面（宽<高），决定横竖屏按钮显隐与上下滑动换集 */
    private var isPortraitVideo = false
    /** 竖屏换集累计滑动距离（像素） */
    private var episodeSlideDistance = 0f
    /** 本次滑动是否已换集，避免阈值内重复触发 */
    private var episodeChanged = false

    /** 横屏手势是否有效（播放态、非锁屏、非屏幕边沿按下） */
    private var gestureActive = false
    /** 本次触摸判定的手势模式（[GESTURE_NONE]/[GESTURE_SEEK]/[GESTURE_BRIGHTNESS]/[GESTURE_VOLUME]） */
    private var gestureMode = GESTURE_NONE
    /** 手势按下位置（判定左右区域与位移基准） */
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    /** 按下时的音量基准：按基准+总位移连续计算，避免增量取整导致的档位跳动掉帧 */
    private var gestureVolumeBase = 0
    /** 按下时的亮度基准 */
    private var gestureBrightnessBase = 0.5f
    /** 手势期间最近一次计算的音量档位：ACTION_UP 时补提交系统音量 */
    private var lastVolumeTarget = -1
    /** 最近一次提交系统音量的时间戳（毫秒），用于限频避免 IPC 掉帧 */
    private var lastVolumeCommitTime = 0L
    /** 水平滑动 seek 的目标位置（毫秒），-1 表示未在滑动，松手 ACTION_UP 时提交 */
    private var seekPosition = -1L

    // 遥控方向键长按快进/快退状态：按下即按"按住时长"推进跳转偏移（随时间增长且轻微加速），
    // 松手 ACTION_UP 才执行最终跳转。不依赖系统 key repeat 事件（部分遥控/模拟器 repeat
    // 不派发到控制层，按事件叠加会导致长按只有首按 10 秒），定时器保证长按必然叠加
    /** 本次方向键快进是否已开始 */
    private var simSlideStart = false
    /** 松手时最终跳转位置（毫秒） */
    private var simSeekPosition = 0L
    /** 相对按下基准位置的累积偏移（毫秒） */
    private var simSlideOffset = 0L
    /** 本次按下起始时间戳（毫秒），按住时长推进的基准 */
    private var simSlideStartTime = 0L
    /** 本次方向键快进方向（1 快进 / -1 快退），定时推进任务使用 */
    private var slideDir = 1

    /**
     * 方向键长按推进定时任务：按住期间每 [SLIDE_TICK_INTERVAL] 按"按住时长"推进跳转偏移，
     * 速度随按住时间轻微加速（按住越久推进越快），与系统 key repeat 事件完全解耦。
     */
    private val slideTickRunnable = object : Runnable {
        override fun run() {
            if (!simSlideStart) return
            val holdMs = System.currentTimeMillis() - simSlideStartTime
            // 推进速度 = 起始速度 + 按住时长 × 加速率（按的越久越快）
            val speed = SLIDE_SPEED_PER_SEC + holdMs * SLIDE_SPEED_ACCEL_PER_SEC_SQ / 1000L
            simSlideOffset = SLIDE_BASE_STEP * slideDir + holdMs * speed * slideDir / 1000L
            updateSlideUi()
            controlHandler.postDelayed(this, SLIDE_TICK_INTERVAL)
        }
    }

    /** 刷新方向键快进进度浮层（tvSlideStart / 定时任务共用） */
    private fun updateSlideUi() {
        val position = (currentPosition + simSlideOffset).coerceIn(0L, duration)
        simSeekPosition = position
        showSeekProgress(position >= currentPosition, position, duration)
        controlHandler.removeCallbacks(hideSeekRunnable)
        controlHandler.postDelayed(hideSeekRunnable, 1000)
    }

    /** 竖屏换集滑动阈值：屏幕高度的 1/4 */
    private fun getEpisodeSlideThreshold(): Int = resources.displayMetrics.heightPixels / 4

    /** 倍速长按判定任务：触发时通知宿主进入临时快进 */
    private val speedLongPressRunnable = Runnable {
        speedLongPressActive = listener?.onSpeedLongPress() == true
    }

    /** 锁屏按钮自动隐藏任务 */
    private val lockRunnable = Runnable {
        lockView?.isFocusable = false
        lockView?.visibility = View.GONE
    }

    /** 手势检测：单击切换控制栏、双击三区快进快退/播放暂停、竖屏上下滑动换集、
     *  横屏左右滑动快进快退、横屏左半屏亮度/右半屏音量 */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // 重置竖屏换集滑动状态
            episodeSlideDistance = 0f
            episodeChanged = false
            // 重置横屏手势：播放态、非锁屏且未在屏幕边沿（顶部下拉通知栏等）按下才启用
            gestureMode = GESTURE_NONE
            seekPosition = -1L
            gestureActive = !isLocked && isInPlaybackState() && !isTouchEdge(e)
            if (gestureActive) {
                gestureStartX = e.x
                gestureStartY = e.y
                gestureVolumeBase = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                    ?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                gestureBrightnessBase = findActivity(context)?.window?.attributes?.screenBrightness ?: 0.5f
                if (gestureBrightnessBase < 0f) gestureBrightnessBase = 0.5f
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleControlBar()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // 竖屏画面上下滑动换集：上滑切下一集、下滑切上一集，
            // 累计滑动超过屏幕高度 1/4 触发一次；横屏画面不响应
            if (isPortraitVideo && !isLocked) {
                episodeSlideDistance += distanceY
                if (kotlin.math.abs(episodeSlideDistance) >= getEpisodeSlideThreshold() && !episodeChanged) {
                    if (episodeSlideDistance > 0) {
                        listener?.onPlayNext()
                    } else {
                        listener?.onPlayPrevious()
                    }
                    episodeChanged = true
                    hideBottom()
                }
                return true
            }
            // 横屏手势：首次滑动按位移方向判定模式（水平=快进快退，垂直=左/右侧小范围亮度/音量），
            // 判定后锁定模式直至松手，避免横向拖动误触发亮度音量、纵向拖动误触发 seek；
            // 垂直滑动仅两侧各 1/4 小范围响应，屏幕中间 1/2 不触发亮度/音量
            if (!gestureActive) return false
            if (gestureMode == GESTURE_NONE) {
                gestureMode = if (kotlin.math.abs(distanceX) >= kotlin.math.abs(distanceY)) {
                    GESTURE_SEEK
                } else {
                    when {
                        gestureStartX < width * GESTURE_SIDE_RATIO -> GESTURE_BRIGHTNESS
                        gestureStartX > width * (1f - GESTURE_SIDE_RATIO) -> GESTURE_VOLUME
                        else -> GESTURE_IGNORE
                    }
                }
            }
            // 位移基准从按下点计算：右滑 deltaX 为正（快进）、上滑 deltaY 为正（增大）
            val deltaX = e2.x - gestureStartX
            val deltaY = gestureStartY - e2.y
            when (gestureMode) {
                GESTURE_SEEK -> slideToChangePosition(deltaX)
                GESTURE_BRIGHTNESS -> adjustBrightness(deltaY)
                GESTURE_VOLUME -> adjustVolume(deltaY)
                else -> Unit // GESTURE_IGNORE：中间区域垂直滑动不响应
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isLocked) return true
            // 双击三区：左区快退 10s、右区快进 10s、中间播放/暂停
            val third = width / 3
            when {
                e.x < third -> if (isInPlaybackState()) seekBy(-SEEK_STEP)
                e.x > third * 2 -> if (isInPlaybackState()) seekBy(SEEK_STEP)
                else -> listener?.onPlayPause()
            }
            return true
        }
    })

    // 电量广播接收
    private var batteryReceiver: BroadcastReceiver? = null

    // SeekBar 用户拖拽标记，避免播放进度回调覆盖用户操作
    private var isUserSeeking = false

    // 片头片尾跳过配置（秒），点击片头/片尾按钮设置，长按清零
    private var skipStartSec = 0
    private var skipEndSec = 0
    private var skipStartApplied = false
    private var skipEndApplied = false
    /** 本次装配恢复的起始进度（毫秒），恢复位置已越过片头点时不再自动跳片头，避免退出重进后进度被拉回片头 */
    private var restoredStartPosition = 0L
    /** 片头片尾记忆键（源key|影片id），非空时跳过时间随影片持久化，退出后重新进入可恢复 */
    private var skipTimeKey = ""

    // 是否有弹幕数据，决定弹幕设置按钮显隐
    private var hasDanmu = false

    // 当前播放状态，用于遥控按键与手势的播放态判断
    private var curPlayState = PlayerConstants.STATE_IDLE

    // 当前播放位置与总时长（毫秒），由宿主通过 setProgress 更新
    private var currentPosition = 0L
    private var duration = 0L

    /** 卡顿缓冲检测：播放中进度停滞且持续有下载速率时置位，驱动迷你顶栏速率显示 */
    private var stallPos = -1L
    private var stallTime = 0L
    private var stalling = false
    /** 最近一次缓冲进度百分比（-1 表示未知），缓冲检测据此区分真实缓冲与渲染卡顿 */
    private var lastBufferedPercent = -1

    /** 预览模式（详情页小窗）：隐藏迷你顶栏，避免片名/网速常驻画面 */
    private var previewMode = false

    /** 设置预览模式，隐藏迷你顶栏（详情页小窗预览播放器使用，保持画面干净）。
     *  暂停条不受 previewMode 影响，预览窗暂停时同样显示右上角暂停条。 */
    fun setPreviewMode(enabled: Boolean) {
        previewMode = enabled
        refreshTopRightVisibility()
    }

    /** 设置回调监听 */
    fun setListener(l: VodControlListener) {
        listener = l
    }

    /** 清空回调监听，宿主销毁时调用 */
    fun clearListener() {
        listener = null
    }

    init {
        // 加载控制布局并完成视图绑定
        LayoutInflater.from(context).inflate(R.layout.player_vod_control_view, this, true)
        initView()
    }

    /** 初始化子视图绑定与事件，构造时调用 */
    private fun initView() {
        // 顶部信息栏
        tvTitleTop = findViewById(R.id.tv_title_top)
        tvPlaySpeedTopHide = findViewById(R.id.tv_play_speed_top_hide)
        tvMpbsTopHide = findViewById(R.id.tv_MPBS_top_hide)
        tvSpeedTopHide = findViewById(R.id.tv_speed_top_hide)
        tvResolution = findViewById(R.id.tv_resolution)
        tvTopRContainer = findViewById(R.id.tv_top_r_container)
        tvSeekTime = findViewById(R.id.tv_seek_time)
        tvSystemTime = findViewById(R.id.tv_system_time)
        tvSysTime = findViewById(R.id.tv_sys_time)
        batteryView = findViewById(R.id.battery_view)
        netSpeedTopR = findViewById(R.id.tv_play_load_net_speed_right_top)
        mpbsTopR = findViewById(R.id.tv_MPBS_top_r)

        // 加载与暂停浮层，通过 tag 标识
        loadingView = findViewWithTag("vod_control_loading")
        loadingViewHide = findViewWithTag("vod_control_loading_hide")
        pauseContainer = findViewWithTag("vod_control_pause")
        pauseProgressText = findViewWithTag("vod_control_pause_t")
        videoPauseBar = findViewById(R.id.video_pausebar)
        pauseIcon = findViewById(R.id.tv_pause_icon)

        // 手势弹窗
        dialogVolume = findViewById(R.id.dialog_volume)
        dialogBrightness = findViewById(R.id.dialog_bright)
        volumeProgressbar = findViewWithTag("progressbar_volume")
        brightnessProgressbar = findViewWithTag("progressbar_brightness")
        slideInfoText = findViewWithTag("vod_control_slide_info")
        progressContainer = findViewById(R.id.tv_progress_container)
        progressText = findViewById(R.id.tv_progress_text)
        videoProgressbar = findViewById(R.id.video_progressbar)
        progressIcon = findViewById(R.id.tv_progress_icon)

        // 控制栏容器
        topContainer = findViewById(R.id.top_container)
        bottomContainer = findViewById(R.id.bottom_container)
        topContainerHide = findViewById(R.id.top_container_hide)

        // 底部控制栏
        currTime = findViewById(R.id.curr_time)
        totalTime = findViewById(R.id.total_time)
        timeEnd = findViewById(R.id.tv_time_end)
        seekBar = findViewById(R.id.seekBar)
        playPauseView = findViewById(R.id.play_pause)
        playPauseImg = findViewById(R.id.play_pauseImg)
        playPrev = findViewById(R.id.play_prev)
        playNext = findViewById(R.id.play_next)
        playSpeed = findViewById(R.id.play_speed)
        playSpeedTxt = findViewById(R.id.play_speed_txt)
        playRetry = findViewById(R.id.play_retry)
        playScale = findViewById(R.id.play_scale)
        playScaleTxt = findViewById(R.id.play_scale_txt)
        playPlayer = findViewById(R.id.play_player)
        playPlayerTxt = findViewById(R.id.play_player_txt)
        playSubtitle = findViewById(R.id.play_subtitle)
        llDanmuSetting = findViewById(R.id.ll_danmu_setting)
        playAudio = findViewById(R.id.play_audio)
        playVideo = findViewById(R.id.play_video)
        playCast = findViewById(R.id.play_cast)
        screenDisplay = findViewById(R.id.screen_display)

        // 片头片尾按钮
        playTimeStart = findViewById(R.id.play_time_start)
        playTimeEnd = findViewById(R.id.play_time_end)
        playTimeReset = findViewById(R.id.play_time_reset)
        playTimeStep = findViewById(R.id.play_time_step)
        playTimeDivider = findViewById(R.id.play_time_divider)

        // 横竖屏切换按钮
        landscapePortraitBtn = findViewById(R.id.landscape_portrait)
        lvPortraitImg = findViewById(R.id.lv_portrait)

        // 字幕视图，并立即应用用户保存的字幕样式（字号/颜色/描边/粗细），
        // 保证预览与播放一致，无需重新打开字幕设置弹窗才生效
        subtitleView = findViewById(R.id.subtitle_view)
        subtitleView?.let { view ->
            val hostActivity = context as? AppCompatActivity ?: context as? android.app.Activity
            if (hostActivity != null) {
                view.setTextSize(SubtitleHelper.getTextSize(hostActivity).toFloat())
                view.setTextColor(SubtitleHelper.getTextColor())
                view.setBackGroundTextColor(SubtitleHelper.getStrokeColor())
                view.setShadowLayer(0f, 0f, 0f, 0)
                view.setTypeface(
                    if (SubtitleHelper.getTextBold() == android.graphics.Typeface.BOLD) {
                        android.graphics.Typeface.DEFAULT_BOLD
                    } else {
                        android.graphics.Typeface.DEFAULT
                    }
                )
            }
        }

        // 锁屏
        lockView = findViewById(R.id.tv_lock)
        // 初始不可聚焦：回前台时系统可能把焦点给不可见的锁屏按钮，显示白色焦点框；
        // 显示锁屏按钮时才恢复可聚焦
        lockView?.isFocusable = false

        // 解析栏：横向列表展示解析器，点击切换并刷新默认项
        parseRoot = findViewById(R.id.parse_root)
        val parseGrid = findViewById<com.owen.tvrecyclerview.widget.TvRecyclerView>(R.id.mGridView)
        parseGrid.layoutManager = V7LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        parseAdapter = ParseListAdapter(parseItems) { bean ->
            if (!isLocked) {
                parseItems.forEach { it.setDefault(it === bean) }
                parseAdapter?.notifyDataSetChanged()
                listener?.onParseChanged(bean)
                hideBottom()
            }
        }
        parseGrid.adapter = parseAdapter

        // 控制栏初始隐藏，避免预览播放器控制栏一直显示
        topContainer?.visibility = INVISIBLE
        bottomContainer?.visibility = INVISIBLE
        llDanmuSetting?.visibility = INVISIBLE
        // 迷你顶栏初始隐藏，加载/缓冲时由 onPlayStateChanged 显示
        topContainerHide?.visibility = GONE

        // 初始化片头片尾步长（默认 5s，范围 5~30）
        skipStepSec = AppPreferences.getShared(context).getInt(AppConstants.PLAY_TIME_STEP, 5).coerceIn(5, 30)
        playTimeStep?.text = "${skipStepSec}S"

        updateSkipViews()
        setupListeners()
        setupRootTouch()
    }

    /** 根视图触摸：锁屏态消费全部触摸并切换锁屏按钮显隐；非锁屏态交给手势检测。
     *  锁定按钮显示期间仅拦截按钮自身的点击，点击其余画面仍响应手势（唤出/隐藏控制栏），
     *  避免控制栏显示后 3 秒内触摸唤出失效 */
    private fun setupRootTouch() {
        val rootView = findViewById<View>(R.id.rootView)
        rootView.setOnTouchListener { _, event ->
            if (isLocked) {
                if (event.action == MotionEvent.ACTION_UP) {
                    if (lockView?.visibility == View.VISIBLE) {
                        lockView?.visibility = View.GONE
                    } else {
                        showLockView()
                    }
                }
                true
            } else {
                val handled = dispatchGestureEvent(event)
                // 松手时提交水平滑动 seek（滑动过程中只预览进度，松手才跳转）
                if (event.action == MotionEvent.ACTION_UP) finishGesture()
                handled
            }
        }
    }

    /** 非锁屏态触摸分发：锁屏按钮点击区域不拦截（交给按钮自身），其余交给手势检测 */
    private fun dispatchGestureEvent(event: MotionEvent): Boolean {
        val lock = lockView
        if (lock?.visibility == View.VISIBLE) {
            val rect = android.graphics.Rect()
            lock.getHitRect(rect)
            if (rect.contains(event.x.toInt(), event.y.toInt())) {
                // 点击锁屏按钮自身：交给按钮处理，不拦截
                return false
            }
        }
        return gestureDetector.onTouchEvent(event)
    }

    /** 触摸分发兜底：子视图未消费的触摸交给手势检测，保证点击画面始终能唤出/隐藏控制栏 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(event)
        if (!handled && !isLocked) {
            val result = gestureDetector.onTouchEvent(event)
            // 子视图未消费的触摸松手时同样提交滑动 seek
            if (event.action == MotionEvent.ACTION_UP) finishGesture()
            return result
        }
        return handled
    }

    /** 绑定按钮点击事件 */
    private fun setupListeners() {
        playPauseView?.setOnClickListener {
            if (!isLocked) {
                listener?.onPlayPause()
                hideBottom()
            }
        }
        // 右上角暂停条上的播放按钮：暂停态点击恢复播放
        pauseIcon?.setOnClickListener {
            if (!isLocked) {
                listener?.onPlayPause()
            }
        }
        playPrev?.setOnClickListener {
            if (!isLocked) {
                listener?.onPlayPrevious()
                hideBottom()
            }
        }
        playNext?.setOnClickListener {
            if (!isLocked) {
                listener?.onPlayNext()
                hideBottom()
            }
        }
        // 倍速锚点菜单：菜单弹出时暂停自动隐藏、关闭时恢复，由菜单生命周期管理，此处不重启倒计时
        playSpeed?.setOnClickListener {
            if (!isLocked) {
                listener?.onSpeedClick()
            }
        }
        // 倍速长按：按住期间临时最大档位快进，松手恢复原速
        playSpeed?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    controlHandler.postDelayed(speedLongPressRunnable, SPEED_LONG_PRESS_DELAY)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    controlHandler.removeCallbacks(speedLongPressRunnable)
                    if (speedLongPressActive) {
                        speedLongPressActive = false
                        listener?.onSpeedLongPressRelease()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        playScale?.setOnClickListener {
            if (!isLocked) {
                listener?.onScaleClick()
                restartAutoHide()
            }
        }
        playPlayer?.setOnClickListener {
            if (!isLocked) {
                listener?.onPlayerClick()
                restartAutoHide()
            }
        }
        playSubtitle?.setOnClickListener {
            if (!isLocked) {
                listener?.onSubtitleClick()
                restartAutoHide()
            }
        }
        llDanmuSetting?.setOnClickListener {
            if (!isLocked) {
                listener?.onDanmuClick()
                restartAutoHide()
            }
        }
        playAudio?.setOnClickListener {
            if (!isLocked) {
                listener?.onAudioClick()
                restartAutoHide()
            }
        }
        playVideo?.setOnClickListener {
            if (!isLocked) {
                listener?.onVideoClick()
                restartAutoHide()
            }
        }
        playCast?.setOnClickListener {
            if (!isLocked) {
                listener?.onCastClick()
                restartAutoHide()
            }
        }
        // 屏显锚点菜单：菜单弹出时暂停自动隐藏、关闭时恢复，由菜单生命周期管理，此处不重启倒计时
        screenDisplay?.setOnClickListener {
            if (!isLocked) {
                listener?.onScreenDisplayClick()
            }
        }
        playRetry?.setOnClickListener {
            listener?.onRetryClick()
            hideBottom()
        }
        lockView?.setOnClickListener {
            toggleLock()
        }

        // 片头片尾：点击记录当前点，长按清零
        playTimeStart?.setOnClickListener {
            if (!isLocked) {
                val current = currentPosition
                val d = duration
                if (d > 0 && current <= d / 2) {
                    skipStartSec = (current / 1000).toInt()
                    saveSkipTimes()
                    updateSkipViews()
                    restartAutoHide()
                }
            }
        }
        playTimeStart?.setOnLongClickListener {
            skipStartSec = 0
            saveSkipTimes()
            updateSkipViews()
            restartAutoHide()
            true
        }
        playTimeEnd?.setOnClickListener {
            if (!isLocked) {
                val current = currentPosition
                val d = duration
                if (d > 0 && current >= d / 2) {
                    skipEndSec = ((d - current) / 1000).toInt()
                    saveSkipTimes()
                    updateSkipViews()
                    restartAutoHide()
                }
            }
        }
        playTimeEnd?.setOnLongClickListener {
            skipEndSec = 0
            saveSkipTimes()
            updateSkipViews()
            restartAutoHide()
            true
        }
        playTimeReset?.setOnClickListener {
            if (!isLocked) {
                skipStartSec = 0
                skipEndSec = 0
                saveSkipTimes()
                updateSkipViews()
                restartAutoHide()
            }
        }

        // 片头片尾步长：点击循环 5→30，长按重置 5
        playTimeStep?.setOnClickListener {
            if (!isLocked) {
                skipStepSec += 5
                if (skipStepSec > 30) skipStepSec = 5
                AppPreferences.getShared(context).putSync(AppConstants.PLAY_TIME_STEP, skipStepSec)
                playTimeStep?.text = "${skipStepSec}S"
                restartAutoHide()
            }
        }
        playTimeStep?.setOnLongClickListener {
            skipStepSec = 5
            AppPreferences.getShared(context).putSync(AppConstants.PLAY_TIME_STEP, skipStepSec)
            playTimeStep?.text = "${skipStepSec}S"
            restartAutoHide()
            true
        }

        // 横竖屏切换：点击回调宿主切换 Activity 方向（仅竖屏画面视频时显示）
        landscapePortraitBtn?.setOnClickListener {
            if (!isLocked) {
                listener?.onOrientationClick()
                hideBottom()
            }
        }

        // 进度条拖动
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    val target = duration * progress / SEEK_BAR_MAX
                    currTime?.text = stringForTime(target)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                // 拖拽期间暂停控制栏自动隐藏
                controlHandler.removeCallbacks(hideBottomRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val progress = seekBar?.progress ?: 0
                if (duration > 0) {
                    val target = duration * progress / SEEK_BAR_MAX
                    listener?.onSeekTo(target)
                }
                restartAutoHide()
            }
        })

        // 根据当前横竖屏方向初始化底部按钮显隐（init 时调用）
        updateBottomButtonsForOrientation()
    }

    // ------------------------------------------------------------------
    // 控制栏显隐
    // ------------------------------------------------------------------

    /** 控制栏当前是否可见 */
    fun isBottomBarVisible(): Boolean = bottomContainer?.visibility == View.VISIBLE

    /** 显示控制栏并启动 8 秒自动隐藏 */
    fun showBottom() {
        if (isLocked) return
        controlHandler.removeMessages(MSG_HIDE_MENU)
        controlHandler.sendEmptyMessage(MSG_SHOW_MENU)
        restartAutoHide()
        showLockView()
    }

    /** 隐藏控制栏并取消自动隐藏计时 */
    fun hideBottom() {
        controlHandler.removeMessages(MSG_SHOW_MENU)
        controlHandler.sendEmptyMessage(MSG_HIDE_MENU)
        controlHandler.removeCallbacks(hideBottomRunnable)
        controlHandler.removeCallbacks(lockRunnable)
        lockView?.isFocusable = false
        lockView?.visibility = View.GONE
    }

    /** 控制栏显隐动画：顶部下滑、底部上滑 + 淡入淡出 */
    private fun showTopBottom(show: Boolean) {
        val top = topContainer ?: return
        val bottom = bottomContainer ?: return
        if (show) {
            // 控制栏显示时隐藏迷你顶栏，避免与 top_container 重叠
            topContainerHide?.visibility = View.GONE
            // 取消进行中的隐藏动画，避免动画结束回调把容器置为 GONE
            top.animate().cancel()
            bottom.animate().cancel()
            // 清除隐藏动画残留的 onAnimationEnd listener（cancel 不会移除 listener，
            // 若不清理，新显示动画结束后旧 listener 会把容器置为 GONE，导致控制栏闪现后消失）
            top.animate().setListener(null)
            bottom.animate().setListener(null)
            top.visibility = View.VISIBLE
            top.alpha = 0f
            top.translationY = -top.height / 2f
            top.animate().translationY(0f).alpha(1f)
                .setDuration(ANIM_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
            bottom.visibility = View.VISIBLE
            bottom.alpha = 0f
            bottom.translationY = bottom.height / 2f
            bottom.animate().translationY(0f).alpha(1f)
                .setDuration(ANIM_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
            updateDanmuBtn()
            // 控制栏显示时按当前方向刷新底部按钮显隐（case 1002 中的调用）
            updateBottomButtonsForOrientation()
            // Workaround Fix : SurfaceView 布局刷新，避免部分设备画面闪烁
            bottom.post { bottom.requestLayout() }
            // 控制栏显示后初始化焦点（无条件聚焦）：
            // UP 键唤出时聚焦片头跳过按钮，其余（DOWN/单击）聚焦暂停按钮；
            // 先让底部容器获得焦点兜底，竖屏模式下这些按钮可能被隐藏（GONE），
            // 直接 requestFocus 失败会导致焦点丢失、遥控器按键全部无响应
            bottom.requestFocus()
            bottom.post {
                if (isKeyUp) {
                    isKeyUp = false
                    playTimeStart?.requestFocus()
                } else {
                    playPauseView?.requestFocus()
                }
            }
        } else {
            // 隐藏后恢复迷你顶栏（仅加载/缓冲时显示，由 refreshTopRightVisibility 维护）
            refreshTopRightVisibility()
            top.animate().cancel()
            bottom.animate().cancel()
            top.animate().translationY(-top.height / 2f).alpha(0f)
                .setDuration(ANIM_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 竖屏模式下用 INVISIBLE 保持跑马灯 TextView 挂载，避免重新显示时滚动从头开始
                        top.visibility = if (isPortraitMode()) View.INVISIBLE else View.GONE
                        top.clearAnimation()
                    }
                })
                .start()
            bottom.animate().translationY(bottom.height / 2f).alpha(0f)
                .setDuration(ANIM_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottom.visibility = View.GONE
                        bottom.clearAnimation()
                    }
                })
                .start()
        }
    }

    /** 迷你顶栏（top_container_hide）显隐：仅加载/缓冲时显示（速率+加载圈），播放中隐藏；
     *  暂停态与投屏接收模式（isCastMode）恒隐藏；控制栏显示时由 showTopBottom 临时隐藏。
     *  卡顿检测命中（stalling）时显示，覆盖部分内核缓冲不回调 BUFFERING 的场景。 */
    private fun updateMiniTopBar() {
        val hide = topContainerHide ?: return
        hide.visibility = if (curPlayState == PlayerConstants.STATE_PAUSED || isCastMode) {
            View.GONE
        } else if (stalling || curPlayState == PlayerConstants.STATE_BUFFERING ||
            curPlayState == PlayerConstants.STATE_PREPARING
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** 迷你顶栏与右上角屏显容器互斥显隐：迷你顶栏显示时隐藏屏显容器，避免速率重叠。
     *  加载/缓冲（含卡顿兜底 stalling）时同步显示转圈，保证缓冲卡顿也有反馈 */
    private fun refreshTopRightVisibility() {
        updateMiniTopBar()
        val miniVisible = topContainerHide?.visibility == View.VISIBLE
        tvTopRContainer?.visibility = if (miniVisible) GONE else if (screenDisplayAnyOn) VISIBLE else GONE
        val showSpin = stalling || curPlayState == PlayerConstants.STATE_BUFFERING ||
            curPlayState == PlayerConstants.STATE_PREPARING
        loadingView?.visibility = if (showSpin) VISIBLE else GONE
        loadingViewHide?.visibility = if (showSpin) VISIBLE else GONE
    }

    /** 单击切换控制栏显隐 */
    private fun toggleControlBar() {
        if (isBottomBarVisible()) hideBottom() else showBottom()
    }

    /** 显示锁屏按钮，3 秒后自动隐藏 */
    private fun showLockView() {
        lockView?.isFocusable = true
        lockView?.visibility = View.VISIBLE
        controlHandler.removeCallbacks(lockRunnable)
        controlHandler.postDelayed(lockRunnable, LOCK_BTN_SHOW_DURATION)
    }

    /** 重启控制栏自动隐藏计时（用户操作时调用） */
    private fun restartAutoHide() {
        controlHandler.removeCallbacks(hideBottomRunnable)
        controlHandler.postDelayed(hideBottomRunnable, CONTROL_BAR_SHOW_DURATION)
    }

    /** 暂停控制栏自动隐藏（弹出 PopupWindow 菜单期间调用，避免菜单操作时控制栏消失） */
    fun pauseAutoHide() {
        controlHandler.removeCallbacks(hideBottomRunnable)
    }

    /** 恢复控制栏自动隐藏（菜单关闭时调用，重启 8 秒倒计时） */
    fun resumeAutoHide() {
        controlHandler.postDelayed(hideBottomRunnable, CONTROL_BAR_SHOW_DURATION)
    }

    /** 屏显按钮，作为屏显菜单的锚点 */
    fun getScreenDisplayAnchor(): View? = screenDisplay

    /** 倍速按钮，作为倍速菜单的锚点 */
    fun getSpeedAnchor(): View? = playSpeed

    /** 切换锁屏状态，并切换锁定/解锁图标 */
    private fun toggleLock() {
        isLocked = !isLocked
        lockView?.isActivated = isLocked
        (lockView as? ImageView)?.setImageResource(
            if (isLocked) CoreUiR.drawable.icon_lock else CoreUiR.drawable.icon_unlock
        )
        if (isLocked) {
            hideBottom()
        } else {
            showBottom()
        }
    }

    // ------------------------------------------------------------------
    // 遥控按键
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLocked) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        val action = event.action
        // 遥控方向键快进/快退松手：结束累积快进，执行最终跳转。
        // 长按期间系统会重复派发 ACTION_DOWN（repeat 事件），仅松手 ACTION_UP 时提交跳转
        if (action == KeyEvent.ACTION_UP &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (isInPlaybackState()) {
                tvSlideStop()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        // 媒体键始终响应，与控制栏显隐无关
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT -> listener?.onPlayNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> listener?.onPlayPrevious()
                    else -> if (isInPlaybackState()) listener?.onPlayPause()
                }
                return true
            }
            // 菜单键显隐控制栏：呼出时复位方向标记，确保焦点落到暂停按钮
            KeyEvent.KEYCODE_MENU -> {
                if (isBottomBarVisible()) {
                    hideBottom()
                } else {
                    isKeyUp = false
                    showBottom()
                }
                return true
            }
        }
        if (isBottomBarVisible()) {
            // 控制栏可见时重置自动隐藏计时
            restartAutoHide()
            // 片头/片尾跳过按钮聚焦时 UP/DOWN 调节跳过时间（dispatchKeyEvent）
            val focused = findFocus()
            if ((focused === playTimeStart || focused === playTimeEnd) &&
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            ) {
                adjustSkipTime(focused === playTimeStart, keyCode == KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // UP 键唤出控制栏后聚焦片头跳过按钮，DOWN 聚焦暂停按钮
                isKeyUp = keyCode == KeyEvent.KEYCODE_DPAD_UP
                showBottom()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (isInPlaybackState()) listener?.onPlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isInPlaybackState()) {
                    // 长按动态快进/快退：按下累积计算偏移并显示进度浮层，松手才跳转
                    tvSlideStart(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 片头/片尾跳过按钮聚焦时按 UP/DOWN 调节跳过时间（increaseTime/decreaseTime）。
     * 步长取当前片头片尾步长配置（默认 5 秒），超过 600 秒（10 分钟）回绕为 0。
     */
    private fun adjustSkipTime(isStart: Boolean, increase: Boolean) {
        val step = skipStepSec
        if (isStart) {
            skipStartSec += if (increase) step else -step
            if (skipStartSec > MAX_SKIP_SECONDS) skipStartSec = 0
            if (skipStartSec < 0) skipStartSec = MAX_SKIP_SECONDS
            // 重新应用跳转起点（重新 seek），终点仅更新文本即可
            skipStartApplied = false
        } else {
            skipEndSec += if (increase) step else -step
            if (skipEndSec > MAX_SKIP_SECONDS) skipEndSec = 0
            if (skipEndSec < 0) skipEndSec = MAX_SKIP_SECONDS
            skipEndApplied = false
        }
        saveSkipTimes()
        updateSkipViews()
        restartAutoHide()
    }

    /**
     * 遥控方向键快进/快退按下（含长按 repeat）：
     * 首按基础跳 [SLIDE_BASE_STEP]（10 秒）并启动按住时长推进定时器，
     * 按住期间按"按住时长"推进且速度轻微加速（按的越久越快），
     * 松手时由 [tvSlideStop] 执行最终跳转。
     */
    private fun tvSlideStart(dir: Int) {
        if (duration <= 0) return
        if (!simSlideStart) {
            simSlideStart = true
            simSlideStartTime = System.currentTimeMillis()
            slideDir = dir
            simSlideOffset = SLIDE_BASE_STEP * dir
            updateSlideUi()
            controlHandler.removeCallbacks(slideTickRunnable)
            controlHandler.postDelayed(slideTickRunnable, SLIDE_TICK_INTERVAL)
        } else {
            // 长按中方向切换（罕见）：后续推进按新方向计算
            slideDir = dir
        }
    }

    /**
     * 遥控方向键快进/快退松手：停止推进并执行累积的最终跳转。
     * 暂停状态下松手恢复播放。
     */
    private fun tvSlideStop() {
        if (!simSlideStart) return
        controlHandler.removeCallbacks(slideTickRunnable)
        listener?.onSeekTo(simSeekPosition)
        if (curPlayState == PlayerConstants.STATE_PAUSED) {
            listener?.onPlayPause()
        }
        simSlideStart = false
        simSeekPosition = 0L
        simSlideOffset = 0L
        simSlideStartTime = 0L
    }

    /** 相对当前位置快进/快退，并显示进度浮层 */
    private fun seekBy(delta: Long) {
        if (duration <= 0) return
        var target = currentPosition + delta
        if (target < 0) target = 0
        if (target > duration) target = duration
        listener?.onSeekTo(target)
        showSeekProgress(delta > 0, target, duration)
        controlHandler.removeCallbacks(hideSeekRunnable)
        controlHandler.postDelayed(hideSeekRunnable, 1000)
    }

    /** 进度浮层自动隐藏任务 */
    private val hideSeekRunnable = Runnable { hideSeekProgress() }

    /** 音量/亮度滑动浮层自动隐藏任务 */
    private val hideSlideRunnable = Runnable { hideSlideInfo() }

    /** 屏幕边沿判断：上下左右 [GESTURE_EDGE_DP]dp 内按下不响应横屏手势，
     *  避免顶部下拉通知栏、底部导航手势区触发亮度/音量/快进快退 */
    private fun isTouchEdge(e: MotionEvent): Boolean {
        val dm = resources.displayMetrics
        val edge = (GESTURE_EDGE_DP * dm.density).toInt()
        return e.rawX < edge || e.rawX > dm.widthPixels - edge ||
            e.rawY < edge || e.rawY > dm.heightPixels - edge
    }

    /** 水平滑动调节进度（横屏左右滑动）：右滑快进、左滑快退，实时显示进度浮层，
     *  松手 ACTION_UP 时由 [finishGesture] 提交跳转 */
    private fun slideToChangePosition(deltaX: Float) {
        if (width <= 0 || duration <= 0) return
        val current = currentPosition
        val target = (current + deltaX / width * SEEK_GESTURE_RANGE.toFloat())
            .toLong()
            .coerceIn(0L, duration)
        seekPosition = target
        showSeekProgress(target >= current, target, duration)
        controlHandler.removeCallbacks(hideSeekRunnable)
        controlHandler.postDelayed(hideSeekRunnable, 1000)
    }

    /** 手势结束（ACTION_UP）：提交水平滑动 seek 与最终音量，并复位手势模式 */
    private fun finishGesture() {
        if (seekPosition >= 0) {
            val target = seekPosition
            seekPosition = -1L
            if (duration > 0 && isInPlaybackState()) {
                listener?.onSeekTo(target)
            }
        }
        commitVolume()
        gestureMode = GESTURE_NONE
    }

    /** 调节亮度（横屏左侧小范围上下滑动），实时应用并显示浮层。
     *  手势方向：上滑增大、下滑减小；以按下时亮度为基准按总位移连续计算（float 全程参与），
     *  保证跟手顺滑；显示时隐藏音量条，避免两侧快速切换时状态条并存 */
    private fun adjustBrightness(deltaY: Float) {
        val activity = findActivity(context) ?: return
        if (height <= 0) return
        val window = activity.window
        val attrs = window.attributes
        val brightness = (gestureBrightnessBase + deltaY / height).coerceIn(0.01f, 1f)
        attrs.screenBrightness = brightness
        window.attributes = attrs
        brightnessProgressbar?.progress = (brightness * 100).toInt()
        slideInfoText?.text = "亮度 ${(brightness * 100).toInt()}"
        dialogBrightness?.visibility = VISIBLE
        dialogVolume?.visibility = GONE
        showSlideInfo()
    }

    /** 调节音量（横屏右侧小范围上下滑动），实时应用并显示浮层。
     *  手势方向：上滑增大、下滑减小；以按下时音量为基准按总位移 float 连续计算再取整，
     *  避免增量取整导致的档位跳动掉帧；显示时隐藏亮度条。
     *  setStreamVolume 是系统 IPC，限频提交避免频繁调用掉帧，松手 ACTION_UP 时补最终值 */
    private fun adjustVolume(deltaY: Float) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0 || height <= 0) return
        val target = (gestureVolumeBase + deltaY * 2 / height * max)
            .coerceIn(0f, max.toFloat())
            .toInt()
        lastVolumeTarget = target
        volumeProgressbar?.progress = target * 100 / max
        slideInfoText?.text = "音量 $target/$max"
        dialogVolume?.visibility = VISIBLE
        dialogBrightness?.visibility = GONE
        showSlideInfo()
        val now = SystemClock.uptimeMillis()
        if (now - lastVolumeCommitTime >= VOLUME_COMMIT_INTERVAL) {
            lastVolumeCommitTime = now
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    /** 手势结束补提交最终音量（限频期间 UI 已跟手，松手时确保系统音量一致） */
    private fun commitVolume() {
        if (lastVolumeTarget < 0) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVolumeTarget, 0)
        lastVolumeTarget = -1
    }

    /** 显示滑动浮层并启动自动隐藏 */
    private fun showSlideInfo() {
        controlHandler.removeCallbacks(hideSlideRunnable)
        controlHandler.postDelayed(hideSlideRunnable, 1000)
    }

    /** 隐藏音量/亮度滑动浮层 */
    private fun hideSlideInfo() {
        dialogVolume?.visibility = GONE
        dialogBrightness?.visibility = GONE
        slideInfoText?.visibility = GONE
    }

    /** 当前是否处于可播放状态（用于遥控按键与手势判断） */
    private fun isInPlaybackState(): Boolean =
        curPlayState != PlayerConstants.STATE_ERROR &&
            curPlayState != PlayerConstants.STATE_IDLE &&
            curPlayState != PlayerConstants.STATE_PREPARING &&
            curPlayState != PlayerConstants.STATE_PREPARED &&
            curPlayState != PlayerConstants.STATE_PLAYBACK_COMPLETED

    /** 设置播放状态，由宿主转发 MyVideoView 的播放状态 */
    fun setPlayState(playState: Int) {
        curPlayState = playState
        // 暂停/播放状态同步驱动暂停条显隐：宿主先调用本方法，不依赖 onPlayStateChanged 回调链
        when (playState) {
            PlayerConstants.STATE_PAUSED -> {
                resetStall()
                showPauseBarInternal()
            }
            PlayerConstants.STATE_PLAYING, PlayerConstants.STATE_PLAYBACK_COMPLETED -> {
                resetStall()
                pauseContainer?.visibility = GONE
                refreshTopRightVisibility()
            }
            else -> Unit
        }
    }

    /** 主动显示右上角暂停条（暂停后由宿主调用，绕过部分内核状态回调时序导致不显示的问题） */
    fun showPauseBar() {
        showPauseBarInternal()
    }

    /** 显示暂停条并置顶、临时隐藏迷你顶栏，避免右上角被遮挡无法感知暂停状态 */
    private fun showPauseBarInternal() {
        pauseContainer?.let {
            it.visibility = VISIBLE
            it.bringToFront()
        }
        topContainerHide?.visibility = GONE
    }

    // ------------------------------------------------------------------
    // 状态与进度
    // ------------------------------------------------------------------

    /** 播放状态变化回调，由宿主转发 MyVideoView 的播放状态 */
    fun onPlayStateChanged(playState: Int) {
        // 迷你顶栏仅加载/缓冲时显示；迷你顶栏显示时隐藏右上角屏显容器避免重叠，
        // 未显示时屏显容器保持显示，保证加载/缓冲时始终能看到速率
        refreshTopRightVisibility()
        when (playState) {
            PlayerConstants.STATE_IDLE -> {
                // 新一集开始时重置片头片尾跳过标记
                skipStartApplied = false
                skipEndApplied = false
            }
            PlayerConstants.STATE_PLAYING -> {
                pauseContainer?.visibility = GONE
                updatePauseIcon(true)
                // 片头自动跳过（每集仅首次进入播放状态时生效）
                if (skipStartSec > 0 && !skipStartApplied) {
                    skipStartApplied = true
                    // 恢复进度已越过片头点时不再跳片头：退出重进/断点续播若再次
                    // seek 到片头会把恢复的进度拉回片头位置，导致始终从片头开始
                    val skipStartMs = skipStartSec * 1000L
                    if (restoredStartPosition <= 0 || restoredStartPosition < skipStartMs) {
                        listener?.onSeekTo(skipStartMs)
                    }
                }
                // 本次装配的恢复位置仅生效一次，复位避免影响后续播放周期
                restoredStartPosition = 0
                listener?.onPlaying()
            }
            PlayerConstants.STATE_PAUSED -> {
                showPauseBarInternal()
                updatePauseIcon(false)
            }
            PlayerConstants.STATE_PREPARED -> {
                listener?.onPrepared()
            }
            PlayerConstants.STATE_BUFFERING -> Unit
            PlayerConstants.STATE_BUFFERED -> Unit
            PlayerConstants.STATE_PREPARING -> Unit
            PlayerConstants.STATE_ERROR -> {
                listener?.onError(context.getString(CoreUiR.string.play_err_video_error))
            }
            PlayerConstants.STATE_PLAYBACK_COMPLETED -> {
                pauseContainer?.visibility = GONE
                listener?.onCompletion()
            }
        }
    }

    /** 更新播放进度，由宿主转发 MyVideoView 的进度回调 */
    fun setProgress(duration: Int, position: Int) {
        this.duration = duration.toLong()
        this.currentPosition = position.toLong()
        detectStall(position)
        // 片尾自动跳过：接近片尾点时触发下一集（每集仅一次）
        if (skipEndSec > 0 && !skipEndApplied && duration > 0 && position > 0 &&
            position + skipEndSec * 1000 >= duration
        ) {
            skipEndApplied = true
            listener?.onPlayNext()
        }
        if (!isUserSeeking) {
            currTime?.text = stringForTime(position.toLong())
            totalTime?.text = stringForTime(duration.toLong())
            // 影片结束时间：播放中显示"结束于 HH:mm:ss"，暂停时附加剩余时长
            val remainingMs = (duration - position).toLong()
            val endTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + remainingMs))
            timeEnd?.text = if (curPlayState == PlayerConstants.STATE_PAUSED) {
                "剩余 ${stringForTime(remainingMs)} | 结束于 $endTime"
            } else {
                "结束于 $endTime"
            }
            if (duration > 0) {
                // Long 运算避免 Int 溢出： position * SEEK_BAR_MAX 溢出
                // 为负数，导致进度条指示器位置错误（长时间影片/大距离拖动后指示器跳回异常位置）
                seekBar?.progress = (position.toLong() * SEEK_BAR_MAX / duration).toInt()
            }
            pauseProgressText?.text = stringForTime(position.toLong()) + " / " + stringForTime(duration.toLong())
            // 右上角进度时间（屏显容器子项，是否显示由 applyScreenDisplay 控制）
            tvSeekTime?.text = stringForTime(position.toLong()) + " | " + stringForTime(duration.toLong())
            // 同步暂停浮层进度
            val percent = if (duration > 0) position * 100 / duration else 0
            videoPauseBar?.progress = percent
            videoProgressbar?.progress = percent
            // 兜底：暂停状态确保右上角暂停条显示（进度每秒刷新，覆盖状态回调时序不稳的内核）；
            // 快进/快退反馈浮层显示期间不恢复暂停条，避免两者重叠
            if (curPlayState == PlayerConstants.STATE_PAUSED &&
                progressContainer?.visibility != VISIBLE
            ) {
                showPauseBarInternal()
            }
        }
    }

    /** 更新缓冲进度（百分比 0-100），由宿主转发 MyVideoView 的缓冲回调；不可用时清零隐藏 */
    fun setBufferedProgress(percent: Int) {
        lastBufferedPercent = percent
        val seek = seekBar ?: return
        seek.secondaryProgress = if (percent in 1..100) {
            percent * SEEK_BAR_MAX / 100
        } else {
            0
        }
    }

    /**
     * 缓冲检测：播放中进度停滞超过阈值、存在下载流量且缓冲未满时判定真实缓冲。
     * 部分内核数据不足但未切入 BUFFERING 状态（缓冲百分比停滞不增长），仅依赖 onInfo 会漏显；
     * 缓冲已满（数据充足）时的画面停滞属渲染瓶颈而非缓冲，不误报。
     */
    private fun detectStall(position: Int) {
        if (curPlayState != PlayerConstants.STATE_PLAYING) {
            resetStall()
            return
        }
        val pos = position.toLong()
        if (pos != stallPos) {
            stallPos = pos
            stallTime = System.currentTimeMillis()
            if (stalling) {
                stalling = false
                refreshTopRightVisibility()
            }
        } else if (!stalling &&
            System.currentTimeMillis() - stallTime >= STALL_DETECT_MS &&
            lastNetSpeedKb > 0 &&
            lastBufferedPercent in 0 until BUFFER_FULL_THRESHOLD
        ) {
            stalling = true
            refreshTopRightVisibility()
        }
    }

    /** 复位卡顿缓冲标记 */
    private fun resetStall() {
        stallPos = -1
        stallTime = 0L
        stalling = false
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 设置标题（片名 : 集名），并启动跑马灯让超长片名在竖屏模式下滚动显示（setTitle） */
    fun setTitle(title: String?) {
        tvTitleTop?.text = title ?: ""
        tvTitleTop?.isSelected = true
    }

    /** 设置分辨率文本，并启动跑马灯让超长分辨率信息滚动显示（updateResolution） */
    fun setResolution(text: String?) {
        tvResolution?.text = text ?: ""
        tvResolution?.isSelected = true
    }

    /**
     * 应用屏显配置：控制右上角容器各子项显隐（网速/进度时间/系统时间/电量）。
     * 至少一个子项开启时显示整体容器，否则整体隐藏。
     * 迷你顶栏网速不受屏显网速影响（随缓冲状态显隐），缓冲时屏显容器由 onPlayStateChanged 临时隐藏避免重叠。
     */
    fun applyScreenDisplay(
        showNetSpeed: Boolean,
        showSeekTime: Boolean,
        showSysTime: Boolean,
        showBattery: Boolean
    ) {
        val container = tvTopRContainer ?: return
        val netVis = if (showNetSpeed) VISIBLE else GONE
        netSpeedTopR?.visibility = netVis
        mpbsTopR?.visibility = netVis
        tvSeekTime?.visibility = if (showSeekTime) VISIBLE else GONE
        tvSystemTime?.visibility = if (showSysTime) VISIBLE else GONE
        batteryView?.visibility = if (showBattery) VISIBLE else GONE
        screenDisplayAnyOn = showNetSpeed || showSeekTime || showSysTime || showBattery
        container.visibility = if (screenDisplayAnyOn) VISIBLE else GONE
    }

    /** 视频尺寸变化后更新横竖屏切换按钮：竖屏画面（宽<高）显示，横屏隐藏 */
    fun updateOrientationButton(videoWidth: Int, videoHeight: Int) {
        val btn = landscapePortraitBtn ?: return
        // 竖屏画面时显示横竖屏按钮并启用上下滑动换集（详情页预览小窗同样显示）
        isPortraitVideo = videoWidth > 0 && videoHeight > 0 && videoWidth < videoHeight
        btn.visibility = if (isPortraitVideo) VISIBLE else GONE
        lvPortraitImg?.setImageResource(CoreUiR.drawable.htov)
    }

    /** 横竖屏方向切换后更新按钮图标（竖屏→切横屏 vtoh，横屏→切竖屏 htov） */
    fun updateOrientationIcon(toPortrait: Boolean) {
        lvPortraitImg?.setImageResource(
            if (toPortrait) CoreUiR.drawable.vtoh else CoreUiR.drawable.htov
        )
    }

    /**
     * 判断当前宿主 Activity 是否为竖屏方向（BaseController.isPortraitMode）。
     * 竖屏方向时隐藏底部不常用按钮、顶部栏用 INVISIBLE 保持跑马灯。
     */
    private fun isPortraitMode(): Boolean {
        val activity = findActivity(context) ?: return false
        val orientation = activity.requestedOrientation
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
            orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 根据当前横竖屏方向更新底部控制栏按钮显隐 */
    fun updateBottomButtonsForOrientation() {
        updateBottomButtonsForOrientation(isPortraitMode())
    }

    /**
     * 根据横竖屏方向更新底部控制栏按钮显隐。
     * 竖屏时隐藏播放控制、切换、片头片尾等不常用按钮，避免控制栏过长需要滑动；
     * 仅保留倍速、重播、播放器、投屏、屏显、横竖屏切换；
     * playScaleTxt 为叠加在缩放按钮上的独立浮层（负 margin 定位），需一并隐藏。
     * 音轨/视轨按钮按轨道数量动态显隐，不由方向统一控制。
     */
    fun updateBottomButtonsForOrientation(portrait: Boolean) {
        // 投屏模式下横屏恢复按钮时跳过，不干预 setCastMode 已隐藏的按钮
        if (!portrait && isCastMode) return
        val hiddenButtons = listOf(
            playPrev, playPauseView, playNext, playScale, playScaleTxt, playSubtitle,
            playTimeStart, playTimeEnd, playTimeReset, playTimeDivider
        )
        val visibility = if (portrait) View.GONE else View.VISIBLE
        hiddenButtons.forEach { it?.visibility = visibility }
        // 音轨/视轨按钮不随方向统一显示，按轨道数量刷新（竖屏时隐藏）
        refreshTrackButtonVisibility()
    }

    /** 设置倍速显示（底部控制栏倍速按钮文本） */
    fun setSpeedText(speed: Float) {
        val text = String.format(Locale.getDefault(), "x%.2f", speed)
        playSpeedTxt?.text = text
    }

    /** 设置缩放显示 */
    fun setScaleText(text: String) {
        playScaleTxt?.text = text
    }

    /** 设置解码器角标（按钮右下角，布局默认 gone，设置时显示），仅显示内核名。
     *  投屏接收模式隐藏内核按钮，角标一并隐藏，避免残留 */
    fun setPlayerText(text: String) {
        playPlayerTxt?.text = text
        playPlayerTxt?.visibility = if (isCastMode) View.GONE else View.VISIBLE
    }

    /** 设置是否有弹幕数据，控制弹幕设置按钮显隐 */
    fun setHasDanmu(has: Boolean) {
        hasDanmu = has
        updateDanmuBtn()
    }

    /** 根据弹幕数据刷新弹幕按钮显隐 */
    private fun updateDanmuBtn() {
        llDanmuSetting?.visibility = if (hasDanmu) View.VISIBLE else View.GONE
    }

    /** 投屏接收模式：隐藏投屏/上下集/播放器切换/解析栏/片头片尾/重播等按钮 */
    fun setCastMode(castMode: Boolean) {
        this.isCastMode = castMode
        val visibility = if (castMode) View.GONE else View.VISIBLE
        parseRoot?.visibility = visibility
        playCast?.visibility = visibility
        playPrev?.visibility = visibility
        playNext?.visibility = visibility
        playPlayer?.visibility = visibility
        playPlayerTxt?.visibility = visibility
        playRetry?.visibility = visibility
        playTimeStart?.visibility = visibility
        playTimeEnd?.visibility = visibility
        playTimeReset?.visibility = visibility
        playTimeStep?.visibility = visibility
        playTimeDivider?.visibility = visibility
        // 投屏接收模式仅精简投屏/上下集/播放器切换等按钮，音轨/视轨按轨道数量刷新（接收端同样需要切换）
        refreshTrackButtonVisibility()
    }

    /**
     * 设置音轨数量是否大于 1（>1 显示音轨按钮，否则隐藏）。
     * 轨道数量读取失败时上层传 false，默认隐藏避免单音轨误显示。
     */
    fun setAudioTrackVisible(show: Boolean) {
        audioTrackVisible = show
        refreshTrackButtonVisibility()
    }

    /**
     * 设置视轨数量是否大于 1（>1 显示视轨按钮，否则隐藏）。
     * 杜比双层等单画面被合并后数量为 1，自动隐藏不显示多余图标。
     */
    fun setVideoTrackVisible(show: Boolean) {
        videoTrackVisible = show
        refreshTrackButtonVisibility()
    }

    /** 按音轨/视轨数量与横竖屏状态刷新轨道按钮显隐（投屏接收模式同样按轨道数量显示，接收端切换音轨/视轨同样有效） */
    private fun refreshTrackButtonVisibility() {
        val hidden = isPortraitMode()
        playAudio?.visibility = if (audioTrackVisible && !hidden) View.VISIBLE else View.GONE
        playVideo?.visibility = if (videoTrackVisible && !hidden) View.VISIBLE else View.GONE
    }

    /**
     * 重置本集片头/片尾跳过标记。
     * MyVideoView.release() 复位状态但不回调 IDLE，换集无法经状态回调感知；
     * 由宿主在装配新播放地址时调用，保证自动连播/手动切集重新执行跳过设置。
     */
    fun resetSkipApplied() {
        skipStartApplied = false
        skipEndApplied = false
    }

    /**
     * 通知本次装配的起始位置（断点续播恢复的进度，毫秒）。
     * 恢复位置已越过片头跳过点时本集不再执行片头自动跳过（进度已在片头之后，
     * 不应被拉回）；未传（0）或恢复位置在片头内时片头自动跳过照常生效。
     * 由宿主在装配播放器时调用，仅对本次播放周期生效。
     */
    fun setStartPosition(positionMs: Long) {
        restoredStartPosition = positionMs
    }

    /**
     * 绑定当前影片的片头片尾记忆键并恢复已保存的跳过时间。
     * 由宿主在播放信息加载完成后调用；换片时重新绑定并重置本集跳过标记。
     */
    fun setSkipTimeKey(key: String) {
        skipTimeKey = key
        val sp = AppPreferences.getShared(context)
        skipStartSec = if (key.isEmpty()) 0 else sp.getInt(AppConstants.PLAY_TIME_START + key, 0)
        skipEndSec = if (key.isEmpty()) 0 else sp.getInt(AppConstants.PLAY_TIME_END + key, 0)
        skipStartApplied = false
        skipEndApplied = false
        updateSkipViews()
    }

    /** 持久化当前影片的片头片尾跳过时间 */
    private fun saveSkipTimes() {
        if (skipTimeKey.isEmpty()) return
        val sp = AppPreferences.getShared(context)
        sp.putSync(AppConstants.PLAY_TIME_START + skipTimeKey, skipStartSec)
        sp.putSync(AppConstants.PLAY_TIME_END + skipTimeKey, skipEndSec)
    }

    /** 刷新片头片尾按钮文本 */
    private fun updateSkipViews() {
        playTimeStart?.text = stringForTime(skipStartSec * 1000L)
        playTimeEnd?.text = stringForTime(skipEndSec * 1000L)
    }

    /** 格式化播放时长（毫秒 → mm:ss 或 h:mm:ss） */
    private fun stringForTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * 更新网速显示，同步刷新屏显与迷你顶栏两处数值与单位。
     * 下载暂停的短瞬（小于 [NET_SPEED_HOLD_MS]）保持上次速率，避免数值闪动归 0。
     * @param tcpKbPerSec 下载速率（KB/s），来自 GSY 内核的 TrafficStats 统计（Exo 内核为 KB/s）
     */
    fun setNetSpeed(tcpKbPerSec: Long) {
        val now = System.currentTimeMillis()
        var kb = tcpKbPerSec.coerceAtLeast(0)
        if (kb > 0) {
            lastNetSpeedKb = kb
            lastNetSpeedTime = now
        } else if (now - lastNetSpeedTime < NET_SPEED_HOLD_MS) {
            // 短瞬无下载流量时保持上次速率，避免常驻网速闪动归 0
            kb = lastNetSpeedKb
        }
        // 小于 1024 KB/s 显示 KB/s，超过后按 1024 进制换算为 MB/s
        val text: String
        val unit: String
        if (kb < 1024) {
            text = String.format(Locale.getDefault(), "%.1f", kb.toDouble())
            unit = "KB/s"
        } else {
            text = String.format(Locale.getDefault(), "%.2f", kb / 1024.0)
            unit = "MB/s"
        }
        netSpeedTopR?.text = text
        mpbsTopR?.text = unit
        tvPlaySpeedTopHide?.text = text
        tvMpbsTopHide?.text = unit
    }

    /** 设置解析器列表数据并刷新展示 */
    fun setParseList(list: List<ParseBean>) {
        parseItems.clear()
        parseItems.addAll(list)
        parseAdapter?.notifyDataSetChanged()
    }

    /** 显示/隐藏解析栏（由宿主根据当前播放内容是否需要解析器决定） */
    fun showParse(show: Boolean) {
        parseRoot?.visibility = if (show) VISIBLE else GONE
    }

    /** 更新播放/暂停图标 */
    private fun updatePauseIcon(isPlaying: Boolean) {
        val res = if (isPlaying)
            CoreUiR.drawable.v_pause
        else
            CoreUiR.drawable.v_play
        playPauseImg?.setImageResource(res)
        pauseIcon?.setImageResource(
            if (isPlaying) CoreUiR.drawable.v_pause
            else CoreUiR.drawable.v_play
        )
    }

    /** 显示快进/快退进度浮层 */
    fun showSeekProgress(isForward: Boolean, currentPosition: Long, duration: Long) {
        progressContainer?.visibility = VISIBLE
        // 与右上角暂停条互斥：显示快进/快退反馈时隐藏暂停条，避免两个状态条重叠
        pauseContainer?.visibility = GONE
        progressText?.text = stringForTime(currentPosition) +
            " / " + stringForTime(duration)
        progressIcon?.setImageResource(
            if (isForward) CoreUiR.drawable.play_ffwd
            else CoreUiR.drawable.play_rewind
        )
    }

    /** 隐藏快进/快退进度浮层 */
    fun hideSeekProgress() {
        progressContainer?.visibility = GONE
        // 快进反馈结束后若仍处于暂停态，恢复右上角暂停条显示
        if (curPlayState == PlayerConstants.STATE_PAUSED) {
            showPauseBarInternal()
        }
    }

    // ------------------------------------------------------------------
    // 系统时间与电量
    // ------------------------------------------------------------------

    private fun refreshSystemTime() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvSystemTime?.text = time
        tvSysTime?.text = time
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val percent = if (scale > 0) level * 100 / scale else 0
                batteryView?.setLevel(percent)
                batteryView?.setCharging(status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(batteryReceiver, filter)
        }
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        batteryReceiver = null
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshSystemTime()
        timeHandler.sendEmptyMessageDelayed(MSG_REFRESH_TIME, TIME_REFRESH_INTERVAL)
        registerBatteryReceiver()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timeHandler.removeCallbacksAndMessages(null)
        controlHandler.removeCallbacksAndMessages(null)
        unregisterBatteryReceiver()
    }

    // ------------------------------------------------------------------
    // 返回键
    // ------------------------------------------------------------------

    /** 处理返回键，返回 true 表示控制器已消费 */
    fun handleBackPressed(): Boolean {
        if (isLocked) {
            // 锁屏状态下先解锁并显示控制栏
            isLocked = false
            lockView?.isActivated = false
            showBottom()
            return true
        }
        if (isBottomBarVisible()) {
            hideBottom()
            return true
        }
        // 当前为竖屏方向时返回，先切回横屏再放行退出，
        // 避免退出后详情页等仍以竖屏显示横屏布局
        if (isPortraitMode()) {
            findActivity(context)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        return listener?.onBackPressed() ?: false
    }

    /** 解析器列表适配器，当前默认解析项使用主题色文字高亮 */
    private class ParseListAdapter(
        private val items: List<ParseBean>,
        private val onClick: (ParseBean) -> Unit
    ) : RecyclerView.Adapter<ParseListAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_play_parse, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bean = items[position]
            holder.tv.text = bean.name ?: ""
            // 当前默认解析器使用主题色文字高亮
            holder.tv.setTextColor(
                if (bean.isDefault()) resolveThemeColor(holder.tv.context) else android.graphics.Color.WHITE
            )
            holder.tv.setOnClickListener { onClick(bean) }
        }

        private fun resolveThemeColor(context: Context): Int {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(CoreUiR.attr.color_theme, typedValue, true)
            return if (typedValue.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                typedValue.data
            } else {
                android.graphics.Color.WHITE
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tv: TextView = itemView.findViewById(R.id.tvParse)
        }
    }
}
