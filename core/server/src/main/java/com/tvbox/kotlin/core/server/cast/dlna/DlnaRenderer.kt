package com.tvbox.kotlin.core.server.cast.dlna

/**
 * DLNA 渲染器（MediaRenderer）。
 *
 * [id] 为 UPnP UDN 标识，用于在 cling 注册表中定位设备；[name] 为友好名称。
 */
data class DlnaRenderer(
    val id: String,
    val name: String
)
