package com.tvbox.kotlin.core.server

import fi.iki.elonen.NanoHTTPD

/**
 * 本地服务请求处理器接口。
 *
 * 提供路由分发能力，由 [RemoteServer] 在 serve() 中按顺序匹配处理器。
 */
interface RequestProcess {

    /** 是否处理当前请求 */
    fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean

    /** 执行响应 */
    fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>,
        files: Map<String, String>
    ): NanoHTTPD.Response

    companion object {
        const val KEY_ACTION_PRESSED = 0
        const val KEY_ACTION_DOWN = 1
        const val KEY_ACTION_UP = 2
    }
}
