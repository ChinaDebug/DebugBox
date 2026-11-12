package com.tvbox.kotlin.core.player.subtitle.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Html
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.widget.TextView
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.subtitle.DefaultSubtitleEngine
import com.tvbox.kotlin.core.player.subtitle.SubtitleEngine
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle

/**
 * 字幕渲染视图。
 *
 * 持有 [DefaultSubtitleEngine] 完成字幕加载与刷新，自身作为监听者将字幕文本渲染到视频上方，
 * 并支持描边绘制以保证文字在任意背景下的可读性。同时实现 [SubtitleEngine] 接口对外转发。
 */
class SimpleSubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr),
    SubtitleEngine,
    SubtitleEngine.OnSubtitleChangeListener,
    SubtitleEngine.OnSubtitlePreparedListener {

    private val engine = DefaultSubtitleEngine(context)

    /** 是否使用内置字幕（播放器内部轨道） */
    @JvmField
    var isInternal: Boolean = false

    /** 当前媒体是否包含内置字幕 */
    @JvmField
    var hasInternal: Boolean = false

    private var strokeColor: Int = Color.BLACK

    init {
        engine.setOnSubtitlePreparedListener(this)
        engine.setOnSubtitleChangeListener(this)
    }

    //region 字幕回调
    override fun onSubtitlePrepared(subtitles: List<Subtitle>?) {
        start()
    }

    override fun onSubtitleChanged(subtitle: Subtitle?) {
        if (subtitle == null || subtitle.content.isEmpty()) {
            text = ""
            return
        }
        var text = subtitle.content
        // 过滤 ASS 残留的 Dialogue 行与 SVG 路径字幕
        if (text.startsWith("Dialogue:") || text.startsWith("m ")) {
            text = ""
            return
        }
        text = text.replace("\r\n", "<br />")
            .replace("\r", "<br />")
            .replace("\n", "<br />")
            .replace("\\N", "<br />")
            .replace(Regex("\\{[\\s\\S]*?\\}"), "")
            .replace(Regex("^.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,"), "")
        setText(Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY))
    }
    //endregion

    //region SubtitleEngine 转发
    override fun setSubtitlePath(path: String?) {
        isInternal = false
        engine.setSubtitlePath(path)
    }

    override fun setSubtitleDelay(milliseconds: Int) {
        engine.setSubtitleDelay(milliseconds)
    }

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        engine.setPlaySubtitleCacheKey(cacheKey)
    }

    override fun getPlaySubtitleCacheKey(): String? = engine.getPlaySubtitleCacheKey()

    override fun start() = engine.start()

    override fun pause() = engine.pause()

    override fun resume() = engine.resume()

    override fun stop() = engine.stop()

    override fun reset() = engine.reset()

    override fun destroy() = engine.destroy()

    override fun bindToPlayer(mediaPlayer: MyVideoView?) {
        engine.bindToPlayer(mediaPlayer)
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        engine.setOnSubtitlePreparedListener(listener)
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        engine.setOnSubtitleChangeListener(listener)
    }

    override fun setOnSubtitleLoadListener(listener: SubtitleEngine.OnSubtitleLoadListener?) {
        engine.setOnSubtitleLoadListener(listener)
    }
    //endregion

    /** 清除当前播放项的字幕缓存记忆并清理网络字幕临时文件 */
    fun clearSubtitleCache() = engine.clearCache()

    /** 读取缓存记忆的字幕路径，未缓存返回 null */
    fun getCachedSubtitlePath(key: String?): String? = engine.getCachedSubtitlePath(key)

    override fun onDetachedFromWindow() {
        destroy()
        super.onDetachedFromWindow()
    }

    //region 描边绘制
    override fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) {
        // radius > 0 表示阴影模式，同步更新描边色；否则保持当前描边色用于描边模式
        if (radius > 0) {
            strokeColor = color
        }
        super.setShadowLayer(radius, dx, dy, color)
    }

    /** 设置描边颜色（不影响阴影） */
    fun setBackGroundTextColor(color: Int) {
        if (color != strokeColor) {
            strokeColor = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawStrokeText(canvas)
        super.onDraw(canvas)
    }

    private fun drawStrokeText(canvas: Canvas) {
        val text = text
        if (TextUtils.isEmpty(text)) return
        val layout: Layout = layout ?: return
        val strokePaint = TextPaint(paint).apply {
            style = Paint.Style.STROKE
            // 描边宽度按 1dp 自适应（各密度下视觉一致），避免过粗描边盖住填充色，
            // 导致设置的字体颜色（如黄色）在预览/播放中呈现为其它颜色
            strokeWidth = maxOf(1f, resources.displayMetrics.density)
            color = strokeColor
        }
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        for (i in 0 until layout.lineCount) {
            val start = layout.getLineStart(i)
            val end = layout.getLineEnd(i)
            val line = text.subSequence(start, end)
            val x = layout.getLineLeft(i)
            val y = layout.getLineBaseline(i).toFloat()
            canvas.drawText(line, 0, line.length, x, y, strokePaint)
        }
        canvas.restore()
    }
    //endregion
}
