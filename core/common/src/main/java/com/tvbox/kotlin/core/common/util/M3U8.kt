package com.tvbox.kotlin.core.common.util

import java.math.BigDecimal
import java.util.regex.Pattern

/** M3U8 广告过滤与地址拼接工具 */
object M3U8 {

    private const val TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val TAG_MEDIA_DURATION = "#EXTINF"
    private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
    private const val TAG_KEY = "#EXT-X-KEY"

    private val REGEX_X_DISCONTINUITY = Pattern.compile(
        "#EXT-X-DISCONTINUITY[\\s\\S]*?(?=#EXT-X-DISCONTINUITY|\$)"
    )
    private val REGEX_MEDIA_DURATION = Pattern.compile("$TAG_MEDIA_DURATION:([\\d\\.]+)\\b")
    private val REGEX_URI = Pattern.compile("URI=\"(.+?)\"")

    @JvmField
    var currentAdCount: Int = 0

    /** 广告正则提供者，由上层模块（如 :core:spider）注入 */
    @Volatile
    @JvmField
    var adRegexProvider: ((tsUrlPre: String) -> List<String>?)? = null

    /** 净化 m3u8 内容，移除广告片段并补全相对地址 */
    fun purify(tsUrlPre: String, m3u8content: String?): String? {
        currentAdCount = 0
        if (m3u8content.isNullOrEmpty()) return null
        if (!m3u8content.startsWith("#EXTM3U")) return null
        removeMinorityUrl(tsUrlPre, m3u8content)?.let { return it }
        return get(tsUrlPre, m3u8content)
    }

    private fun maxPercent(preUrlMap: Map<String, Int>): Double {
        var maxTimes = 0
        var totalTimes = 0
        for (value in preUrlMap.values) {
            if (value > maxTimes) maxTimes = value
            totalTimes += value
        }
        return if (totalTimes == 0) 0.0 else maxTimes * 1.0 / (totalTimes * 1.0)
    }

    private const val timesNoAd = 15

    private fun removeMinorityUrl(tsUrlPre: String, m3u8content: String): String? {
        var linesplit = "\n"
        if (m3u8content.contains("\r\n")) linesplit = "\r\n"
        val lines = m3u8content.split(linesplit).toTypedArray()

        val preUrlMap = LinkedHashMap<String, Int>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val ilast = line.lastIndexOf('.')
            if (ilast <= 4) continue
            val preUrl = line.substring(0, ilast - 4)
            preUrlMap[preUrl] = (preUrlMap[preUrl] ?: 0) + 1
        }
        if (preUrlMap.size <= 1) return null

