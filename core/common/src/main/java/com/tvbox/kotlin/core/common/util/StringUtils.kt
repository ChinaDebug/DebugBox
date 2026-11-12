package com.tvbox.kotlin.core.common.util

/** 字符串与对象空判断工具 */
object StringUtils {

    fun isEmpty(str: CharSequence?): Boolean = str == null || str.isEmpty()
}
