package com.tvbox.kotlin.feature.play.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.tvbox.kotlin.core.common.util.SubtitleHelper
import com.tvbox.kotlin.core.player.TrackInfo
import com.tvbox.kotlin.core.player.TrackInfoBean
import com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R

/**
 * 字幕设置弹窗。
 *
 * 提供字幕来源选择（内置 / 外置本地 / 在线搜索 / 关闭）与字幕样式设置：
 * 字号（10~60 步长 2）、字体颜色、描边颜色、字体粗细、样式预览、时间调整（±10 秒）、恢复默认设置。
 * 样式设置通过 [SubtitleHelper] 持久化，并实时回调宿主应用到正在播放的字幕视图。
 *
 * @param trackInfo 播放器当前 TrackInfo 快照，用于判断内置字幕轨道
 */
class PlaySubtitleDialog(
    context: Context,
    private val trackInfo: TrackInfo
) : BaseDialog(context, CoreUiR.style.CustomDialogStyle) {

    private companion object {
        const val MIN_TEXT_SIZE = 10
        const val MAX_TEXT_SIZE = 60
        const val SIZE_STEP = 2
        const val MAX_TIME_DELAY = 10.0
        const val TIME_STEP = 0.5

        val BOLD_NAMES = arrayOf("常规", "粗体")
        val BOLD_VALUES = intArrayOf(Typeface.NORMAL, Typeface.BOLD)
    }

    /** 字幕轨道选择回调，[TrackInfoBean.INVALID_TRACK_ID] 表示关闭字幕 */
    private var subtitleSelectedListener: ((trackId: Int) -> Unit)? = null

    /** 选择内置字幕轨道回调（打开轨道选择列表） */
    private var internalSubtitleListener: (() -> Unit)? = null

    /** 选择外置字幕文件回调 */
    private var pickExternalListener: (() -> Unit)? = null

    /** 在线搜索字幕回调 */
    private var searchSubtitleListener: (() -> Unit)? = null

    /** 字幕字号变化回调 */
    private var textSizeListener: ((size: Int) -> Unit)? = null

    /** 字幕样式（颜色/描边/粗细）变化回调 */
    private var styleChangeListener: (() -> Unit)? = null

    /** 字幕时间调整回调，参数为单次调整量（毫秒） */
    private var timeDelayListener: ((milliseconds: Int) -> Unit)? = null

    private lateinit var subtitleSizeText: TextView
    private lateinit var subtitleTextColorText: TextView
    private lateinit var subtitleStrokeColorText: TextView
    private lateinit var subtitleBoldText: TextView
    private lateinit var subtitleTimeText: TextView
    private lateinit var subtitleStyleText: SimpleSubtitleView

    fun onSubtitleSelected(listener: (trackId: Int) -> Unit): PlaySubtitleDialog {
        subtitleSelectedListener = listener
        return this
    }

    fun onInternalSubtitle(listener: () -> Unit): PlaySubtitleDialog {
        internalSubtitleListener = listener
        return this
    }

    fun onPickExternal(listener: () -> Unit): PlaySubtitleDialog {
        pickExternalListener = listener
        return this
    }

    fun onSearchSubtitle(listener: () -> Unit): PlaySubtitleDialog {
        searchSubtitleListener = listener
        return this
    }

    fun onTextSizeChange(listener: (size: Int) -> Unit): PlaySubtitleDialog {
        textSizeListener = listener
        return this
    }

    fun onStyleChange(listener: () -> Unit): PlaySubtitleDialog {
        styleChangeListener = listener
        return this
    }

    fun onTimeDelayChange(listener: (milliseconds: Int) -> Unit): PlaySubtitleDialog {
        timeDelayListener = listener
        return this
    }

    /** 对话框宿主 Activity，用于读取字幕偏好（SubtitleHelper 需 Activity 计算自适应字号） */
    private val hostActivity: Activity?
        get() = ownerActivity ?: (context as? Activity)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_play_subtitle)
        initViews()
    }

    private fun initViews() {
        // 字幕来源：内置字幕进入轨道选择列表
        findViewById<View>(R.id.selectInternal).setOnClickListener {
            dismiss()
            internalSubtitleListener?.invoke()
        }
        findViewById<View>(R.id.selectLocal).setOnClickListener {
            dismiss()
            pickExternalListener?.invoke()
        }
        findViewById<View>(R.id.selectRemote).setOnClickListener {
            dismiss()
            searchSubtitleListener?.invoke()
        }
        findViewById<View>(R.id.selectClose).setOnClickListener {
            dismiss()
            subtitleSelectedListener?.invoke(TrackInfoBean.INVALID_TRACK_ID)
        }

        // 字幕大小（默认预设 18，无 Activity 时回退）
        subtitleSizeText = findViewById(R.id.subtitleSizeText)
        subtitleSizeText.text = (hostActivity?.let { SubtitleHelper.getTextSize(it) } ?: SubtitleHelper.DEFAULT_TEXT_SIZE).toString()
        findViewById<View>(R.id.subtitleSizeMinus).setOnClickListener { changeTextSize(-SIZE_STEP) }
        findViewById<View>(R.id.subtitleSizePlus).setOnClickListener { changeTextSize(SIZE_STEP) }

        // 字体颜色
        subtitleTextColorText = findViewById(R.id.subtitleTextColorText)
        updateTextColorText()
        findViewById<View>(R.id.subtitleTextColorMinus).setOnClickListener { changeTextColor(-1) }
        findViewById<View>(R.id.subtitleTextColorPlus).setOnClickListener { changeTextColor(1) }

        // 描边颜色
        subtitleStrokeColorText = findViewById(R.id.subtitleStrokeColorText)
        updateStrokeColorText()
        findViewById<View>(R.id.subtitleStrokeColorMinus).setOnClickListener { changeStrokeColor(-1) }
        findViewById<View>(R.id.subtitleStrokeColorPlus).setOnClickListener { changeStrokeColor(1) }

        // 字体粗细
        subtitleBoldText = findViewById(R.id.subtitleBoldText)
        updateBoldText()
        findViewById<View>(R.id.subtitleBoldMinus).setOnClickListener { changeBold(-1) }
        findViewById<View>(R.id.subtitleBoldPlus).setOnClickListener { changeBold(1) }

        // 样式预览
        subtitleStyleText = findViewById(R.id.subtitleStyleText)
        subtitleStyleText.text = SubtitleHelper.getStylePreviewText()
        applyStyle(subtitleStyleText)

        // 时间调整
        subtitleTimeText = findViewById(R.id.subtitleTimeText)
        updateTimeText()
        findViewById<View>(R.id.subtitleTimeMinus).setOnClickListener { changeTimeDelay(-TIME_STEP) }
        findViewById<View>(R.id.subtitleTimePlus).setOnClickListener { changeTimeDelay(TIME_STEP) }

        // 重置（恢复默认预设：字号 18/自适应、白字黑边、常规、0 延迟）
        findViewById<View>(R.id.subtitleReset).setOnClickListener {
            SubtitleHelper.resetSettings(hostActivity)
            val size = hostActivity?.let { SubtitleHelper.getTextSize(it) } ?: SubtitleHelper.DEFAULT_TEXT_SIZE
            subtitleSizeText.text = size.toString()
            updateTextColorText()
            updateStrokeColorText()
            updateBoldText()
            subtitleTimeText.text = "0秒"
            applyStyle(subtitleStyleText)
            textSizeListener?.invoke(size)
            styleChangeListener?.invoke()
            timeDelayListener?.invoke(0)
        }
    }

    private fun changeTextSize(step: Int) {
        val curSize = subtitleSizeText.text.toString().toIntOrNull() ?: return
        val newSize = (curSize + step).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        subtitleSizeText.text = newSize.toString()
        SubtitleHelper.setTextSize(newSize)
        applyStyle(subtitleStyleText)
        textSizeListener?.invoke(newSize)
    }

    private fun changeTextColor(delta: Int) {
        val colors = SubtitleHelper.TEXT_COLORS
        val current = SubtitleHelper.getTextColorIndex()
        val next = (current + delta + colors.size) % colors.size
        SubtitleHelper.setTextColor(colors[next])
        updateTextColorText()
        applyStyle(subtitleStyleText)
        styleChangeListener?.invoke()
    }

    private fun changeStrokeColor(delta: Int) {
        val colors = SubtitleHelper.STROKE_COLORS
        val current = SubtitleHelper.getStrokeColorIndex()
        val next = (current + delta + colors.size) % colors.size
        SubtitleHelper.setStrokeColor(colors[next])
        updateStrokeColorText()
        applyStyle(subtitleStyleText)
        styleChangeListener?.invoke()
    }

    private fun changeBold(delta: Int) {
        val current = getBoldIndex()
        val next = (current + delta + BOLD_VALUES.size) % BOLD_VALUES.size
        SubtitleHelper.setTextBold(BOLD_VALUES[next])
        updateBoldText()
        applyStyle(subtitleStyleText)
        styleChangeListener?.invoke()
    }

    private fun changeTimeDelay(step: Double) {
        val curTime = parseTimeText()
        val time = (curTime + step).coerceIn(-MAX_TIME_DELAY, MAX_TIME_DELAY)
        subtitleTimeText.text = formatTimeText(time)
        SubtitleHelper.setTimeDelay(Math.round(time * 1000).toInt())
        timeDelayListener?.invoke(Math.round(step * 1000).toInt())
    }

    private fun parseTimeText(): Double {
        val text = subtitleTimeText.text.toString().replace("秒", "").replace("+", "")
        return text.toDoubleOrNull() ?: 0.0
    }

    private fun formatTimeText(time: Double): String = when {
        time == 0.0 -> "0秒"
        time > 0 -> "+${time}秒"
        else -> "${time}秒"
    }

    private fun updateTimeText() {
        val delay = SubtitleHelper.getTimeDelay()
        subtitleTimeText.text = formatTimeText(delay / 1000.0)
    }

    private fun updateTextColorText() {
        subtitleTextColorText.text = SubtitleHelper.TEXT_COLOR_NAMES[SubtitleHelper.getTextColorIndex()]
    }

    private fun updateStrokeColorText() {
        subtitleStrokeColorText.text = SubtitleHelper.STROKE_COLOR_NAMES[SubtitleHelper.getStrokeColorIndex()]
    }

    private fun updateBoldText() {
        subtitleBoldText.text = BOLD_NAMES[getBoldIndex()]
    }

    private fun getBoldIndex(): Int {
        val bold = SubtitleHelper.getTextBold()
        for (i in BOLD_VALUES.indices) {
            if (BOLD_VALUES[i] == bold) return i
        }
        return 0
    }

    /** 将当前样式应用到预览视图（字号固定取布局 ts_20，仅预览颜色/描边/粗细）。
     *  预览标签宽高固定，字号无实际大小显示效果，故不随实际字幕字号变化。 */
    private fun applyStyle(view: SimpleSubtitleView) {
        view.setTextColor(SubtitleHelper.getTextColor())
        view.setBackGroundTextColor(SubtitleHelper.getStrokeColor())
        view.setShadowLayer(0f, 0f, 0f, 0)
        view.setTypeface(if (SubtitleHelper.getTextBold() == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        // 文案与当前设置一致（如"红色黑边"），随 +/- 调整同步刷新
        view.text = SubtitleHelper.getStylePreviewText()
    }
}
