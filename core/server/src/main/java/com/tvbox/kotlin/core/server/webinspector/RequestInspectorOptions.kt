package com.tvbox.kotlin.core.server.webinspector

/**
 * WebView 请求拦截器配置。
 *
 * 仅承载需要额外注入的 JavaScript 代码片段。
 */
data class RequestInspectorOptions(
    val extraJavaScriptToInject: String = ""
)
