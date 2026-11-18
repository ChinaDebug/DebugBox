package com.tvbox.kotlin.core.player.iso

import java.nio.charset.StandardCharsets

internal data class IsoPlaylistSegment(
    val clipId: String,
    val inTimeUs: Long,
    val outTimeUs: Long,
) {
    val durationMs: Long get() = ((outTimeUs - inTimeUs) / MILLIS_PER_SECOND).coerceAtLeast(0L)
}

internal data class IsoPlaylist(
    val name: String,
    val segments: List<IsoPlaylistSegment>,
    val durationMs: Long,
    val streams: List<IsoStream> = emptyList(),
    val videoPid: Int? = null,
)

internal data class IsoStream(
    val pid: Int,
    val language: String?,
)

internal fun mplTicksToMicros(ticks: Long): Long = ticks * MICROS_PER_SECOND / TICKS_PER_SECOND

internal fun mplTicksToMillis(ticks: Long): Long = mplTicksToMicros(ticks) / MILLIS_PER_SECOND

internal fun parseMplPlaylist(name: String, data: ByteArray): IsoPlaylist? {
    if (data.size < HEADER_MIN_SIZE) return null
    if (String(data, 0, SIGNATURE_SIZE, StandardCharsets.US_ASCII) != MPLS_SIGNATURE) return null
    val playListStart = be32(data, PLAY_LIST_START_OFFSET)
    if (playListStart < 0L || playListStart > MAX_OFFSET) return null
    val start = playListStart.toInt()
    if (start + PLAY_LIST_HEADER_SIZE > data.size) return null
    val playListLength = be32(data, start)
    if (playListLength < 0L || playListLength > MAX_OFFSET) return null
    if (start + PLAY_LIST_LENGTH_SIZE + playListLength > data.size) return null
    val clipCount = be16(data, start + COUNT_OFFSET)
    if (clipCount <= 0 || clipCount > MAX_CLIP_COUNT) return null
    val segments = ArrayList<IsoPlaylistSegment>(clipCount)
    val streams = ArrayList<IsoStream>()
    var videoPid: Int? = null
    var offset = start + FIRST_PLAY_ITEM_OFFSET
    var totalTicks = 0L
    for (index in 0 until clipCount) {
        if (offset + PLAY_ITEM_LENGTH_SIZE > data.size) return null
        val itemLength = be16(data, offset)
        if (itemLength < PLAY_ITEM_MIN_SIZE) return null
        val body = offset + PLAY_ITEM_LENGTH_SIZE
        if (body + itemLength > data.size) return null
        val clipId = String(data, body + CLIP_ID_OFFSET, CLIP_ID_LENGTH, StandardCharsets.US_ASCII)
        if (clipId.any { it < '0' || it > '9' }) return null
        val codec = String(data, body + CODEC_ID_OFFSET, CODEC_ID_LENGTH, StandardCharsets.US_ASCII)
        if (codec != CODEC_ID_M2TS) return null
        val inTime = be32(data, body + IN_TIME_OFFSET)
        val outTime = be32(data, body + OUT_TIME_OFFSET)
        if (outTime < inTime) return null
        segments.add(
            IsoPlaylistSegment(clipId, mplTicksToMicros(inTime), mplTicksToMicros(outTime)),
        )
        val itemVideoPid = readStnStreams(data, body, itemLength, streams)
        if (videoPid == null && itemVideoPid != null) videoPid = itemVideoPid
        totalTicks += outTime - inTime
        offset = body + itemLength
    }
    return IsoPlaylist(name, segments, mplTicksToMillis(totalTicks), dedupeStreams(streams), videoPid)
}

internal fun pickLongestPlaylist(playlists: List<IsoPlaylist>): IsoPlaylist? =
    playlists.maxByOrNull { uniqueDurationMs(it) }

internal data class IsoPrefetchWindow(val offset: Long, val length: Int)

internal data class MplPrefetchPlan(val windows: List<IsoPrefetchWindow>)

