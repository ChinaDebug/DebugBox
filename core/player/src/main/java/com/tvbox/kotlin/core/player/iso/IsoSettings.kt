package com.tvbox.kotlin.core.player.iso

import com.tvbox.kotlin.core.common.util.AppPreferencesHolder

/**
 * ISO/BDMV 音视频轨开关。
 *
 * 偏好可能在内核线程读取，未初始化或读取异常时回落到默认开启，避免中断播放。
 */
object IsoSettings {

    private const val KEY_LPCM = "player_iso_lpcm"
    private const val KEY_TRUEHD_CORE = "player_iso_truehd_core"
    private const val KEY_PGS = "player_iso_pgs"

    var lpcmEnabled: Boolean
        get() = read(KEY_LPCM)
        set(value) = write(KEY_LPCM, value)

    var trueHdCoreEnabled: Boolean
        get() = read(KEY_TRUEHD_CORE)
        set(value) = write(KEY_TRUEHD_CORE, value)

    var pgsEnabled: Boolean
        get() = read(KEY_PGS)
        set(value) = write(KEY_PGS, value)

    private fun read(key: String): Boolean =
        runCatching { AppPreferencesHolder.getBoolean(key, true) }.getOrDefault(true)

    private fun write(key: String, value: Boolean) {
        runCatching { AppPreferencesHolder.putBooleanSync(key, value) }
    }
}
