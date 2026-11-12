package com.tvbox.kotlin.feature.play.ui

import android.content.Context

/**
 * 音轨选择记忆：按进度键持久化 Exo 内核的音频轨道索引（渲染器/轨道组/轨道索引），
 * 重新播放同一集时自动恢复；媒体源结构变化导致索引失效时由调用方校验容错。
 */
object AudioTrackMemory {

    private const val PREFS_NAME = "audio_track_prefs"
    private const val SUFFIX_RENDER = "_render"
    private const val SUFFIX_GROUP = "_group"
    private const val SUFFIX_TRACK = "_track"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存 Exo 内核音频轨道索引 */
    fun saveExo(context: Context, playKey: String, renderId: Int, groupIndex: Int, trackId: Int) {
        prefs(context).edit()
            .putInt(playKey + SUFFIX_RENDER, renderId)
            .putInt(playKey + SUFFIX_GROUP, groupIndex)
            .putInt(playKey + SUFFIX_TRACK, trackId)
            .apply()
    }

    /** 读取 Exo 内核记忆的音轨索引；无记忆时返回 null */
    fun loadExo(context: Context, playKey: String): Triple<Int, Int, Int>? {
        val p = prefs(context)
        val renderId = p.getInt(playKey + SUFFIX_RENDER, -1)
        val groupIndex = p.getInt(playKey + SUFFIX_GROUP, -1)
        val trackId = p.getInt(playKey + SUFFIX_TRACK, -1)
        return if (renderId >= 0 && groupIndex >= 0 && trackId >= 0) {
            Triple(renderId, groupIndex, trackId)
        } else {
            null
        }
    }
}
