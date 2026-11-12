package com.tvbox.kotlin.core.server.webinspector

import com.tvbox.kotlin.core.common.LOG
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * WebView 请求记录的 JavaScript 接口。
 *
 * 通过注入 JS 钩子拦截表单提交、XHR、fetch 请求，
 * 并在原生侧按 URL 或拦截头匹配回放请求体与头信息。
 *
 * @param webView 需要拦截的 WebView，构造时立即注入 JavascriptInterface
 */
internal class RequestInspectorJavaScriptInterface(webView: WebView) {

    init {
        webView.addJavascriptInterface(this, INTERFACE_NAME)
    }

    private val recordedRequests = ArrayList<RecordedRequest>()

    /** 按 URL 与请求头匹配已记录的请求 */
    fun findRecordedRequestForUrl(url: String, headers: Map<String, String>): RecordedRequest? {
        return synchronized(recordedRequests) {
            recordedRequests.find { recordedRequest ->
                if (recordedRequest.headers.containsKey(INTERCEPT_HEADER)) {
                    // 命中拦截头时按头匹配
                    recordedRequest.headers[INTERCEPT_HEADER] == headers[INTERCEPT_HEADER]
                } else {
                    // 否则按完整 URL 匹配
                    url == recordedRequest.url
                }
            }
        }
    }

    /** 已记录的请求描述 */
    data class RecordedRequest(
        val type: WebViewRequestType,
        val url: String,
        val method: String,
        val body: String,
        val headers: Map<String, String>,
        val trace: String,
        val enctype: String?
    )

    @JavascriptInterface
    fun recordFormSubmission(
        url: String,
        method: String,
        formParameterList: String,
        headers: String,
        trace: String,
        enctype: String?
    ) {
        val formParameterJsonArray = JSONArray(formParameterList)
        val headerMap = getHeadersAsMap(headers)
        val body = when (enctype) {
            "application/x-www-form-urlencoded" -> {
                headerMap["content-type"] = enctype
                getUrlEncodedFormBody(formParameterJsonArray)
            }
            "multipart/form-data" -> {
                headerMap["content-type"] = "multipart/form-data; boundary=$MULTIPART_FORM_BOUNDARY"
                getMultiPartFormBody(formParameterJsonArray)
            }
            "text/plain" -> {
                headerMap["content-type"] = enctype
                getPlainTextFormBody(formParameterJsonArray)
            }
            else -> {
                LOG.e(LOG_TAG, "Incorrect encoding received from JavaScript: $enctype")
                ""
            }
        }
        addRecordedRequest(
            RecordedRequest(WebViewRequestType.FORM, url, method, body, headerMap, trace, enctype)
        )
    }

    @JavascriptInterface
    fun recordXhr(url: String, method: String, body: String, headers: String, trace: String) {
        val headerMap = getHeadersAsMap(headers)
        addRecordedRequest(
            RecordedRequest(WebViewRequestType.XML_HTTP, url, method, body, headerMap, trace, null)
        )
    }

    @JavascriptInterface
    fun recordFetch(url: String, method: String, body: String, headers: String, trace: String) {
        val headerMap = getHeadersAsMap(headers)
        addRecordedRequest(
            RecordedRequest(WebViewRequestType.FETCH, url, method, body, headerMap, trace, null)
        )
    }

    private fun addRecordedRequest(recordedRequest: RecordedRequest) {
        synchronized(recordedRequests) {
            recordedRequests.add(recordedRequest)
        }
    }

    private fun getHeadersAsMap(headersString: String): MutableMap<String, String> {
        val headersObject = JSONObject(headersString)
        val map = HashMap<String, String>()
        for (key in headersObject.keys()) {
            val lowercaseHeader = key.lowercase(Locale.getDefault())
            map[lowercaseHeader] = headersObject.getString(key)
        }
        return map
    }

