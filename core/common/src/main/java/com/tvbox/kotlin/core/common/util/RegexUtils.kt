package com.tvbox.kotlin.core.common.util

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** 正则缓存工具 */
object RegexUtils {

    private val patternCache = ConcurrentHashMap<String, Pattern>()

    @JvmOverloads
    fun getPattern(regex: String, flag: Int = 0): Pattern {
        return patternCache.getOrPut(regex) {
            if (flag == 0) Pattern.compile(regex) else Pattern.compile(regex, flag)
        }
    }
}
