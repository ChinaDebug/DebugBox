package com.tvbox.kotlin.core.common.bean

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 直播回看地址模板（FongMi 直播源 catchup 配置）。
 *
 * [type]="default" 时直接使用扩展后的 [source]；否则先按 [replace]（"正则,替换"）
 * 改写频道地址，再把 [source] 追加到末尾。[source] 支持时间占位符：
 * `{utc}`、`{utcend}`、`{(b)yyyyMMddHHmmss}`、`{(e)HHmmss}`。
 */
class LiveCatchup {

    var type: String = ""
    var source: String = ""
    var replace: String = ""

    /** 是否具备可用的回看模板 */
    val available: Boolean get() = source.isNotEmpty()

    /** 生成回看地址；配置不完整或频道地址为空时返回 null */
    fun buildUrl(channelUrl: String, start: Date, end: Date): String? {
        if (source.isEmpty() || channelUrl.isEmpty()) return null
        val expanded = expand(source, start, end)
        if (type.equals("default", ignoreCase = true)) return expanded

        var replayUrl = channelUrl
        if (replace.isNotEmpty()) {
            val parts = replace.split(",", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) {
                replayUrl = runCatching { replayUrl.replace(Regex(parts[0]), parts[1]) }
                    .getOrDefault(replayUrl)
            }
        }
        // 频道地址已带查询串时，模板参数改用 & 连接
        val queryIndex = replayUrl.indexOf('?')
        val finalSource = if (queryIndex in 0 until replayUrl.length - 1) {
            expanded.replace("?", "&")
        } else {
            expanded
        }
        return replayUrl + finalSource
    }

    private fun expand(template: String, start: Date, end: Date): String {
        val matcher = TOKEN_PATTERN.matcher(template)
        val result = StringBuffer()
        while (matcher.find()) {
            val token = matcher.group(1) ?: continue
            val tagMatcher = TAG_PATTERN.matcher(token)
            val value = if (tagMatcher.find()) formatToken(tagMatcher.group(1).orEmpty(), start, end) else ""
            matcher.appendReplacement(result, Matcher.quoteReplacement(value))
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun formatToken(tag: String, start: Date, end: Date): String {
        if (tag == "utc" || tag.startsWith("utc:")) return (start.time / 1000).toString()
        if (tag == "utcend" || tag.startsWith("utcend:")) return (end.time / 1000).toString()
        val bracketIndex = tag.indexOf(')')
        if (bracketIndex < 0) return ""
        return when {
            tag.startsWith("(b") -> formatTime(start, tag.substring(bracketIndex + 1))
            tag.startsWith("(e") -> formatTime(end, tag.substring(bracketIndex + 1))
            else -> ""
        }
    }

    private fun formatTime(time: Date, pattern: String): String {
        if (pattern == "timestamp") return (time.time / 1000).toString()
        return runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(time) }
            .getOrDefault("")
    }

    private companion object {
        private val TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]*\\})")
        private val TAG_PATTERN = Pattern.compile("\\{([^}]*)\\}")
    }
}
