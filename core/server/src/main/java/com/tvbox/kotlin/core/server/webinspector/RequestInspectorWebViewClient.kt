package com.tvbox.kotlin.core.server.webinspector

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 请求拦截客户端。
 *
 * 在页面加载时注入 JS 钩子记录表单/XHR/fetch 请求，
 * 并在 [shouldInterceptRequest] 回调中将记录合并到 [WebViewRequest] 供子类判断。
 *
 * 子类重写 [shouldInterceptRequest]（[WebViewRequest] 版本）即可实现自定义拦截。
 */
@SuppressLint("SetJavaScriptEnabled")
open class RequestInspectorWebViewClient @JvmOverloads constructor(
    webView: WebView,
    private val options: RequestInspectorOptions = RequestInspectorOptions()
) : WebViewClient() {

    private val interceptionJavascriptInterface = RequestInspectorJavaScriptInterface(webView)

    init {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
    }

    final override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val recordedRequest = interceptionJavascriptInterface.findRecordedRequestForUrl(
            request.url.toString(),
            request.requestHeaders
        )
        val webViewRequest = WebViewRequest.create(request, recordedRequest)
        return shouldInterceptRequest(view, webViewRequest)
    }

    /** 子类重写此方法实现自定义拦截，返回 null 表示放行 */
    open fun shouldInterceptRequest(
        view: WebView,
        webViewRequest: WebViewRequest
    ): WebResourceResponse? {
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        RequestInspectorJavaScriptInterface.enabledRequestInspection(
            view,
            options.extraJavaScriptToInject
        )
        super.onPageStarted(view, url, favicon)
    }
}
