package com.tvbox.kotlin.core.player.subtitle.format

import com.tvbox.kotlin.core.player.subtitle.exception.FatalParsingException
import com.tvbox.kotlin.core.player.subtitle.model.Style
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle
import com.tvbox.kotlin.core.player.subtitle.model.Time
import com.tvbox.kotlin.core.player.subtitle.model.TimedTextObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * ASS/SSA 字幕格式解析与序列化。
 */
class FormatASS : TimedTextFileFormat {

    @Throws(IOException::class, FatalParsingException::class)
    override fun parseFile(fileName: String, input: InputStream): TimedTextObject {
        val tto = TimedTextObject()
        tto.fileName = fileName
        var caption: Subtitle
        var timer = 100f
        var isASS = false
        var styleFormat: Array<String>? = null
        var dialogueFormat: Array<String>? = null
        val br = BufferedReader(InputStreamReader(input))
        var line: String? = br.readLine()
        try {
            while (line != null) {
                line = line.trim()
                if (line.startsWith("[")) {
                    when (line.lowercase()) {
                        "[script info]" -> {
                            line = br.readLine()?.trim()
                            while (line != null && !line.startsWith("[")) {
                                when {
                                    line.startsWith("Title:") -> {
                                        tto.title = line.split(":").getOrElse(1) { "" }.trim()
                                    }
                                    line.startsWith("Original Script:") -> {
                                        tto.author = line.split(":").getOrElse(1) { "" }.trim()
                                    }
                                    line.startsWith("Script Type:") -> {
                                        val v = line.split(":").getOrElse(1) { "" }.trim()
                                        if (v.equals("v4.00+", ignoreCase = true)) isASS = true
                                        else if (!v.equals("v4.00", ignoreCase = true)) {
                                            tto.warnings += "Script version is older than 4.00, it may produce parsing errors."
                                        }
                                    }
                                    line.startsWith("Timer:") -> {
                                        timer = line.split(":").getOrElse(1) { "100" }
                                            .trim().replace(',', '.').toFloatOrNull() ?: 100f
                                    }
                                }
                                line = br.readLine()?.trim()
                            }
                        }
                        "[v4 styles]", "[v4 styles+]", "[v4+ styles]" -> {
                            if (line.contains("+") && !isASS) {
                                isASS = true
                                tto.warnings += "ScriptType should be set to v4:00+ in the [Script Info] section.\n\n"
                            }
                            line = br.readLine()
                            // 定位 Format 行
                            while (line != null && !line.startsWith("Format:")) {
                                line = br.readLine()
                            }
                            if (line != null) {
                                styleFormat = line.split(":").getOrElse(1) { "" }.trim().split(",").toTypedArray()
                            }
                            line = br.readLine()
                            // 跳到首个 Style 行
                            while (line != null && !line.startsWith("Style:")) {
                                line = br.readLine()
                            }
                            if (line != null) {
                                val style = parseStyleForASS(
                                    line.split(":").getOrElse(1) { "" }.trim().split(",").toTypedArray(),
                                    styleFormat ?: emptyArray(),
                                    isASS,
                                    tto
                                )
                                tto.styling[style.iD] = style
                            }
                        }
                        "[events]" -> {
                            line = br.readLine()
                            tto.warnings += "Only dialogue events are considered, all other events are ignored.\n\n"
                            while (line != null && !line.startsWith("Format:")) {
                                line = br.readLine()
                            }
                            if (line != null) {
                                dialogueFormat = line.split(":").getOrElse(1) { "" }.trim().split(",").toTypedArray()
                            }
                            line = br.readLine()
                            while (line != null && !line.startsWith("[")) {
                                if (line.startsWith("Dialogue:")) {
                                    caption = parseDialogueForASS(
                                        line.split(":", limit = 2).getOrElse(1) { "" }.trim().split(",", limit = 10).toTypedArray(),
                                        dialogueFormat ?: emptyArray(),
                                        timer,
                                        tto
                                    )
                                    var key = caption.start.mseconds
                                    while (tto.captions.containsKey(key)) key++
                                    tto.captions.put(key, caption)
                                }
                                line = br.readLine()
                            }
                        }
                        "[fonts]", "[graphics]" -> {
                            tto.warnings += "The section $line is not supported for conversion, all information there will be lost.\n\n"
                        }
                        else -> {
                            tto.warnings += "Unrecognized section: $line all information there is ignored."
                        }
                    }
                }
                line = br.readLine()
            }
            tto.cleanUnusedStyles()
        } catch (e: Exception) {
            tto.warnings += "unexpected end of file, maybe last caption is not complete.\n\n"
        } finally {
            input.runCatching { close() }
        }
        tto.built = true
        return tto
    }

