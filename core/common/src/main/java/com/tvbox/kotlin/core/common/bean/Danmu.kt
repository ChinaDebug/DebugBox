package com.tvbox.kotlin.core.common.bean

import java.util.regex.Pattern

/**
 * 弹幕数据模型。
 */
class Danmu {

    private var data: List<Data>? = null

    /** 单条弹幕 */
    class Data {
        var param: String = ""
        var text: String = ""
    }

    fun getData(): List<Data> = data ?: emptyList()

    companion object {

        /** B 站弹幕条目：<d p="time,mode,size,color">text</d> */
        private val ITEM_PATTERN = Pattern.compile("<d p=\"(.*?)\">(.*?)</d>", Pattern.DOTALL)

        private val ENTITY_PATTERN = Pattern.compile("&#(x?[0-9a-fA-F]+);")

        /** 从 XML 轻量解析弹幕 */
        fun fromXml(str: String?): Danmu {
            if (str.isNullOrEmpty()) return Danmu()
            // 去除 UTF-8 BOM 与首部空白：部分源的 XML 声明头前带 BOM/换行/空格，
            // 不清理会导致下方 <?xmlversion= 修复判定失效（BOM 优先移除，trimStart 兜底）
            var src = str.removePrefix("\uFEFF").trimStart()
            if (src.startsWith("<?xmlversion=")) {
                src = "<?xml version=" + src.substring(12)
            }
            // 数字实体（含 emoji 的 &#128514; 形式）解码为 Unicode 字符，
            // 避免 emoji/特殊字符被过滤成空格导致弹幕表情全部丢失
            val cleaned = decodeNumericEntities(src)
            val danmu = Danmu()
            val list = ArrayList<Data>()
            val m = ITEM_PATTERN.matcher(cleaned)
            while (m.find()) {
                val d = Data()
                d.param = m.group(1) ?: ""
                d.text = m.group(2) ?: ""
                list.add(d)
            }
            danmu.data = list
            return danmu
        }

        /** 解码 XML 数字实体（十进制或十六进制）为 Unicode 字符；避免非法码。
         *  支持 UTF-16 代理对（如 B 站源将 emoji 编码为 &#55357;&#56374; 两个代理单元），
         *  相邻的高/低代理实体组合为完整 emoji，孤立代理保留原样。 */
        fun decodeNumericEntities(src: String): String {
            val matcher = ENTITY_PATTERN.matcher(src)
            val sb = StringBuilder()
            var last = 0
            while (matcher.find()) {
                sb.append(src, last, matcher.start())
                val code = parseEntityCode(matcher.group(1))
                when {
                    code == null -> sb.append(matcher.group(0))
                    // 高代理单元：与紧跟的低代理实体组合成完整码点
                    code in 0xD800..0xDBFF -> {
                        val next = ENTITY_PATTERN.matcher(src).apply { region(matcher.end(), src.length) }
                        if (next.find()) {
                            val low = parseEntityCode(next.group(1))
                            if (low != null && low in 0xDC00..0xDFFF) {
                                val combined = ((code - 0xD800) shl 10) + (low - 0xDC00) + 0x10000
                                sb.append(String(Character.toChars(combined)))
                                // 跳过已组合的低代理实体
                                matcher.region(next.end(), src.length)
                                last = next.end()
                                continue
                            }
                        }
                        // 孤立高代理无法渲染，丢弃
                    }
                    // 控制字符（除换行/回车/制表），避免产生非法字符
                    code in 0x09..0x0D || code in 0x20..0xD7FF || code in 0xE000..0x10FFFF ->
                        sb.append(String(Character.toChars(code)))
                    else -> sb.append(matcher.group(0))
                }
                last = matcher.end()
            }
            sb.append(src, last, src.length)
            return sb.toString()
        }

        /** 解析实体数字（支持十进制与 x/0x 十六进制），非法或空返回 null */
        private fun parseEntityCode(raw: String?): Int? {
            return try {
                if (raw != null && raw.startsWith("x", ignoreCase = true)) {
                    raw.substring(1).toInt(16)
                } else {
                    raw?.toInt()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
