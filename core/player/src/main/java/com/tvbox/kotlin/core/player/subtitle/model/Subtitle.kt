package com.tvbox.kotlin.core.player.subtitle.model

/**
 * 单条字幕。content 内部以 `<br />` 表示换行，便于上层统一渲染。
 */
class Subtitle {
    var style: Style? = null
    var region: Region? = null
    var start: Time = Time("hh:mm:ss,ms", "00:00:00,000")
    var end: Time = Time("hh:mm:ss,ms", "00:00:00,000")
    var content: String = ""
}
