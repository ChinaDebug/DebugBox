package com.tvbox.kotlin.feature.play.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * 歌词滚动显示控件。
 * 播放进度驱动 [updateProgress]，当前行居中高亮放大，前后行灰色渐隐；
 * 无歌词数据时居中显示占位提示。
 */
class LrcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val lines = ArrayList<LrcLine>()

    /** 当前高亮行索引，-1 表示未定位 */
    private var currentIndex = -1

    private val normalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#99FFFFFF")
    }

    private val currentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }

    /** 设置歌词数据；空列表时显示占位提示 */
    fun setLyric(list: List<LrcLine>) {
        lines.clear()
        lines.addAll(list)
        currentIndex = -1
        invalidate()
    }

    /** 当前播放进度驱动（毫秒），自动定位并高亮当前行 */
    fun updateProgress(position: Long) {
        if (lines.isEmpty()) return
        val idx = findIndex(position)
        if (idx != currentIndex) {
            currentIndex = idx
            invalidate()
        }
    }

    /** 二分查找最后一个 time <= position 的行索引 */
    private fun findIndex(position: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].time <= position) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        if (lines.isEmpty()) {
            normalPaint.textSize = height / 12f
            normalPaint.alpha = 140
            canvas.drawText("暂无歌词", width / 2f, centerY, normalPaint)
            return
        }
        val lineHeight = height / VISIBLE_LINES.toFloat()
        val offset = VISIBLE_LINES / 2
        for (i in -offset..offset) {
            val idx = currentIndex + i
            if (idx < 0 || idx >= lines.size) continue
            val text = lines[idx].text
            if (text.isEmpty()) continue
            val isCurrent = i == 0 && currentIndex >= 0
            val p = if (isCurrent) currentPaint else normalPaint
            p.textSize = if (isCurrent) lineHeight * 0.5f else lineHeight * 0.4f
            // 远离当前行渐隐，保证可读性
            p.alpha = if (isCurrent) 255 else (170 - abs(i) * 35).coerceIn(55, 170)
            canvas.drawText(text, width / 2f, centerY + i * lineHeight, p)
        }
    }

    companion object {
        private const val VISIBLE_LINES = 7
    }
}