internal fun mplPrefetchPlan(
    extents: List<IsoExtent>,
    maxSpan: Long = PREFETCH_MAX_SPAN,
    maxGap: Long = PREFETCH_MAX_GAP,
    minDensity: Double = PREFETCH_MIN_DENSITY,
    maxWindows: Int = PREFETCH_MAX_WINDOWS,
): MplPrefetchPlan {
    val sorted = extents.filter { it.length > 0L }.sortedBy { it.offset }
    if (sorted.isEmpty()) return MplPrefetchPlan(emptyList())
    val groups = ArrayList<PrefetchGroup>()
    for (extent in sorted) {
        val end = extent.offset + extent.length
        val open = groups.lastOrNull()
        if (open != null && extent.offset <= open.end + maxGap) {
            open.payload += (end - maxOf(extent.offset, open.end)).coerceAtLeast(0L)
            open.end = maxOf(open.end, end)
        } else {
            groups.add(PrefetchGroup(extent.offset, end, extent.length))
        }
    }
    val windows = ArrayList<IsoPrefetchWindow>(groups.size)
    for (group in groups) {
        val span = group.end - group.start
        if (span <= 0L || span > maxSpan) continue
        if (group.payload.toDouble() / span < minDensity) continue
        windows.add(IsoPrefetchWindow(group.start, span.toInt()))
    }
    if (windows.isEmpty() || windows.size > maxWindows) return MplPrefetchPlan(emptyList())
    return MplPrefetchPlan(windows)
}

internal fun mplPrefetchCandidates(entries: List<IsoEntry>, covered: Set<String>): List<IsoEntry> =
    if (covered.isEmpty()) entries else entries.filter { it.name !in covered }

private class PrefetchGroup(var start: Long, var end: Long, var payload: Long)

internal fun uniqueDurationMs(playlist: IsoPlaylist): Long {
    val seen = HashSet<IsoPlaylistSegment>(playlist.segments.size)
    var total = 0L
    for (segment in playlist.segments) {
        if (seen.add(segment)) total += segment.durationMs
    }
    return total
}

private fun dedupeStreams(streams: List<IsoStream>): List<IsoStream> {
    if (streams.size < 2) return streams
    val seen = HashSet<IsoStream>(streams.size)
    val unique = ArrayList<IsoStream>(streams.size)
    for (stream in streams) {
        if (seen.add(stream)) unique.add(stream)
    }
    return unique
}

private class MplStreamRecord(
    val pid: Int,
    val language: String?,
    val end: Int,
)

private fun readStnStreams(data: ByteArray, body: Int, itemLength: Int, out: MutableList<IsoStream>): Int? {
    val itemEnd = body + itemLength
    if (itemLength < STN_MIN_ITEM_BYTES || itemEnd > data.size) return null
    var offset = body + STN_ITEM_OFFSET
    val flags = be16(data, body + FLAGS_OFFSET)
    if ((flags shr MULTI_ANGLE_BIT) and 1 == 1) {
        val angles = data[body + STN_ITEM_OFFSET].toInt() and 0xFF
        if (angles <= 0) return null
        offset = body + STN_ITEM_OFFSET + ANGLE_HEADER_BYTES + (angles - 1) * ANGLE_ENTRY_BYTES
    }
    if (offset + 2 > itemEnd) return null
    val stnLength = be16(data, offset)
    val stnStart = offset + 2
    val stnEnd = stnStart + stnLength
    if (stnLength < STN_HEADER_BYTES || stnEnd > itemEnd) return null
    val videoCount = data[stnStart + STN_VIDEO_COUNT].toInt() and 0xFF
    val audioCount = data[stnStart + STN_AUDIO_COUNT].toInt() and 0xFF
    val subtitleCount = data[stnStart + STN_SUBTITLE_COUNT].toInt() and 0xFF
    var position = stnStart + STN_HEADER_BYTES
    var videoPid: Int? = null
    for (index in 0 until videoCount) {
        val record = readStreamRecord(data, position, stnEnd, LANGUAGE_SHIFT_AUDIO) ?: return null
        if (videoPid == null && record.pid >= 0) videoPid = record.pid
        position = record.end
    }
    for (index in 0 until audioCount) {
        val record = readStreamRecord(data, position, stnEnd, LANGUAGE_SHIFT_AUDIO) ?: return null
        if (record.pid >= 0 && record.language != null) {
            out.add(IsoStream(record.pid, record.language))
        }
        position = record.end
    }
    for (index in 0 until subtitleCount) {
        val record = readStreamRecord(data, position, stnEnd, LANGUAGE_SHIFT_SUBTITLE) ?: return null
        if (record.pid >= 0 && record.language != null) {
            out.add(IsoStream(record.pid, record.language))
        }
        position = record.end
    }
    return videoPid
}