    private fun getUrlEncodedFormBody(formParameterJsonArray: JSONArray): String {
        val sb = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            if (i != 0) sb.append("&")
            sb.append(name).append("=").append(encodedValue)
        }
        return sb.toString()
    }

    private fun getMultiPartFormBody(formParameterJsonArray: JSONArray): String {
        val sb = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            sb.append("--").append(MULTIPART_FORM_BOUNDARY).append("\n")
            sb.append("Content-Disposition: form-data; name=\"$name\"").append("\n\n")
            sb.append(value).append("\n")
        }
        sb.append("--").append(MULTIPART_FORM_BOUNDARY).append("--")
        return sb.toString()
    }

    private fun getPlainTextFormBody(formParameterJsonArray: JSONArray): String {
        val sb = StringBuilder()
        repeat(formParameterJsonArray.length()) { i ->
            val formParameter = formParameterJsonArray.get(i) as JSONObject
            val name = formParameter.getString("name")
            val value = formParameter.getString("value")
            if (i != 0) sb.append("\n")
            sb.append(name).append("=").append(value)
        }
        return sb.toString()
    }

    companion object {
        private const val LOG_TAG = "RequestInspectorJs"
        private const val MULTIPART_FORM_BOUNDARY = "----WebKitFormBoundaryU7CgQs9WnqlZYKs6"
        private const val INTERFACE_NAME = "RequestInspection"
        private const val INTERCEPT_HEADER = "x-request-intercept-identifier"

        /** 注入到 WebView 的请求拦截 JS 代码 */
        private const val JAVASCRIPT_INTERCEPTION_CODE = """
function getFullUrl(url) {
    if (url && url.startsWith("/")) {
        return location.protocol + '//' + location.host + url;
    } else {
        return url;
    }
}

function recordFormSubmission(form) {
    var jsonArr = [];
    for (i = 0; i < form.elements.length; i++) {
        var parName = form.elements[i].name;
        var parValue = form.elements[i].value;
        var parType = form.elements[i].type;

        jsonArr.push({
            name: parName,
            value: parValue,
            type: parType
        });
    }

    const path = form.attributes['action'] === undefined ? "/" : form.attributes['action'].nodeValue;
    const method = form.attributes['method'] === undefined ? "GET" : form.attributes['method'].nodeValue;
    const url = getFullUrl(path);
    const encType = form.attributes['enctype'] === undefined ? "application/x-www-form-urlencoded" : form.attributes['enctype'].nodeValue;
    const err = new Error();
    $INTERFACE_NAME.recordFormSubmission(
        url,
        method,
        JSON.stringify(jsonArr),
        "{}",
        err.stack,
        encType
    );
}

function handleFormSubmission(e) {
    const form = e ? e.target : this;
    recordFormSubmission(form);
    form._submit();
}

HTMLFormElement.prototype._submit = HTMLFormElement.prototype.submit;
HTMLFormElement.prototype.submit = handleFormSubmission;
window.addEventListener('submit', function (submitEvent) {
    handleFormSubmission(submitEvent);
}, true);

let lastXmlhttpRequestPrototypeMethod = null;
let xmlhttpRequestHeaders = {};
let xmlhttpRequestUrl = null;
XMLHttpRequest.prototype._open = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function (method, url, async, user, password) {
    lastXmlhttpRequestPrototypeMethod = method;
    xmlhttpRequestUrl = url;
    const asyncWithDefault = async === undefined ? true : async;
    this._open(method, url, asyncWithDefault, user, password);
};
XMLHttpRequest.prototype._setRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
XMLHttpRequest.prototype.setRequestHeader = function (header, value) {
    xmlhttpRequestHeaders[header] = value;
    this._setRequestHeader(header, value);
};
XMLHttpRequest.prototype._send = XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.send = function (body) {
    const err = new Error();
    const url = getFullUrl(xmlhttpRequestUrl);
    const reqIdentifier = new Date().getTime();
    const headers = { ...xmlhttpRequestHeaders, '$INTERCEPT_HEADER': reqIdentifier }
    $INTERFACE_NAME.recordXhr(
        url,
        lastXmlhttpRequestPrototypeMethod,
        body || "",
        JSON.stringify(headers),
        err.stack
    );
    lastXmlhttpRequestPrototypeMethod = null;
    xmlhttpRequestUrl = null;
    xmlhttpRequestHeaders = {};
    this.setRequestHeader('$INTERCEPT_HEADER', reqIdentifier);
    this._send(body);
};

window._fetch = window.fetch;
window.fetch = function () {
    const url = arguments[1] && 'url' in arguments[1] ? arguments[1]['url'] : "/";
    const fullUrl = getFullUrl(url);
    const method = arguments[1] && 'method' in arguments[1] ? arguments[1]['method'] : "GET";
    const body = arguments[1] && 'body' in arguments[1] ? arguments[1]['body'] : "";
    const reqIdentifier = new Date().getTime();
    const headers = JSON.stringify({ ...(arguments[1] && 'headers' in arguments[1] ? arguments[1]['headers'] : {}), '$INTERCEPT_HEADER': reqIdentifier });
    let err = new Error();
    $INTERFACE_NAME.recordFetch(fullUrl, method, body, headers, err.stack);
    return window._fetch.apply(this, { ...arguments, 'headers': headers });
}

window._sendBeacon = window.navigator.sendBeacon;
window.navigator.sendBeacon = function (url, arguments) {
    const fullUrl = getFullUrl(url);
    const method = "POST";
    const reqIdentifier = new Date().getTime();
    const headers = JSON.stringify({ '$INTERCEPT_HEADER': reqIdentifier });
    let err = new Error();
    $INTERFACE_NAME.recordXhr(fullUrl, method, body, headers, err.stack);
    this.setRequestHeader('$INTERCEPT_HEADER', reqIdentifier);
    return window._sendBeacon.apply(this, arguments);
}
        """

        /** 启用请求拦截，注入拦截代码与额外 JS */
        fun enabledRequestInspection(webView: WebView, extraJavaScriptToInject: String) {
            webView.evaluateJavascript(
                "javascript: $JAVASCRIPT_INTERCEPTION_CODE\n$extraJavaScriptToInject",
                null
            )
        }
    }
}
