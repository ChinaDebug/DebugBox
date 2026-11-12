package com.tvbox.kotlin.app.js

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URL
import java.util.regex.Pattern

/**
 * HTML 解析工具，供 JS 爬虫桥接（pdfh/pdfa/pd/pdfla）使用。
 * 支持海阔式解析表达式（&& 组合、:eq 索引、-- 排除）与自动 urlJoin。
 */
object HtmlParser {

    @Volatile
    private var pdfhHtml = ""
    @Volatile
    private var pdfaHtml = ""
    @Volatile
    private var pdfhDoc: Document? = null
    @Volatile
    private var pdfaDoc: Document? = null

    private val URL_CSS = Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE or Pattern.DOTALL)
    private val NOADD_INDEX = Pattern.compile(":eq|:lt|:gt|:first|:last|^body$|^#")
    private val URLJOIN_ATTR =
        Pattern.compile("(url|src|href|-original|-src|-play|-url|style)$", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)
    private val SPECIAL_URL = Pattern.compile("^(ftp|magnet|thunder|ws):", Pattern.MULTILINE or Pattern.CASE_INSENSITIVE)

    /** 合并父地址与子地址，返回绝对地址 */
    fun joinUrl(parent: String?, child: String?): String {
        if (parent.isNullOrEmpty()) return child ?: ""
        return try {
            URL(URL(parent), child ?: "").toExternalForm()
        } catch (e: Exception) {
            child ?: ""
        }
    }

    private class ParseInfo {
        var rule: String = ""
        var index: Int = 0
        var excludes: MutableList<String>? = null
    }

    private fun getParseInfo(nparse: String): ParseInfo {
        val info = ParseInfo()
        info.rule = nparse
        if (nparse.contains(":eq")) {
            var rule = nparse.split(":")[0]
            var pos = nparse.split(":")[1]
            if (rule.contains("--")) {
                val rules = rule.split("--")
                info.excludes = rules.drop(1).toMutableList()
                rule = rules[0]
            } else if (pos.contains("--")) {
                val rules = pos.split("--")
                info.excludes = rules.drop(1).toMutableList()
                pos = rules[0]
            }
            info.rule = rule
            info.index = pos.replace("eq(", "").replace(")", "").toIntOrNull() ?: 0
        } else {
            if (nparse.contains("--")) {
                val rules = nparse.split("--")
                info.excludes = rules.drop(1).toMutableList()
                info.rule = rules[0]
            }
        }
        return info
    }

    private fun isIndex(str: String): Boolean {
        if (str.isEmpty()) return false
        for (mark in listOf(":eq", ":lt", ":gt", ":first", ":last", "body", "#")) {
            if (str.contains(mark)) {
                if (mark == "body" || mark == "#") return str.startsWith(mark)
                return true
            }
        }
        return false
    }

    /** 海阔解析表达式转原生表达式：自动补 :eq 下标索引 */
    private fun parseHikerToJq(parse: String, first: Boolean): String {
        if (parse.contains("&&")) {
            val parses = parse.split("&&")
            val newParses = ArrayList<String>()
            for (i in parses.indices) {
                val pss = parses[i].split(" ")
                val ps = pss[pss.size - 1]
                if (!NOADD_INDEX.matcher(ps).find()) {
                    if (!first && i >= parses.size - 1) {
                        newParses.add(parses[i])
                    } else {
                        newParses.add(parses[i] + ":eq(0)")
                    }
                } else {
                    newParses.add(parses[i])
                }
            }
            return newParses.joinToString(" ")
        }
        val pss = parse.split(" ")
        val ps = pss[pss.size - 1]
        return if (!NOADD_INDEX.matcher(ps).find() && first) "$parse:eq(0)" else parse
    }

    /** 按单规则解析：:eq 索引、-- 排除子元素 */
    private fun parseOneRule(doc: Document, nparse: String, ret: Elements): Elements {
        val info = getParseInfo(nparse)
        var result = if (ret.isEmpty()) doc.select(info.rule) else ret.select(info.rule)
        if (nparse.contains(":eq")) {
            result = if (info.index < 0) result.eq(result.size + info.index) else result.eq(info.index)
        }
        info.excludes?.let { excludes ->
            if (result.isNotEmpty()) {
                result = result.clone()
                for (rule in excludes) {
                    result.select(rule).remove()
                }
            }
        }
        return result
    }

    /** 解析单条规则并取属性/文本，addUrl 非空时自动拼接相对地址 */
    fun parseDomForUrl(html: String, ruleIn: String, addUrl: String): String {
        if (pdfhHtml != html) {
            pdfhHtml = html
            pdfhDoc = Jsoup.parse(html)
        }
        val doc = pdfhDoc ?: return ""
        var rule = ruleIn
        if (rule == "body&&Text" || rule == "Text") return doc.text()
        if (rule == "body&&Html" || rule == "Html") return doc.html()
        var option = ""
        if (rule.contains("&&")) {
            val rs = rule.split("&&")
            option = rs[rs.size - 1]
            rule = rs.dropLast(1).joinToString("&&")
        }
        rule = parseHikerToJq(rule, true)
        var ret = Elements()
        for (nparse in rule.split(" ")) {
            ret = parseOneRule(doc, nparse, ret)
            if (ret.isEmpty()) return ""
        }
        val result: String
        if (option.isNotEmpty()) {
            result = when (option) {
                "Text" -> ret.text()
                "Html" -> ret.html()
                else -> {
                    var value = ret.attr(option)
                    if (option.lowercase().contains("style") && value.contains("url(")) {
                        URL_CSS.matcher(value).takeIf { it.find() }?.let { value = it.group(1) ?: "" }
                        if (value.isNotEmpty()) value = value.replace(Regex("^['|\"](.*)['|\"]$"), "$1")
                    }
                    if (value.isNotEmpty() && addUrl.isNotEmpty()) {
                        val needJoin = URLJOIN_ATTR.matcher(option).find()
                        val special = SPECIAL_URL.matcher(value).find()
                        if (needJoin && !special) {
                            value = if (value.contains("http")) {
                                value.substring(value.indexOf("http"))
                            } else {
                                joinUrl(addUrl, value)
                            }
                        }
                    }
                    value
                }
            }
        } else {
            result = ret.outerHtml()
        }
        return result
    }

    /** 按规则解析出元素列表，返回各元素 outerHtml */
    fun parseDomForArray(html: String, ruleIn: String): List<String> {
        if (pdfaHtml != html) {
            pdfaHtml = html
            pdfaDoc = Jsoup.parse(html)
        }
        val doc = pdfaDoc ?: return emptyList()
        val rule = parseHikerToJq(ruleIn, false)
        var ret = Elements()
        for (nparse in rule.split(" ")) {
            ret = parseOneRule(doc, nparse, ret)
            if (ret.isEmpty()) return emptyList()
        }
        return (0 until ret.size).map { ret[it].outerHtml() }
    }

    /** 解析列表：每条取 list_text 与 list_url 两个字段，以 $ 拼接 */
    fun parseDomForList(html: String, p1In: String, listText: String, listUrl: String, addUrl: String): List<String> {
        if (pdfaHtml != html) {
            pdfaHtml = html
            pdfaDoc = Jsoup.parse(html)
        }
        val doc = pdfaDoc ?: return emptyList()
        val p1 = parseHikerToJq(p1In, false)
        var ret = Elements()
        for (nparse in p1.split(" ")) {
            ret = parseOneRule(doc, nparse, ret)
            if (ret.isEmpty()) return emptyList()
        }
        val result = ArrayList<String>()
        for (i in 0 until ret.size) {
            val item = ret[i].outerHtml()
            result.add(
                parseDomForUrl(item, listText, "").trim() + '$' +
                    parseDomForUrl(item, listUrl, addUrl)
            )
        }
        return result
    }
}
