package com.tvbox.kotlin.core.spider

import android.net.Uri
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.common.util.RegexUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频嗅探解析规则管理，按 host 维护嗅探规则、过滤规则、广告正则与嗅探脚本，
 * 规则缓存使用线程安全的 ConcurrentHashMap，供 ApiConfig 解析配置 rules 段时写入、播放器嗅探时查询。
 */
object VideoParseRuler {

    private val HOSTS_RULE = ConcurrentHashMap<String, ArrayList<ArrayList<String>>>()
    private val HOSTS_FILTER = ConcurrentHashMap<String, ArrayList<ArrayList<String>>>()
    private val HOSTS_REGEX = ConcurrentHashMap<String, ArrayList<String>>()
    private val HOSTS_SCRIPT = ConcurrentHashMap<String, ArrayList<String>>()

    @JvmStatic
    fun clearRule() {
        HOSTS_RULE.clear()
        HOSTS_FILTER.clear()
        HOSTS_REGEX.clear()
        HOSTS_SCRIPT.clear()
    }

    @JvmStatic
    fun addHostRule(host: String, rule: ArrayList<String>) {
        if (rule.isEmpty()) return
        HOSTS_RULE.getOrPut(host) { ArrayList() }.add(rule)
    }

    @JvmStatic
    fun getHostRules(host: String): ArrayList<ArrayList<String>>? = HOSTS_RULE[host]

    @JvmStatic
    fun addHostFilter(host: String, rule: ArrayList<String>) {
        HOSTS_FILTER.getOrPut(host) { ArrayList() }.add(rule)
    }

    @JvmStatic
    fun getHostFilters(host: String): ArrayList<ArrayList<String>>? = HOSTS_FILTER[host]

    @JvmStatic
    fun addHostRegex(host: String, regex: ArrayList<String>) {
        if (regex.isEmpty()) return
        HOSTS_REGEX.getOrPut(host) { ArrayList() }.addAll(regex)
    }

    @JvmStatic
    fun getHostsRegex(): Map<String, ArrayList<String>> = HOSTS_REGEX

    /** 判断 url 是否需嗅探解析：先按视频格式判断，再按 host 规则匹配，无 host 规则回退通配 * */
    @JvmStatic
    fun checkIsVideoForParse(webUrl: String?, url: String): Boolean {
        try {
            var isVideo = DefaultConfig.isVideoFormat(url)
            if (HOSTS_RULE.isNotEmpty() && !isVideo && webUrl != null) {
                val host = Uri.parse(webUrl).host
                isVideo = if (host != null && getHostRules(host) != null) {
                    checkVideoForOneHostRules(host, url)
                } else {
                    checkVideoForOneHostRules("*", url)
                }
            }
            return isVideo
        } catch (e: Exception) {
            LOG.e(e)
        }
        return false
    }

    private fun checkVideoForOneHostRules(host: String, url: String): Boolean {
        val hostRules = getHostRules(host) ?: return false
        for (ruleGroup in hostRules) {
            if (ruleGroup.isEmpty()) continue
            var checkIsVideo = true
            for (rule in ruleGroup) {
                if (!RegexUtils.getPattern(rule).matcher(url).find()) {
                    checkIsVideo = false
                    break
                }
            }
            if (checkIsVideo) return true
        }
        return false
    }

    /** 判断 url 是否命中 host 过滤规则 */
    @JvmStatic
    fun isFilter(webUrl: String?, url: String): Boolean {
        try {
            if (HOSTS_FILTER.isEmpty() || webUrl == null) return false
            val host = Uri.parse(webUrl).host ?: return false
            if (getHostFilters(host) != null) return checkIsFilterForOneHostRules(host, url)
        } catch (e: Exception) {
            LOG.e(e)
        }
        return false
    }

    private fun checkIsFilterForOneHostRules(host: String, url: String): Boolean {
        val hostFilters = getHostFilters(host) ?: return false
        for (filterGroup in hostFilters) {
            if (filterGroup.isEmpty()) continue
            var checkIsFilter = true
            for (rule in filterGroup) {
                if (!RegexUtils.getPattern(rule).matcher(url).find()) {
                    checkIsFilter = false
                    break
                }
            }
            if (checkIsFilter) return true
        }
        return false
    }

    @JvmStatic
    fun addHostScript(host: String, script: ArrayList<String>) {
        if (script.isEmpty()) return
        HOSTS_SCRIPT.getOrPut(host) { ArrayList() }.addAll(script)
    }

    /** 返回首个匹配 url 的嗅探脚本 */
    @JvmStatic
    fun getHostScript(url: String): String {
        for ((host, list) in HOSTS_SCRIPT) {
            if (url.contains(host) && list.isNotEmpty()) return list[0]
        }
        return ""
    }

    /** 判断 url 是否命中 host 的广告正则 */
    @JvmStatic
    fun isAd(webUrl: String?, url: String?): Boolean {
        try {
            if (HOSTS_REGEX.isEmpty() || webUrl == null || url == null) return false
            val host = Uri.parse(webUrl).host ?: return false
            val regexList = HOSTS_REGEX[host] ?: return false
            for (regex in regexList) {
                if (RegexUtils.getPattern(regex).matcher(url).find()) {
                    return true
                }
            }
        } catch (e: Exception) {
            LOG.e(e)
        }
        return false
    }
}
