package com.tvbox.kotlin.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 纯音频模式均衡器波形：圆角柱 + 白色渐变 + 对称包络 + 平滑过渡动画。
 * 高度由正弦包络叠加两层波形驱动，起伏自然（非随机跳变），贴合音乐播放器风格。
 */
class AudioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val invalidateRunnable = Runnable { invalidate() }

    /** 柱体目标高度（0..1）与当前平滑高度（0..1），随 [barCount] 自适应重建 */
    private var barCount = 21
    private var target = FloatArray(barCount)
    private var smooth = FloatArray(barCount)

    private var viewWidth = 0
    private var viewHeight = 0
    private var gradient: LinearGradient? = null
    private var animPhase = 0f

    init {
        paint.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        // 柱数随宽度自适应，间距固定，保证小窗/全屏都不过密；柱数变化时同步重建高度数组，
        // 否则 onDraw 访问 target[i] 会越界崩溃
        val newCount = ((w - SPACE) / (MIN_BAR + SPACE)).coerceIn(7, 31)
        if (newCount != barCount) {
            barCount = newCount
            target = FloatArray(barCount)
            smooth = FloatArray(barCount)
        }
        // 白色渐变：顶部亮色、底部半透明，竖条由底部向上生长更立体
        gradient = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(COLOR_BOTTOM, COLOR_TOP),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return
        animPhase += 0.07f
        // 目标高度：正弦包络（中间高两边低）叠加两层波形成自然起伏，非随机跳变
        for (i in 0 until barCount) {
            val x = i.toFloat() / (barCount - 1).coerceAtLeast(1)
            val envelope = sin(PI * x).toFloat()
            val wave1 = sin(x * 3.0 * PI + animPhase).toFloat()
            val wave2 = sin(x * 7.0 * PI - animPhase * 1.6 + 1.3).toFloat()
            val mix = 0.6f * wave1 + 0.4f * wave2
            target[i] = envelope * (0.18f + 0.72f * (mix * 0.5f + 0.5f))
        }
        // 平滑插值：柱体高度过渡自然，避免跳变
        for (i in 0 until barCount) {
            smooth[i] += (target[i] - smooth[i]) * 0.32f
        }
        // 绘制圆角柱
        val gap = SPACE.toFloat()
        val barW = (viewWidth - gap * (barCount - 1)) / barCount
        val radius = (barW / 2f).coerceAtMost(4f * density)
        val minH = 2f * density
        paint.shader = gradient
        for (i in 0 until barCount) {
            val left = i * (barW + gap)
            val barH = (smooth[i] * viewHeight).coerceAtLeast(minH)
            canvas.drawRoundRect(
                left, viewHeight - barH, left + barW, viewHeight.toFloat(),
                radius, radius, paint
            )
        }
        handler.removeCallbacks(invalidateRunnable)
        handler.postDelayed(invalidateRunnable, FRAME_MS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    private val density: Float
        get() = resources.displayMetrics.density

    companion object {
        private const val SPACE = 3 // 柱间距 px
        private const val MIN_BAR = 4 // 最小柱宽 px，用于自适应柱数
        private const val FRAME_MS = 33L // 约 30fps
        private const val COLOR_TOP = 0xE6FFFFFF.toInt() // 亮白 90%
        private const val COLOR_BOTTOM = 0x33FFFFFF.toInt() // 半透明白 20%
    }
}