    override fun toFile(tto: TimedTextObject): Array<String> {
        if (!tto.built) return emptyArray()
        val file = ArrayList<String>(30 + tto.styling.size + tto.captions.size)
        file.add("[Script Info]")
        file.add("Title: ${if (tto.title.isNullOrEmpty()) tto.fileName else tto.title}")
        file.add("Original Script: ${if (tto.author.isNullOrEmpty()) "Unknown" else tto.author}")
        if (tto.copyrigth.isNotEmpty()) file.add("; ${tto.copyrigth}")
        if (tto.description.isNotEmpty()) file.add("; ${tto.description}")
        file.add("; Converted by the Online Subtitle Converter developed by J. David Requejo")
        file.add(if (tto.useASSInsteadOfSSA) "Script Type: V4.00+" else "Script Type: V4.00")
        file.add("Collisions: Normal")
        file.add("Timer: 100,0000")
        if (tto.useASSInsteadOfSSA) file.add("WrapStyle: 1")
        file.add("")
        file.add(if (tto.useASSInsteadOfSSA) "[V4+ Styles]" else "[V4 Styles]")
        file.add(
            if (tto.useASSInsteadOfSSA) {
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"
            } else {
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding"
            }
        )
        tto.styling.values.forEach { current ->
            val sb = StringBuilder("Style: ")
            sb.append(current.iD).append(',')
                .append(current.font ?: "").append(',')
                .append(current.fontSize ?: "").append(',')
                .append(getColorsForASS(tto.useASSInsteadOfSSA, current))
                .append(getOptionsForASS(tto.useASSInsteadOfSSA, current))
                .append("1,2,2,")
                .append(getAlignForASS(tto.useASSInsteadOfSSA, current.textAlign))
                .append(",0,0,0,")
            if (!tto.useASSInsteadOfSSA) sb.append("0,")
            sb.append("0")
            file.add(sb.toString())
        }
        file.add("")
        file.add("[Events]")
        file.add(
            if (tto.useASSInsteadOfSSA) {
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
            } else {
                "Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
            }
        )
        tto.captions.values.forEach { current ->
            val sb = StringBuilder("Dialogue: 0,")
            if (tto.offset != 0) {
                current.start.mseconds += tto.offset
                current.end.mseconds += tto.offset
            }
            sb.append(current.start.getTime("h:mm:ss.cs")).append(',')
                .append(current.end.getTime("h:mm:ss.cs")).append(',')
            if (tto.offset != 0) {
                current.start.mseconds -= tto.offset
                current.end.mseconds -= tto.offset
            }
            sb.append(current.style?.iD ?: "Default")
            sb.append(",,0000,0000,0000,,")
            sb.append(
                current.content.replace("<br />", "\\N")
                    .replace(Regex("\\<.*?\\>"), "")
            )
            file.add(sb.toString())
        }
        file.add("")
        return file.toTypedArray()
    }

    private fun parseStyleForASS(
        line: Array<String>,
        styleFormat: Array<String>,
        isASS: Boolean,
        tto: TimedTextObject
    ): Style {
        val newStyle = Style(Style.defaultID())
        if (line.size != styleFormat.size) {
            tto.warnings += "incorrectly formatted style line\n\n"
            return newStyle
        }
        for (i in styleFormat.indices) {
            val key = styleFormat[i].trim().lowercase()
            val value = line.getOrNull(i)?.trim() ?: continue
            when (key) {
                "name" -> newStyle.iD = value
                "fontname" -> newStyle.font = value
                "fontsize" -> newStyle.fontSize = value
                "primarycolour" -> newStyle.color = convertColor(value, isASS, tto) ?: newStyle.color
                "backcolour" -> newStyle.backgroundColor = convertColor(value, isASS, tto) ?: newStyle.backgroundColor
                "bold" -> newStyle.bold = value.equals("-1") || value.equals("true", true)
                "italic" -> newStyle.italic = value.equals("-1") || value.equals("true", true)
                "underline" -> newStyle.underline = value.equals("-1") || value.equals("true", true)
                "alignment" -> {
                    val placement = value.toIntOrNull() ?: continue
                    newStyle.textAlign = mapAlignment(placement, isASS)
                }
            }
        }
        return newStyle
    }

