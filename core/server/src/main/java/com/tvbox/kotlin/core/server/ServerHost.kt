package com.tvbox.kotlin.core.server

/**
 * 本地服务外部依赖聚合接口。
 *
 * server 模块不持有 ApiConfig、Proxy、OkGoHelper 等上层依赖，
 * 由上层模块实现此接口并通过 Hilt 注入到 [ControlManager]。
 *
 * proxy 路由返回的结果数组格式与之一致：
 *  - index 0: 状态码 Int 或 "error" 字符串或 NanoHTTPD.Response
 *  - index 1: mime 或错误消息
 *  - index 2: InputStream
 *  - index 3: 可选的响应头 Map
 */
interface ServerHost {

    /** 静态资源提供者 */
    val resources: ServerResources

    /** 是否存在指定 key 的源 */
    fun hasSource(key: String): Boolean

    /** 是否存在 push_agent 源 */
    fun hasPushAgent(): Boolean = hasSource("push_agent")

    /** 本地代理（do=xxx）路由处理 */
    fun proxyLocal(params: Map<String, String>): Array<Any?>?

    /** 通用代理（go=xxx）路由处理 */
    fun proxy(params: Map<String, String>): Array<Any?>?

    /** DNS over HTTPS 查询，返回原始响应字节 */
    fun resolveDns(name: String?): ByteArray

    /** DASH 数据（base64 编码），无数据返回 null */
    fun dashData(): String?

    /** 服务启动成功回调，上层据此同步端口到播放器与爬虫代理
     * @param port 实际监听端口
     * @param dohEnabled 是否启用 DNS over HTTPS */
    fun onServerStarted(port: Int, dohEnabled: Boolean)
}
