package com.tvbox.kotlin.core.player.effect.anime4k

object EffectCanvas {

    @Volatile
    private var size: IntArray? = null

    fun set(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        size = intArrayOf(width, height)
    }

    fun get(): IntArray? = size
}
