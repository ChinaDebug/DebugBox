package com.tvbox.kotlin.core.player.subtitle

import com.tvbox.kotlin.core.player.subtitle.model.TimedTextObject

/**
 * 字幕加载成功结果。
 *
 * @param timedTextObject 解析后的字幕对象
 * @param fileName 文件名（用于缓存命名与展示）
 * @param content 原始文本内容（用于网络字幕落盘缓存）
 * @param subtitlePath 字幕源路径
 */
data class SubtitleLoadSuccessResult(
    var timedTextObject: TimedTextObject? = null,
    var fileName: String = "",
    var content: String = "",
    var subtitlePath: String = ""
)
