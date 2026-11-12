package com.tvbox.kotlin.core.player.subtitle.format

import com.tvbox.kotlin.core.player.subtitle.exception.FatalParsingException
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle
import com.tvbox.kotlin.core.player.subtitle.model.Time
import com.tvbox.kotlin.core.player.subtitle.model.TimedTextObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * WebVTT 字幕格式解析与序列化。
 *
 * 支持 `WEBVTT` 头、NOTE 注释块、`HH:MM:SS.mmm` 与 `MM:SS.mmm` 两种时间格式。
 * 去除 `<...>` 内联标签以兼容 SimpleSubtitleView 的纯文本渲染。
 */
class FormatVTT : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, input: InputStream): TimedTextObject {
        val tto = TimedTextObject()
        tto.fileName = fileName
        val br = BufferedReader(InputStreamReader(input))
        try {
            // 跳过 WEBVTT 头部及空行
            var line: String? = br.readLine()
            while (line != null && (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE"))) {
                line = br.readLine()
            }
            while (line != null) {
                // 跳过空行与 NOTE 块
                if (line.isEmpty()) {
                    line = br.readLine()
                    continue
                }
                if (line.startsWith("NOTE")) {
                    line = br.readLine()
                    while (line != null && line.isNotEmpty()) line = br.readLine()
                    continue
                }
                // 时间行可能紧跟可选的 cue 标识，若当前行不含 --> 则视作标识，读取下一行
                var timeLine = line
                if (!timeLine.contains("-->")) {
                    timeLine = br.readLine() ?: break
                }
                val times = timeLine.split("-->")
                if (times.size < 2) {
                    line = br.readLine()
                    continue
                }
                val caption = Subtitle()
                caption.start = Time("hh:mm:ss,ms", normalizeTime(times[0].trim()))
                caption.end = Time("hh:mm:ss,ms", normalizeTime(times[1].trim().split(" ")[0]))
                val text = StringBuilder()
                line = br.readLine()
                while (line != null && line.isNotEmpty()) {
                    text.append(line).append("<br />")
                    line = br.readLine()
                }
                caption.content = text.toString().replace(Regex("\\<.*?\\>"), "")
                var key = caption.start.mseconds
                while (tto.captions.containsKey(key)) key++
                tto.captions.put(key, caption)
                line = br.readLine()
            }
        } finally {
            input.runCatching { close() }
        }
        tto.built = true
        return tto
    }

    /** 将 VTT 时间（MM:SS.mmm 或 HH:MM:SS.mmm）规范为 SRT 的 HH:MM:SS,mmm */
    private fun normalizeTime(raw: String): String {
        val dotIndex = raw.indexOf('.')
        val main = if (dotIndex >= 0) raw.substring(0, dotIndex) else raw
        val ms = if (dotIndex >= 0) raw.substring(dotIndex + 1) else "000"
        val parts = main.split(":")
        val h: String
        val m: String
        val s: String
        when (parts.size) {
            3 -> { h = parts[0]; m = parts[1]; s = parts[2] }
            2 -> { h = "0"; m = parts[0]; s = parts[1] }
            else -> { h = "0"; m = "0"; s = parts.getOrNull(0) ?: "0" }
        }
        val msPad = (ms + "000").substring(0, 3)
        return "${pad(h)}:${pad(m)}:${pad(s)},$msPad"
    }

    private fun pad(v: String): String = if (v.length < 2) "0$v" else v

    override fun toFile(tto: TimedTextObject): Array<String> {
        if (!tto.built) return emptyArray()
        val file = ArrayList<String>(5 * tto.captions.size + 1)
        file.add("WEBVTT")
        file.add("")
        tto.captions.values.forEach { current ->
            file.add(toVttTime(current.start) + " --> " + toVttTime(current.end))
            current.content.split("<br />").filter { it.isNotEmpty() }.forEach { file.add(it) }
            file.add("")
        }
        return file.toTypedArray()
    }

    private fun toVttTime(time: Time): String =
        time.getTime("hh:mm:ss,ms").replace(",", ".")
}
