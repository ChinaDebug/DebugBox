package com.tvbox.kotlin.core.common.util

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import com.tvbox.kotlin.core.common.AppConstants

/** 字幕偏好与样式常量 */
object SubtitleHelper {

    /** 无 Activity 可用时的默认字幕字号（sp） */
    const val DEFAULT_TEXT_SIZE = 18

    @JvmField
    val TEXT_COLORS = intArrayOf(
        Color.WHITE,
        Color.YELLOW,
        Color.GREEN,
        Color.CYAN,
        Color.MAGENTA,
        Color.RED,
        Color.parseColor("#FF9800"),
        Color.parseColor("#E5E5E5"),
        Color.parseColor("#FFEB3B"),
        Color.parseColor("#FFB6C1")
    )

    @JvmField
    val TEXT_COLOR_NAMES = arrayOf("白色", "黄色", "绿色", "青色", "紫色", "红色", "橙色", "灰色", "浅黄", "粉色")

    @JvmField
    val STROKE_COLORS = intArrayOf(
        Color.BLACK,
        Color.WHITE,
        Color.YELLOW,
        Color.GREEN,
        Color.CYAN,
        Color.RED,
        Color.parseColor("#FF9800"),
        Color.TRANSPARENT
    )

    @JvmField
    val STROKE_COLOR_NAMES = arrayOf("黑边", "白边", "黄边", "绿边", "青边", "红边", "橙边", "无边")

    /** 根据屏幕宽度自适应字幕字号 */
    fun getSubtitleTextAutoSize(activity: Activity): Int {
        val cfg = activity.resources.configuration
        val smallestScreenWidthDp = cfg.smallestScreenWidthDp
        return when {
            smallestScreenWidthDp >= 960 -> 28
            smallestScreenWidthDp >= 600 -> 22
            else -> DEFAULT_TEXT_SIZE
        }
    }

    fun getTextSize(activity: Activity): Int =
        AppPreferencesHolder.getInt(AppConstants.SUBTITLE_TEXT_SIZE, getSubtitleTextAutoSize(activity))

    // 写入统一走同步缓存更新（putSync），保证写入后立即 get 读到新值，
    // 否则异步写盘竞态会导致字幕设置界面预览显示上一次的颜色/描边/粗细
    fun setTextSize(size: Int) {
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_SIZE, size)
    }

    fun getTimeDelay(): Int = AppPreferencesHolder.getInt(AppConstants.SUBTITLE_TIME_DELAY, 0)

    fun setTimeDelay(delay: Int) {
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TIME_DELAY, delay)
    }

    fun getTextColor(): Int = AppPreferencesHolder.getInt(AppConstants.SUBTITLE_TEXT_COLOR, Color.WHITE)

    fun setTextColor(color: Int) {
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_COLOR, color)
    }

    fun getTextColorIndex(): Int {
        val currentColor = getTextColor()
        for (i in TEXT_COLORS.indices) {
            if (TEXT_COLORS[i] == currentColor) return i
        }
        return 0
    }

    fun getStrokeColor(): Int = AppPreferencesHolder.getInt(AppConstants.SUBTITLE_STROKE_COLOR, Color.BLACK)

    fun setStrokeColor(color: Int) {
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_STROKE_COLOR, color)
    }

    fun getStrokeColorIndex(): Int {
        val currentColor = getStrokeColor()
        for (i in STROKE_COLORS.indices) {
            if (STROKE_COLORS[i] == currentColor) return i
        }
        return 0
    }

    fun getTextBold(): Int = AppPreferencesHolder.getInt(AppConstants.SUBTITLE_TEXT_BOLD, Typeface.NORMAL)

    fun setTextBold(bold: Int) {
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_BOLD, bold)
    }

    /** 重置所有字幕设置（同步更新缓存，保证重置后立即读取生效）。
     * 无 Activity 时字号回退到默认预设 18。 */
    fun resetSettings(activity: Activity?) {
        val defaultSize = activity?.let { getSubtitleTextAutoSize(it) } ?: DEFAULT_TEXT_SIZE
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_SIZE, defaultSize)
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_COLOR, Color.WHITE)
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_STROKE_COLOR, Color.BLACK)
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TEXT_BOLD, Typeface.NORMAL)
        AppPreferencesHolder.putIntSync(AppConstants.SUBTITLE_TIME_DELAY, 0)
    }

    /** 样式预览文本 */
    fun getStylePreviewText(): String =
        TEXT_COLOR_NAMES[getTextColorIndex()] + STROKE_COLOR_NAMES[getStrokeColorIndex()]

    fun getStyleName(index: Int): String = getStylePreviewText()
}