private fun readStreamRecord(
    data: ByteArray,
    start: Int,
    end: Int,
    languageShift: Int,
): MplStreamRecord? {
    if (start + 2 > end) return null
    val entryLength = data[start].toInt() and 0xFF
    val entryEnd = start + 1 + entryLength
    if (entryEnd + 2 > end) return null
    val inMux = (data[start + 1].toInt() and 0xFF) == STREAM_ENTRY_IN_MUX
    val pid = if (inMux && entryLength >= STREAM_ENTRY_PID_BYTES) be16(data, start + 2) else -1
    val attributeLength = data[entryEnd].toInt() and 0xFF
    val attributeStart = entryEnd + 1
    val attributeEnd = attributeStart + attributeLength
    if (attributeEnd > end) return null
    val language = if (pid >= 0 && attributeLength >= languageShift + LANGUAGE_BYTES) {
        readLanguage(data, attributeStart + languageShift)
    } else {
        null
    }
    return MplStreamRecord(pid, language, attributeEnd)
}

private fun readLanguage(data: ByteArray, offset: Int): String? {
    var text = ""
    for (index in 0 until LANGUAGE_BYTES) {
        val value = data[offset + index].toInt() and 0xFF
        if (value < PRINTABLE_MIN || value > PRINTABLE_MAX) return null
        text += value.toChar()
    }
    if (text == UNDETERMINED_LANGUAGE) return null
    return text
}

private fun be16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun be32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

private const val PREFETCH_MAX_SPAN = 4L * 1024L * 1024L
private const val PREFETCH_MAX_GAP = 256L * 1024L
private const val PREFETCH_MIN_DENSITY = 0.5
private const val PREFETCH_MAX_WINDOWS = 4
private const val MPLS_SIGNATURE = "MPLS"
private const val SIGNATURE_SIZE = 4
private const val HEADER_MIN_SIZE = 12
private const val PLAY_LIST_START_OFFSET = 8
private const val PLAY_LIST_LENGTH_SIZE = 4
private const val COUNT_OFFSET = 6
private const val PLAY_LIST_HEADER_SIZE = 10
private const val FIRST_PLAY_ITEM_OFFSET = 10
private const val PLAY_ITEM_LENGTH_SIZE = 2
private const val PLAY_ITEM_MIN_SIZE = 20
private const val CLIP_ID_OFFSET = 0
private const val CLIP_ID_LENGTH = 5
private const val CODEC_ID_OFFSET = 5
private const val CODEC_ID_LENGTH = 4
private const val CODEC_ID_M2TS = "M2TS"
private const val IN_TIME_OFFSET = 12
private const val OUT_TIME_OFFSET = 16
private const val MAX_CLIP_COUNT = 999
private const val MAX_OFFSET = 64L * 1024L * 1024L
private const val TICKS_PER_SECOND = 45_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val FLAGS_OFFSET = 9
private const val MULTI_ANGLE_BIT = 4
private const val STN_ITEM_OFFSET = 32
private const val STN_MIN_ITEM_BYTES = 34
private const val ANGLE_HEADER_BYTES = 2
private const val ANGLE_ENTRY_BYTES = 11
private const val STN_HEADER_BYTES = 14
private const val STN_VIDEO_COUNT = 2
private const val STN_AUDIO_COUNT = 3
private const val STN_SUBTITLE_COUNT = 4
private const val STREAM_ENTRY_IN_MUX = 1
private const val STREAM_ENTRY_PID_BYTES = 3
private const val LANGUAGE_BYTES = 3
private const val LANGUAGE_SHIFT_AUDIO = 2
private const val LANGUAGE_SHIFT_SUBTITLE = 1
private const val PRINTABLE_MIN = 0x20
private const val PRINTABLE_MAX = 0x7E
private const val UNDETERMINED_LANGUAGE = "und"
