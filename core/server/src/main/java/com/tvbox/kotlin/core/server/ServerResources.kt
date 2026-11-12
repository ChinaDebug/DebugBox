package com.tvbox.kotlin.core.server

import java.io.InputStream

/**
 * 本地服务静态资源提供者抽象。
 *
 * RawRequestProcess 直接通过 R.raw.* 资源 ID 加载首页与脚本，
 * server 模块不直接持有 app/ui 资源，改由上层模块注入资源流。
 *
 * 上层实现需保证返回的 [InputStream] 由调用方按需关闭。
 */
interface ServerResources {

    /** 根据请求路径查找静态资源，未命中返回 null */
    fun find(uri: String): Resource?

    /** 静态资源描述 */
    data class Resource(
        val mimeType: String,
        val openStream: () -> InputStream
    )
}
