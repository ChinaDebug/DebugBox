package com.github.catvod.crawler

/**
 * JS 爬虫工厂契约，由上层提供 QuickJS 实现以解耦 JsLoader 与 JsSpider 依赖树。
 * JsSpider 及其 JS 全局工具（Global/Connect/HtmlParser 等）属 app 层范围，
 * 此处仅声明创建契约，JsLoader 在 [create] 返回空时回落到 SpiderNull。
 */
fun interface JsSpiderFactory {

    /**
     * 创建 JS 爬虫实例。
     *
     * @param key 站点 key
     * @param api JS 爬虫 api 标识
     * @param classLoader 自定义 jsapi jar 的 ClassLoader，可能为空
     * @return 实现 [Spider] 的 JS 爬虫实例
     */
    fun create(key: String, api: String, classLoader: Class<*>?): Spider
}
