package com.tvbox.kotlin.core.player.subtitle.model

/**
 * 字幕时间点。支持 SRT/ASS/STL 等多种格式字符串解析与回格式化。
 * 内部以毫秒整数存储。
 */
class Time(format: String, value: String) {

    /** 毫秒数 */
    @JvmField
    var mseconds: Int = 0

    init {
        runCatching {
            when (format.lowercase()) {
                "hh:mm:ss,ms" -> {
                    // SRT: 01:02:22,501
                    val h = value.substring(0, 2).toInt()
                    val m = value.substring(3, 5).toInt()
                    val s = value.substring(6, 8).toInt()
                    val ms = value.substring(9, 12).toInt()
                    mseconds = ms + s * 1000 + m * 60000 + h * 3600000
                }
                "h:mm:ss.cs" -> {
                    // ASS/SSA: 1:02:22.51
                    val h = value.substring(0, 1).toInt()
                    val m = value.substring(2, 4).toInt()
                    val s = value.substring(5, 7).toInt()
                    val cs = value.substring(8, 10).toInt()
                    mseconds = cs * 10 + s * 1000 + m * 60000 + h * 3600000
                }
                "h:m:s:f/fps" -> {
                    val args = value.split("/")
                    val fps = args[1].toFloat()
                    val parts = args[0].split(":")
                    val h = parts[0].toInt()
                    val m = parts[1].toInt()
                    val s = parts[2].toInt()
                    val f = parts[3].toInt()
                    mseconds = (f * 1000 / fps).toInt() + s * 1000 + m * 60000 + h * 3600000
                }
            }
        }.onFailure { mseconds = 0 }
    }

    /**
     * 按 format 格式化为字符串。
     * 支持 "hh:mm:ss,ms"、"h:mm:ss.cs"、"hhmmssff/fps"、"h:m:s:f/fps"、"hh:mm:ss:ff/fps"。
     */
    fun getTime(format: String): String {
        val sb = StringBuilder()
        val fmt = format.lowercase()
        when {
            fmt == "hh:mm:ss,ms" -> {
                sb.append(pad(mseconds / 3600000))
                sb.append(':').append(pad((mseconds / 60000) % 60))
                sb.append(':').append(pad((mseconds / 1000) % 60))
                sb.append(',').append(pad3(mseconds % 1000))
            }
            fmt == "h:mm:ss.cs" -> {
                sb.append(pad(mseconds / 3600000))
                sb.append(':').append(pad((mseconds / 60000) % 60))
                sb.append(':').append(pad((mseconds / 1000) % 60))
                sb.append('.').append(pad((mseconds / 10) % 100))
            }
            fmt.startsWith("hhmmssff/") -> {
                val fps = format.split("/")[1].toFloat()
                sb.append(pad(mseconds / 3600000))
                sb.append(pad((mseconds / 60000) % 60))
                sb.append(pad((mseconds / 1000) % 60))
                sb.append(pad((mseconds % 1000) * fps.toInt() / 1000))
            }
            fmt.startsWith("h:m:s:f/") -> {
                val fps = format.split("/")[1].toFloat()
                sb.append(mseconds / 3600000).append(':')
                sb.append((mseconds / 60000) % 60).append(':')
                sb.append((mseconds / 1000) % 60).append(':')
                sb.append((mseconds % 1000) * fps.toInt() / 1000)
            }
            fmt.startsWith("hh:mm:ss:ff/") -> {
                val fps = format.split("/")[1].toFloat()
                sb.append(pad(mseconds / 3600000)).append(':')
                sb.append(pad((mseconds / 60000) % 60)).append(':')
                sb.append(pad((mseconds / 1000) % 60)).append(':')
                sb.append(pad((mseconds % 1000) * fps.toInt() / 1000))
            }
        }
        return sb.toString()
    }

    private fun pad(v: Int): String = if (v < 10) "0$v" else v.toString()
    private fun pad3(v: Int): String = when {
        v < 10 -> "00$v"
        v < 100 -> "0$v"
        else -> v.toString()
    }
}
