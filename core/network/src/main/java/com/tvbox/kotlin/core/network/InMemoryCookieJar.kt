package com.tvbox.kotlin.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存级 Cookie 持久化 Jar。
 *
 * 默认实现仅持有运行期 Cookie，进程结束后清空，适合爬虫场景下短期复用会话。
 * 需要跨进程持久化时可在此扩展为 DataStore/Room 写入。
 *
 * 线程安全：内部使用 [ConcurrentHashMap] + 同步 List。
 */
class InMemoryCookieJar : CookieJar {

    /** host -> cookies */
    private val store: MutableMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val list = store.getOrPut(url.host) { mutableListOf() }
        // 同名同 path 的 Cookie 替换，避免无限增长
        cookies.forEach { incoming ->
            list.removeAll { it.name == incoming.name && it.path == incoming.path }
            if (incoming.expiresAt > System.currentTimeMillis()) {
                list.add(incoming)
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        // 过滤过期 Cookie
        list.removeAll { it.expiresAt <= now }
        return list.filter { it.matches(url) }
    }

    /** 清空全部 Cookie，登出/切换账号场景调用 */
    @Synchronized
    fun clear() {
        store.clear()
    }

    /** 清除指定 host 的 Cookie */
    @Synchronized
    fun clearForHost(host: String) {
        store.remove(host)
    }
}
