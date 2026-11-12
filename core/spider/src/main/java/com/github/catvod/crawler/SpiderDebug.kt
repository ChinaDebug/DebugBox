package com.github.catvod.crawler

import com.tvbox.kotlin.core.common.LOG

/** 爬虫统一日志输出，避免 jar 内部直接使用 Log 造成混淆 */
object SpiderDebug {

    private const val TAG = "SpiderLog"

    @JvmStatic
    fun log(th: Throwable) {
        LOG.e(TAG, th.message, th)
    }

    @JvmStatic
    fun log(msg: String) {
        LOG.d(TAG, msg)
    }

    @JvmStatic
    fun ec(i: Int): String = ""
}
