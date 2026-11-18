package com.tvbox.kotlin.core.player.iso

fun interface RangeBlockReader {
    fun read(start: Long, length: Int): ByteArray
}
