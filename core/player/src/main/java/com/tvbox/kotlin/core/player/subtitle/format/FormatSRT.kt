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
 * SRT 字幕格式解析与序列化。
 */
class FormatSRT : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, input: InputStream): TimedTextObject {
        val tto = TimedTextObject()
        tto.fileName = fileName
        var caption = Subtitle()
        var captionNumber = 1
        val br = BufferedReader(InputStreamReader(input))
        var line: String? = br.readLine()
        var lineCounter = 0
        try {
            while (line != null) {
                line = line.trim()
                lineCounter++
                if (line.isEmpty()) {
                    line = br.readLine()
                    continue
                }
                var allGood = false
                // 期望递增序号
                try {
                    val num = line.toInt()
                    if (num == captionNumber) {
                        captionNumber++
                        allGood = true
                    } else {
                        tto.warnings += "$captionNumber expected at line $lineCounter\n skipping to next line\n\n"
                    }
                } catch (e: Exception) {
                    tto.warnings += "$captionNumber expected at line $lineCounter\n skipping to next line\n\n"
                }
                if (allGood) {
                    // 时间行
                    lineCounter++
                    line = br.readLine()?.trim()
                    if (line == null || line.length < 12) {
                        tto.warnings += "incorrect time format at line $lineCounter"
                        allGood = false
                    } else {
                        try {
                            val start = line.substring(0, 12)
                            val end = line.substring(line.length - 12)
                            caption.start = Time("hh:mm:ss,ms", start)
                            caption.end = Time("hh:mm:ss,ms", end)
                        } catch (e: Exception) {
                            tto.warnings += "incorrect time format at line $lineCounter"
                            allGood = false
                        }
                    }
                }
                if (allGood) {
                    // 文本行
                    lineCounter++
                    line = br.readLine()?.trim()
                    val text = StringBuilder()
                    while (line != null && line.isNotEmpty()) {
                        text.append(line).append("<br />")
                        line = br.readLine()?.trim()
                        lineCounter++
                    }
                    caption.content = text.toString()
                    var key = caption.start.mseconds
                    while (tto.captions.containsKey(key)) key++
                    tto.captions.put(key, caption)
                }
                // 跳过剩余空行
                while (line != null && line.isNotEmpty()) {
                    line = br.readLine()?.trim()
                    lineCounter++
                }
                caption = Subtitle()
                line = br.readLine()
            }
        } catch (e: NullPointerException) {
            tto.warnings += "unexpected end of file, maybe last caption is not complete.\n\n"
        } finally {
            input.runCatching { close() }
        }
        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String> {
        if (!tto.built) return emptyArray()
        val file = ArrayList<String>(5 * tto.captions.size)
        var captionNumber = 1
        tto.captions.values.forEach { current ->
            file.add(captionNumber++.toString())
            if (tto.offset != 0) {
                current.start.mseconds += tto.offset
                current.end.mseconds += tto.offset
            }
            file.add(current.start.getTime("hh:mm:ss,ms") + " --> " + current.end.getTime("hh:mm:ss,ms"))
            if (tto.offset != 0) {
                current.start.mseconds -= tto.offset
                current.end.mseconds -= tto.offset
            }
            cleanTextForSRT(current).forEach { file.add(it) }
            file.add("")
        }
        return file.toTypedArray()
    }

    private fun cleanTextForSRT(current: Subtitle): List<String> {
        return current.content.split("<br />")
            .map { it.replace(Regex("\\<.*?\\>"), "") }
    }
}
