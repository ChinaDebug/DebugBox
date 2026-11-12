package com.tvbox.kotlin.core.server.webinspector

/**
 * WebView 请求类型。
 *
 * 区分表单提交、XHR、fetch 与原始 HTML 请求。
 */
enum class WebViewRequestType {
    /** fetch 请求 */
    FETCH,

    /** XMLHttpRequest */
    XML_HTTP,

    /** 表单提交 */
    FORM,

    /** 普通 HTML 主请求 */
    HTML
}
