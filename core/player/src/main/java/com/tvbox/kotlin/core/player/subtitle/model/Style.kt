package com.tvbox.kotlin.core.player.subtitle.model

/**
 * 字幕样式（ASS/SSA）。颜色以 8 位 RGBA 字符串存储。
 */
class Style(var iD: String) {

    constructor(iD: String, style: Style) : this(iD) {
        font = style.font
        fontSize = style.fontSize
        color = style.color
        backgroundColor = style.backgroundColor
        textAlign = style.textAlign
        italic = style.italic
        underline = style.underline
        bold = style.bold
    }

    var font: String? = null
    var fontSize: String? = null
    /** 8 位 RGBA 颜色 */
    var color: String = "ffffffff"
    var backgroundColor: String = "000000ff"
    var textAlign: String = ""
    var italic: Boolean = false
    var bold: Boolean = false
    var underline: Boolean = false

    companion object {
        private var styleCounter = 0

        fun defaultID(): String = "default${styleCounter++}"

        /**
         * 按 format 将 value 转换为 8 位 RGBA 颜色字符串。
         * 支持 name、&HBBGGRR、&HAABBGGRR、decimalCodedBBGGRR、decimalCodedAABBGGRR。
         */
        fun getRGBValue(format: String, value: String): String? {
            return when (format.lowercase()) {
                "name" -> when (value) {
                    "transparent" -> "00000000"
                    "black" -> "000000ff"
                    "silver" -> "c0c0c0ff"
                    "gray" -> "808080ff"
                    "white" -> "ffffffff"
                    "maroon" -> "800000ff"
                    "red" -> "ff0000ff"
                    "purple" -> "800080ff"
                    "fuchsia", "magenta" -> "ff00ffff"
                    "green" -> "008000ff"
                    "lime" -> "00ff00ff"
                    "olive" -> "808000ff"
                    "yellow" -> "ffff00ff"
                    "navy" -> "000080ff"
                    "blue" -> "0000ffff"
                    "teal" -> "008080ff"
                    "aqua", "cyan" -> "00ffffff"
                    else -> null
                }
                "&hbbggrr" -> {
                    // SSA: &HBBGGRR
                    StringBuilder().append(value.substring(6))
                        .append(value[4]).append(value[2]).append("ff").toString()
                }
                "&haabbggrr" -> {
                    // ASS: &HAABBGGRR
                    StringBuilder().append(value.substring(8))
                        .append(value[6]).append(value[4]).append(value[2]).toString()
                }
                "decimalcodedbbggrr" -> {
                    var c = Integer.toHexString(value.toInt())
                    while (c.length < 6) c = "0$c"
                    c.substring(4) + c.substring(2, 4) + c.substring(0, 2) + "ff"
                }
                "decimalcodedaabbggrr" -> {
                    var c = java.lang.Long.toHexString(value.toLong())
                    while (c.length < 8) c = "0$c"
                    c.substring(6) + c.substring(4, 6) + c.substring(2, 4) + c.substring(0, 2)
                }
                else -> null
            }
        }
    }
}
