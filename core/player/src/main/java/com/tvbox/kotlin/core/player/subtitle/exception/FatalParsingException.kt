package com.tvbox.kotlin.core.player.subtitle.exception

/**
 * 字幕解析过程中的致命错误。
 */
class FatalParsingException(parsingError: String) : Exception(parsingError) {

    override fun getLocalizedMessage(): String = message ?: ""
}
