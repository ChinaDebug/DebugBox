package com.tvbox.kotlin.core.player

/**
 * 播放器内部请求头约定。
 *
 * [HEADER_FORMAT]、[HEADER_ISO] 与 [HEADER_CROSS_PROTOCOL] 仅用于向引擎传递播放类型
 * 或控制行为，不能作为真实请求头发送，统一由 [toRequestHeaders] 过滤。
 */
object HttpHeaders {

    const val HEADER_FORMAT = "TVBox-Format"

    /** 标记 ISO/BDMV 播放（值为任意非空字符串） */
    const val HEADER_ISO = "TVBox-Iso"

    /** 跨协议重定向开关（由 OkHttp 默认行为处理，不作为请求头） */
    const val HEADER_CROSS_PROTOCOL = "allowCrossProtocolRedirects"

    /** 转真实请求头：丢弃空值、内部格式标识、ISO 标记与重定向开关 */
    fun toRequestHeaders(headers: Map<String, String>?): HashMap<String, String> {
        val result = HashMap<String, String>()
        if (headers == null) return result
        for ((key, value) in headers) {
            if (key.isBlank() || value.isBlank()) continue
            if (key.equals(HEADER_FORMAT, true) ||
                key.equals(HEADER_ISO, true) ||
                key.equals(HEADER_CROSS_PROTOCOL, true)
            ) {
                continue
            }
            result[key] = value.trim()
        }
        return result
    }
}
