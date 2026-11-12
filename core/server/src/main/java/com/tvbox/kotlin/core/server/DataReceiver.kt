package com.tvbox.kotlin.core.server

/**
 * 远端推送数据接收回调。
 *
 * 由 [RemoteServer] 在收到 POST /action 或 GET /api/updateUrl 时调用，
 * [ControlManager] 通过匿名实现将各类型推送转发到事件总线或广播。
 */
interface DataReceiver {
    fun onTextReceived(text: String?)

    fun onApiReceived(url: String?)

    fun onLiveReceived(url: String?)

    fun onEpgReceived(url: String?)

    fun onPushReceived(url: String?)

    fun onMirrorReceived(id: String?, sourceKey: String?)

    /**
     * 弹幕就绪回调（GET /action?do=refresh&type=danmaku）。
     * 爬虫异步生成弹幕后通过该接口通知，[url] 为当前剧集可加载的弹幕地址。
     */
    fun onDanmuReceived(url: String?)

    /**
     * 提交全部配置并重载（web 订阅设置界面"提交全部并重载"按钮）。
     * 由接收方统一保存三项地址并触发应用重载。
     */
    fun onSubmitAll(api: String?, live: String?, epg: String?)
}
