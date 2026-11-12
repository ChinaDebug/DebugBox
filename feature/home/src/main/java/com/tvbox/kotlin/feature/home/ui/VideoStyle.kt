package com.tvbox.kotlin.feature.home.ui

import android.view.ViewGroup
import android.widget.ImageView
import me.jessyan.autosize.utils.AutoSizeUtils
import org.json.JSONObject

/**
 * 首页源站展示风格（homeSourceBean.style）解析与应用。
 *
 * style 为源站配置的 JSON 字符串，如 `{"ratio":0.67,"type":"rect"}`：
 * - ratio：海报宽高比（宽 / 高）；
 * - type：展示风格（rect 网格 / list 列表）。
 * 首页据此动态调整海报容器宽高，使卡片比例贴合源站约定，避免 fitXY 拉伸变形。
 */
object VideoStyle {

    /** 展示风格：ratio 为宽高比，type 为风格 */
    data class Style(val ratio: Float, val type: String)

    /** 解析源站 style JSON，非法或空时返回 null（保持默认布局） */
    fun parseStyle(style: String?): Style? {
        if (style.isNullOrEmpty()) return null
        return try {
            val json = JSONObject(style)
            // ratio 非法（缺失/为 0/负数/NaN/Infinity）时按默认布局处理：
            // 否则 applyStyleToImage 中 宽/ratio 会产生除零（无限高）、负高或超大高度，直接导致网格错乱
            val ratio = json.optDouble("ratio", -1.0).toFloat()
            if (ratio <= 0f || ratio.isNaN() || ratio.isInfinite()) return null
            Style(ratio, json.optString("type", ""))
        } catch (_: Throwable) {
            null
        }
    }

    /** 按风格计算网格列数；无 style 或未知类型时使用默认列数 */
    fun spanCountByStyle(style: Style?, defaultCount: Int): Int {
        if (style == null) return defaultCount
        return when (style.type) {
            "rect" -> when {
                style.ratio >= 1.7 -> 3 // 横图
                style.ratio >= 1.0 -> 4 // 4:3 及更宽，与 getStyleDefaultWidth 的 4 列宽一致，避免默认列数下卡片超宽溢出
                else -> defaultCount
            }
            "list" -> 1
            else -> defaultCount
        }
    }

    /**
     * 网格列宽（mm）：按设计稿 1280 宽、左右 padding 各 60mm、项间距 20mm 计算，
     * 与分类页 GridFragment 布局（fragment_grid.xml）保持一致。
     * 卡片宽度不得超过列宽，否则横图卡片会右侧溢出（片名背景色超出卡片边界）。
     */
    fun columnWidthBySpan(spanCount: Int): Int {
        val designWidth = 1280
        val horizontalPadding = 60 * 2
        val spacing = 20
        return (designWidth - horizontalPadding - spacing * (spanCount - 1)) / spanCount
    }

    /** 按风格获取默认海报宽度（mm）：竖图 214、横图 3 列 373、其余（4 列）275 */
    fun getStyleDefaultWidth(style: Style?): Int {
        if (style == null) return 280
        return when {
            style.ratio < 1.0 -> 214
            style.ratio > 1.7 -> columnWidthBySpan(3)
            else -> columnWidthBySpan(4)
        }
    }

    /** 默认竖图卡片尺寸（mm），与 item 布局 vs_214 / vs_280 保持一致 */
    private const val DEFAULT_CARD_WIDTH = 214
    private const val DEFAULT_CARD_HEIGHT = 280

    /**
     * 按 style 动态调整 ImageView 父容器（卡片）的宽高：
     * 宽 = 默认宽度，高 = 宽 / ratio，使卡片比例与海报一致。
     * style 为 null 时恢复默认竖图尺寸（214 × 280），用于切换回默认内容（如降级豆瓣热播/历史）时
     * 纠正 ViewHolder 复用残留的源站风格尺寸。
     */
    fun applyStyleToImage(imageView: ImageView, style: Style?) {
        val parent = imageView.parent as? ViewGroup ?: return
        val defaultWidth = if (style == null) DEFAULT_CARD_WIDTH else getStyleDefaultWidth(style)
        // ratio 非法时兜底为默认竖图高度（parseStyle 已过滤，此处防御构造出的 Style）
        val height = when {
            style == null || style.ratio <= 0f || style.ratio.isNaN() || style.ratio.isInfinite() -> DEFAULT_CARD_HEIGHT
            else -> (defaultWidth / style.ratio).toInt()
        }
        val lp = parent.layoutParams
        lp.width = AutoSizeUtils.mm2px(imageView.context, defaultWidth.toFloat())
        lp.height = AutoSizeUtils.mm2px(imageView.context, height.toFloat())
        parent.layoutParams = lp
    }
}
