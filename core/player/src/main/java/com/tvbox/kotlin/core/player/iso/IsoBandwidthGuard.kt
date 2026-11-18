package com.tvbox.kotlin.core.player.iso

internal class IsoBandwidthVerdict(
    val measuredBytesPerSecond: Long,
    val requiredBytesPerSecond: Long,
)

internal fun isoRequiredBytesPerSecond(containerLength: Long, durationMs: Long): Long {
    if (containerLength <= 0L || durationMs <= 0L) return 0L
    val logicalLength = containerLength * M2TS_TS_SIZE / M2TS_PACKET_SIZE
    return logicalLength * MILLIS_PER_SECOND / durationMs
}

internal fun isoBandwidthVerdict(
    measuredBytesPerSecond: Long,
    targetLength: Long,
    durationMs: Long,
): IsoBandwidthVerdict? {
    if (measuredBytesPerSecond <= 0L) return null
    val required = isoRequiredBytesPerSecond(targetLength, durationMs)
    if (required <= 0L) return null
    if (measuredBytesPerSecond * PERCENT >= required * THRESHOLD_PERCENT) return null
    return IsoBandwidthVerdict(measuredBytesPerSecond, required)
}

internal fun stopsIsoBandwidthRetry(
    isoSession: Boolean,
    switchTried: Boolean,
): Boolean = isoSession && switchTried

internal fun effectiveIsoDuration(playerDurationMs: Long, playlistDurationMs: Long): Long =
    if (playlistDurationMs > 0L) playlistDurationMs else playerDurationMs

internal object IsoBandwidthGuard {

    fun evaluate(durationMs: Long): IsoBandwidthVerdict? =
        isoBandwidthVerdict(
            IsoThroughput.bytesPerSecond(),
            effectiveIsoBytes(),
            effectiveIsoDuration(durationMs, IsoPlaylistState.durationMs()),
        )
}

internal fun effectiveIsoBytes(): Long {
    val playlistBytes = IsoPlaylistState.totalBytes()
    return if (playlistBytes > 0L) playlistBytes else IsoThroughput.targetLength()
}

private const val THRESHOLD_PERCENT = 115L
private const val PERCENT = 100L
private const val MILLIS_PER_SECOND = 1000L
