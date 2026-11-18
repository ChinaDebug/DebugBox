package com.tvbox.kotlin.core.player.effect.anime4k

import android.content.Context
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

internal class Anime4kEffect : GlEffect {

    @Volatile
    var tier: Anime4kTier? = null

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Anime4kChainProgram(context, tier, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = tier == null
}
