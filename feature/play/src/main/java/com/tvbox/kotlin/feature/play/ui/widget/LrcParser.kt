package com.tvbox.kotlin.feature.play.ui.widget

/** LRC 歌词行：time 为歌词出现时间（毫秒） */
data class LrcLine(val time: Long, val text: String)

/**
 * LRC 歌词解析器。
 * 支持 `[mm:ss]`、`[mm:ss.xx]`、`[mm:ss:xx]` 时间标签与单行多时间标签展开；
 * 元信息行（[ti:]/[ar:]/[al:] 等，无时间标签）自动忽略。
 */
object LrcParser {

    /** 时间标签：[mm:ss(.xx)] 或 [mm:ss:xx]，秒与小数部分允许 1~3 位 */
    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    /** 解析 LRC 文本为按时间升序的歌词行；无有效时间行时返回空列表 */
    fun parse(lrc: String): List<LrcLine> {
        val lines = ArrayList<LrcLine>()
        for (raw in lrc.split('\n', '\r')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val minute = tag.groupValues[1].toLongOrNull() ?: continue
                val second = tag.groupValues[2].toLongOrNull() ?: continue
                val fraction = tag.groupValues[3].toLongOrNull() ?: 0L
                // 小数位：>=100 视为毫秒；否则视为百分之一秒
                val fractionMs = if (fraction >= 100L) fraction else fraction * 10L
                lines.add(LrcLine(minute * 60_000L + second * 1_000L + fractionMs, text))
            }
        }
        lines.sortBy { it.time }
        return lines
    }
}