        var domainFiltering = false
        if (maxPercent(preUrlMap) < 0.8) {
            preUrlMap.clear()
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                if (!line.startsWith("http://") && !line.startsWith("https://")) return null
                val ifirst = line.indexOf('/', 9)
                if (ifirst <= 0) continue
                val preUrl = line.substring(0, ifirst)
                preUrlMap[preUrl] = (preUrlMap[preUrl] ?: 0) + 1
            }
            if (preUrlMap.size <= 1) return null
            if (maxPercent(preUrlMap) < 0.8) return null
            var allDomainsExceedThreshold = true
            for (count in preUrlMap.values) {
                if (count <= timesNoAd) {
                    allDomainsExceedThreshold = false
                    break
                }
            }
            if (allDomainsExceedThreshold) return null
            domainFiltering = true
        }

        var maxTimes = 0
        var maxTimesPreUrl = ""
        for ((key, value) in preUrlMap) {
            if (value > maxTimes) {
                maxTimesPreUrl = key
                maxTimes = value
            }
        }
        if (maxTimes == 0) return null

        var dealedExtXKey = false
        for (i in lines.indices) {
            if (!dealedExtXKey && lines[i].startsWith("#EXT-X-KEY")) {
                var keyUrl = ""
                var start = lines[i].indexOf("URI=\"")
                if (start != -1) {
                    start += "URI=\"".length
                    val end = lines[i].indexOf("\"", start)
                    if (end != -1) {
                        keyUrl = lines[i].substring(start, end)
                    }
                    if (!keyUrl.startsWith("http://") && !keyUrl.startsWith("https://")) {
                        val newKeyUrl = if (keyUrl.isNotEmpty() && keyUrl[0] == '/') {
                            val ifirst = tsUrlPre.indexOf('/', 9)
                            tsUrlPre.substring(0, ifirst) + keyUrl
                        } else {
                            tsUrlPre + keyUrl
                        }
                        lines[i] = lines[i].replace("URI=\"$keyUrl\"", "URI=\"$newKeyUrl\"")
                    }
                    dealedExtXKey = true
                }
            }
            if (lines[i].isEmpty() || lines[i][0] == '#') continue

            if (!domainFiltering) {
                if (lines[i].startsWith(maxTimesPreUrl)) {
                    if (!lines[i].startsWith("http://") && !lines[i].startsWith("https://")) {
                        lines[i] = if (lines[i].isNotEmpty() && lines[i][0] == '/') {
                            val ifirst = tsUrlPre.indexOf('/', 9)
                            tsUrlPre.substring(0, ifirst) + lines[i]
                        } else {
                            tsUrlPre + lines[i]
                        }
                    }
                } else {
                    if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') lines[i - 1] = ""
                    lines[i] = ""
                    currentAdCount += 1
                }
            } else {
                var absoluteUrl = lines[i]
                if (!absoluteUrl.startsWith("http://") && !absoluteUrl.startsWith("https://")) {
                    absoluteUrl = if (absoluteUrl.isNotEmpty() && absoluteUrl[0] == '/') {
                        val ifirst = tsUrlPre.indexOf('/', 9)
                        tsUrlPre.substring(0, ifirst) + absoluteUrl
                    } else {
                        tsUrlPre + absoluteUrl
                    }
                }
                val ifirst = absoluteUrl.indexOf('/', 9)
                val domain = if (ifirst > 0) absoluteUrl.substring(0, ifirst) else absoluteUrl
                val cnt = preUrlMap[domain]
                if (domain == maxTimesPreUrl || (cnt != null && cnt > timesNoAd)) {
                    lines[i] = absoluteUrl
                } else {
                    if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') lines[i - 1] = ""
                    lines[i] = ""
                    currentAdCount += 1
                }
            }
        }
        return lines.joinToString(linesplit)
    }

    private fun get(tsUrlPre: String, m3u8Content: String): String? {
        val content = m3u8Content.replace("\r\n", "\n")
        val sb = StringBuilder()
        for (line in content.split("\n")) {
            sb.append(if (shouldResolve(line)) resolve(tsUrlPre, line) else line).append("\n")
        }
        val ads = getRegex(tsUrlPre)
        if (ads.isNullOrEmpty()) return null
        return clean(sb.toString(), ads)
    }

    /** 返回指定 host 的广告正则；由上层注入的 adRegexProvider 提供 */
    private fun getRegex(tsUrlPre: String): List<String>? = adRegexProvider?.invoke(tsUrlPre)

    private fun clean(line: String, ads: List<String>): String {
        var scan = false
        var result = line
        for (ad in ads) {
            if (ad.contains(TAG_DISCONTINUITY) || ad.contains(TAG_MEDIA_DURATION)) {
                result = scanAd(result, ad)
            } else if (isDouble(ad)) {
                scan = true
            }
        }
        return if (scan) scan(result, ads) else result
    }

    private fun scanAd(line: String, tagAd: String): String {
        val m1 = RegexUtils.getPattern(tagAd).matcher(line)
        val needRemoveAd = ArrayList<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var tCount = 0
            while (m2.find()) tCount += 1
            needRemoveAd.add(groupCleaned)
            currentAdCount += tCount
        }
        var result = line
        for (rem in needRemoveAd) result = result.replace(rem, "")
        return result
    }

    private fun scan(line: String, ads: List<String>): String {
        val m1 = REGEX_X_DISCONTINUITY.matcher(line)
        val needRemoveAd = ArrayList<String>()
        while (m1.find()) {
            val group = m1.group()
            val groupCleaned = group.replace(TAG_ENDLIST, "")
            val m2 = REGEX_MEDIA_DURATION.matcher(group)
            var ft = BigDecimal.ZERO
            var lt = BigDecimal.ZERO
            var t = BigDecimal.ZERO
            var tCount = 0
            while (m2.find()) {
                if (ft == BigDecimal.ZERO) ft = BigDecimal(m2.group(1))
                lt = BigDecimal(m2.group(1))
                t = t.add(lt)
                tCount += 1
            }
            val ftStr = ft.toString()
            val ltStr = lt.toString()
            val tStr = t.toString()
            for (ad in ads) {
                if (ad.startsWith("-")) {
                    val adClean = ad.substring(1)
                    if (ltStr.startsWith(adClean)) {
                        needRemoveAd.add(groupCleaned)
                        currentAdCount += tCount
                        break
                    }
                } else {
                    if (ftStr.startsWith(ad) || tStr.startsWith(ad)) {
                        needRemoveAd.add(groupCleaned)
                        currentAdCount += tCount
                        break
                    }
                }
            }
        }
        var result = line
        for (rem in needRemoveAd) result = result.replace(rem, "")
        return result
    }

    private fun isDouble(ad: String): Boolean = try {
        ad.toDouble() != 0.0
    } catch (_: Exception) {
        false
    }

    private fun shouldResolve(line: String): Boolean =
        (!line.startsWith("#") && !line.startsWith("http")) || line.startsWith(TAG_KEY)

    private fun resolve(base: String, line: String): String {
        if (line.startsWith(TAG_KEY)) {
            val matcher = REGEX_URI.matcher(line)
            val value = if (matcher.find()) matcher.group(1) else null
            return if (value == null) line else line.replace(value, resolveUrl(base, value))
        }
        return resolveUrl(base, line)
    }

    /** 相对 URL 拼接，替代 androidx.media3.common.util.UriUtil.resolve */
    private fun resolveUrl(base: String, reference: String): String {
        return try {
            if (reference.startsWith("http://") || reference.startsWith("https://")) {
                return reference
            }
            val baseUri = java.net.URI(base)
            baseUri.resolve(reference).toString()
        } catch (_: Exception) {
            if (reference.isNotEmpty() && reference[0] == '/') {
                val idx = base.indexOf('/', 9)
                if (idx > 0) base.substring(0, idx) + reference else reference
            } else {
                base + reference
            }
        }
    }
}
