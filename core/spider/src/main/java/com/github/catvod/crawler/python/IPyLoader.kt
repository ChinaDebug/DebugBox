package com.github.catvod.crawler.python

import com.github.catvod.crawler.Spider

/**
 * Python 爬虫加载器契约。
 * 由 pyLoader 实现，ApiConfig 通过 IPyLoader 类型解耦对 :engine:pyramid 的依赖。
 */
interface IPyLoader {
    fun clear()
    fun setConfig(jsonStr: String?)
    fun setRecentPyKey(pyApi: String?)
    fun getSpider(key: String, cls: String, ext: String): Spider
    fun proxyInvoke(params: Map<String, String>): Array<Any?>?
}
