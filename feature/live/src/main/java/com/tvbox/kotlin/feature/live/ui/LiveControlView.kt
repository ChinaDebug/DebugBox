package com.tvbox.kotlin.feature.live.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.feature.live.R
import kotlin.math.abs

/**
 * 直播控制器：加载 [R.layout.player_live_control_view]，
 * 将单击、长按、滑动与播放状态回调给宿主
 * （状态由宿主从 [com.tvbox.kotlin.core.player.MyVideoView] 监听器转发调用 [onPlayStateChanged]）。
 *
 * 交互：
 * - 单击屏幕左 2/5：弹出频道列表
 * - 单击屏幕中 1/5：切换频道信息条
 * - 单击屏幕右 2/5：弹出设置面板
 * - 长按：弹出设置面板
 * - 左右 Fling：切换播放源
 */
class LiveControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    View.OnTouchListener {

    /** 控制器事件回调 */
    interface Listener {
        fun singleTap(e: MotionEvent): Boolean
        fun longPress()
        fun playStateChanged(playState: Int)
        fun changeSource(direction: Int)
        /** 双击切换播放/暂停 */
        fun togglePlay()
    }

    private var listener: Listener? = null

    /** 是否开启双击暂停 */
    private var doubleTapTogglePlayEnabled = false

    /** 当前播放状态，由 onPlayStateChanged 回调更新 */
    private var currentPlayState = PlayerConstants.STATE_IDLE

    private val gestureDetector = GestureDetector(context, this)

    init {
        LayoutInflater.from(context).inflate(R.layout.player_live_control_view, this, true)
        setOnTouchListener(this)
        // 隐藏点播专用控件：该布局复用了点播控制布局，
        // 其中的转圈迷你顶栏、暂停进度浮层、快进快退进度、
        // 音量/亮度弹窗等均不适用于直播，避免直播画面右上角常驻多余控件
        findViewById<View>(R.id.top_container_hide)?.visibility = View.GONE
        findViewById<View>(R.id.tv_pause_container)?.visibility = View.GONE
        findViewById<View>(R.id.tv_progress_container)?.visibility = View.GONE
        findViewById<View>(R.id.tv_slide_progress_text)?.visibility = View.GONE
        findViewById<View>(R.id.dialog_volume)?.visibility = View.GONE
        findViewById<View>(R.id.dialog_bright)?.visibility = View.GONE
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setDoubleTapTogglePlayEnabled(enabled: Boolean) {
        doubleTapTogglePlayEnabled = enabled
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (event == null) return false
        return gestureDetector.onTouchEvent(event)
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (listener?.singleTap(e) == true) return true
        return false
    }

    override fun onLongPress(e: MotionEvent) {
        listener?.longPress()
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        val start = e1 ?: return false
        val minFlingDistance = 100
        val minFlingVelocity = 10
        if (start.x - e2.x > minFlingDistance && abs(velocityX) > minFlingVelocity) {
            listener?.changeSource(-1)
        } else if (e2.x - start.x > minFlingDistance && abs(velocityX) > minFlingVelocity) {
            listener?.changeSource(1)
        }
        return false
    }

    /** 播放状态变化回调，由宿主转发 MyVideoView 的播放状态 */
    fun onPlayStateChanged(playState: Int) {
        currentPlayState = playState
        listener?.playStateChanged(playState)
    }

    /** 双击切换播放/暂停 */
    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (doubleTapTogglePlayEnabled && isInPlaybackState()) {
            listener?.togglePlay()
        }
        return true
    }

    private fun isInPlaybackState(): Boolean {
        val state = currentPlayState
        return state != PlayerConstants.STATE_ERROR &&
            state != PlayerConstants.STATE_IDLE &&
            state != PlayerConstants.STATE_PREPARING &&
            state != PlayerConstants.STATE_START_ABORT &&
            state != PlayerConstants.STATE_PLAYBACK_COMPLETED
    }

    // 以下手势回调保持空实现，仅满足接口契约
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) = Unit

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean = false

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
}
