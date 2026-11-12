package com.tvbox.kotlin.core.player.subtitle.model

import com.tvbox.kotlin.core.player.subtitle.format.FormatASS
import com.tvbox.kotlin.core.player.subtitle.format.FormatSRT
import java.util.Hashtable
import java.util.TreeMap

/**
 * 字幕文本对象，承载元信息、样式、布局与字幕条目集合。
 * captions 以开始时间为键的 TreeMap，保证有序。
 */
class TimedTextObject {

    // 元信息
    var title: String = ""
    var description: String = ""
    var copyrigth: String = ""
    var author: String = ""
    var fileName: String = ""
    var language: String = ""

    /** 样式表（id -> Style） */
    var styling: Hashtable<String, Style> = Hashtable()

    /** 布局表（id -> Region） */
    var layout: Hashtable<String, Region> = Hashtable()

    /** 字幕条目（开始时间 -> Subtitle） */
    var captions: TreeMap<Int, Subtitle> = TreeMap()

    /** 解析过程中的非致命错误记录 */
    var warnings: String = "List of non fatal errors produced during parsing:\n\n"

    /** 是否保存为 ASS（false 则 SSA） */
    var useASSInsteadOfSSA: Boolean = true

    /** 整体偏移（毫秒） */
    var offset: Int = 0

    /** 是否已成功解析构建 */
    var built: Boolean = false

    /** 清理未被任何字幕引用的样式 */
    fun cleanUnusedStyles() {
        val used = Hashtable<String, Style>()
        captions.values.forEach { caption ->
            caption.style?.let { style ->
                if (!used.containsKey(style.iD)) used[style.iD] = style
            }
        }
        styling = used
    }

    fun toSRT(): Array<String> = FormatSRT().toFile(this) as Array<String>
    fun toASS(): Array<String> = FormatASS().toFile(this) as Array<String>
}
