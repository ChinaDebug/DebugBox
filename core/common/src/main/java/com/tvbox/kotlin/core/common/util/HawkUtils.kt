package com.tvbox.kotlin.core.common.util

import com.tvbox.kotlin.core.common.AppConstants

/**
 * 偏好读写工具
 * 仅保留通用偏好读写，依赖 ApiConfig/资源数组的方法由上层模块（feature:setting 等）实现。
 */
object HawkUtils {

    private const val DANMU_OPEN = "danmu_open"
    private const val DANMU_MAXLINE = "danmu_maxline"
    private const val DANMU_SPEED = "danmu_speed"
    private const val DANMU_ALPHA = "danmu_alpha"
    private const val DANMU_SIZESCALE = "danmu_sizescale"
    private const val DANMU_COLOR = "danmu_color"
    private const val DANMU_FANCY = "danmu_fancy"
    private const val DANMU_API = "danmu_api"

    /** 内置在线弹幕接口默认地址（弹弹play 兼容 API，可在弹幕设置中修改） */
    const val DEFAULT_DANMU_API = "https://logvardanmu.konfan.cn/87654321"

    fun getDanmuOpen(): Boolean = AppPreferencesHolder.getBoolean(DANMU_OPEN, false)

    fun setDanmuOpen(danmuOpen: Boolean) {
        AppPreferencesHolder.putBooleanSync(DANMU_OPEN, danmuOpen)
    }

    fun getDanmuMaxLine(): Int = AppPreferencesHolder.getInt(DANMU_MAXLINE, 3)

    fun setDanmuMaxLine(danmuMaxLine: Int) {
        AppPreferencesHolder.putIntSync(DANMU_MAXLINE, danmuMaxLine)
    }

    fun getDanmuSpeed(): Float = AppPreferencesHolder.getFloat(DANMU_SPEED, 1.5f)

    fun setDanmuSpeed(danmuSpeed: Float) {
        AppPreferencesHolder.putFloatSync(DANMU_SPEED, danmuSpeed)
    }

    fun getDanmuAlpha(): Float = AppPreferencesHolder.getFloat(DANMU_ALPHA, 90 / 100.0f)

    fun setDanmuAlpha(danmuAlpha: Float) {
        AppPreferencesHolder.putFloatSync(DANMU_ALPHA, danmuAlpha)
    }

    /** 弹幕字号缩放（scaleTextSize），默认 1.0：整体放大渲染字号，文字更清晰 */
    fun getDanmuSizeScale(): Float = AppPreferencesHolder.getFloat(DANMU_SIZESCALE, 1.0f)

    fun setDanmuSizeScale(danmuSizeScale: Float) {
        AppPreferencesHolder.putFloatSync(DANMU_SIZESCALE, danmuSizeScale)
    }

    fun getDanmuColor(): Boolean = AppPreferencesHolder.getBoolean(DANMU_COLOR, false)

    fun setDanmuColor(color: Boolean) {
        AppPreferencesHolder.putBooleanSync(DANMU_COLOR, color)
    }

    /** 花式弹幕（背景色+加强描边），默认关闭 */
    fun getDanmuFancy(): Boolean = AppPreferencesHolder.getBoolean(DANMU_FANCY, false)

    fun setDanmuFancy(fancy: Boolean) {
        AppPreferencesHolder.putBooleanSync(DANMU_FANCY, fancy)
    }

    /** 在线弹幕接口根地址（弹弹play 兼容），空值时回退内置默认地址 */
    fun getDanmuApi(): String {
        val value = AppPreferencesHolder.getString(DANMU_API, DEFAULT_DANMU_API).trim()
        return value.ifEmpty { DEFAULT_DANMU_API }
    }

    fun setDanmuApi(url: String) {
        AppPreferencesHolder.putSync(DANMU_API, url.trim())
    }
}
