package com.tvbox.kotlin.core.player.iso

internal enum class IsoSeekAction {
    ALLOW,
    BLOCK,
    REBUILD,
}

internal fun isoSeekAction(
    isoSession: Boolean,
    seekable: Boolean,
    positionMs: Long,
    currentMs: Long,
    toleranceMs: Long,
    rebuildEnabled: Boolean,
): IsoSeekAction {
    if (!isoSession) return IsoSeekAction.ALLOW
    if (positionMs <= toleranceMs && currentMs <= toleranceMs) return IsoSeekAction.ALLOW
    if (!seekable) return IsoSeekAction.BLOCK
    return if (rebuildEnabled) IsoSeekAction.REBUILD else IsoSeekAction.BLOCK
}