    private fun convertColor(value: String, isASS: Boolean, tto: TimedTextObject): String? {
        return if (value.startsWith("&H")) {
            val fmt = if (isASS) "&HAABBGGRR" else "&HBBGGRR"
            Style.getRGBValue(fmt, value)
        } else {
            val fmt = if (isASS) "decimalCodedAABBGGRR" else "decimalCodedBBGGRR"
            runCatching { Style.getRGBValue(fmt, value) }.getOrElse {
                tto.warnings += "invalid color: $value\n\n"
                null
            }
        }
    }

    private fun mapAlignment(placement: Int, isASS: Boolean): String {
        if (isASS) {
            return when (placement) {
                1 -> "bottom-left"
                2 -> "bottom-center"
                3 -> "bottom-right"
                4 -> "mid-left"
                5 -> "mid-center"
                6 -> "mid-right"
                7 -> "top-left"
                8 -> "top-center"
                9 -> "top-right"
                else -> "bottom-center"
            }
        }
        return when (placement) {
            9 -> "bottom-left"
            10 -> "bottom-center"
            11 -> "bottom-right"
            1 -> "mid-left"
            2 -> "mid-center"
            3 -> "mid-right"
            5 -> "top-left"
            6 -> "top-center"
            7 -> "top-right"
            else -> "bottom-center"
        }
    }

    private fun parseDialogueForASS(
        line: Array<String>,
        dialogueFormat: Array<String>,
        timer: Float,
        tto: TimedTextObject
    ): Subtitle {
        val newCaption = Subtitle()
        // 第 10 个字段（index 9）起为字幕文本
        newCaption.content = (line.getOrNull(9) ?: "")
            .replace(Regex("\\{.*?\\}"), "")
            .replace("\n", "<br />")
            .replace("\\N", "<br />")
        for (i in dialogueFormat.indices) {
            val key = dialogueFormat[i].trim().lowercase()
            val value = line.getOrNull(i)?.trim() ?: continue
            when (key) {
                "style" -> tto.styling[value]?.let { newCaption.style = it }
                "start" -> newCaption.start = Time("h:mm:ss.cs", value)
                "end" -> newCaption.end = Time("h:mm:ss.cs", value)
            }
        }
        if (timer != 100f) {
            newCaption.start.mseconds = (newCaption.start.mseconds / (timer / 100f)).toInt()
            newCaption.end.mseconds = (newCaption.end.mseconds / (timer / 100f)).toInt()
        }
        return newCaption
    }

    private fun getColorsForASS(useASS: Boolean, style: Style): String {
        val color = style.color
        val bg = style.backgroundColor
        return if (color.length >= 6 && bg.length >= 6) {
            if (useASS) {
                Integer.parseInt("00" + color.substring(4, 6) + color.substring(2, 4) + color.substring(0, 2), 16)
                    .toString() + ",16777215,0," +
                    java.lang.Long.parseLong("80" + bg.substring(4, 6) + bg.substring(2, 4) + bg.substring(0, 2), 16)
                    .toString() + ","
            } else {
                java.lang.Long.parseLong(color.substring(4, 6) + color.substring(2, 4) + color.substring(0, 2), 16)
                    .toString() + ",16777215,0," +
                    java.lang.Long.parseLong(bg.substring(4, 6) + bg.substring(2, 4) + bg.substring(0, 2), 16)
                    .toString() + ","
            }
        } else {
            "0,16777215,0,0,"
        }
    }

    private fun getOptionsForASS(useASS: Boolean, style: Style): String {
        val sb = StringBuilder()
        sb.append(if (style.bold) "-1," else "0,")
        sb.append(if (style.italic) "-1," else "0,")
        if (useASS) {
            sb.append(if (style.underline) "-1," else "0,")
            sb.append("0,100,100,0,0,")
        }
        return sb.toString()
    }

    private fun getAlignForASS(useASS: Boolean, align: String): Int {
        if (useASS) {
            return when (align) {
                "bottom-left" -> 1
                "bottom-center" -> 2
                "bottom-right" -> 3
                "mid-left" -> 4
                "mid-center" -> 5
                "mid-right" -> 6
                "top-left" -> 7
                "top-center" -> 8
                "top-right" -> 9
                else -> 2
            }
        }
        return when (align) {
            "bottom-left" -> 9
            "bottom-center" -> 10
            "bottom-right" -> 11
            "mid-left" -> 1
            "mid-center" -> 2
            "mid-right" -> 3
            "top-left" -> 5
            "top-center" -> 6
            "top-right" -> 7
            else -> 10
        }
    }
}
