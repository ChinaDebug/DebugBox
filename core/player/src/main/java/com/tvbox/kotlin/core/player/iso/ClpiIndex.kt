package com.tvbox.kotlin.core.player.iso

import java.nio.charset.StandardCharsets

data class ClpiAccessPoint(
    val ptsTicks: Long,
    val spn: Long,
    val angleChange: Boolean,
) {
    val packetOffset: Long get() = spn * SOURCE_PACKET_SIZE

    val syncOffset: Long get() = packetOffset + SOURCE_PACKET_HEADER
}

data class ClpiIndex(
    val clipName: String,
    val accessPoints: List<ClpiAccessPoint>,
    val sourcePackets: Long,
) {
    fun accessPointAt(ptsTicks: Long): ClpiAccessPoint? {
        var low = 0
        var high = accessPoints.size - 1
        var found: ClpiAccessPoint? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val point = accessPoints[mid]
            if (point.ptsTicks <= ptsTicks) {
                found = point
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    fun minGapTicks(): Long {
        var min = Long.MAX_VALUE
        for (index in 1 until accessPoints.size) {
            val gap = accessPoints[index].ptsTicks - accessPoints[index - 1].ptsTicks
            if (gap > 0 && gap < min) min = gap
        }
        return if (min == Long.MAX_VALUE) 0L else min
    }

    fun maxGapTicks(): Long {
        var max = 0L
        for (index in 1 until accessPoints.size) {
            val gap = accessPoints[index].ptsTicks - accessPoints[index - 1].ptsTicks
            if (gap > max) max = gap
        }
        return max
    }
}

internal fun parseClpi(clipName: String, data: ByteArray): ClpiIndex? {
    if (data.size < HEADER_SIZE) return null
    if (String(data, 0, SIGNATURE_SIZE, StandardCharsets.US_ASCII) != CLPI_SIGNATURE) return null
    val cpiStart = be32At(data, CPI_START_OFFSET)
    if (cpiStart < 0L || cpiStart > MAX_OFFSET) return null
    val cpi = cpiStart.toInt()
    if (cpi + CPI_MIN_SIZE > data.size) return null
    val cpiLength = be32At(data, cpi)
    if (cpiLength <= 0L || cpi + CPI_MIN_SIZE + cpiLength > data.size + CPI_TAIL_SLACK) return null
    val bits = BitReader(data, (cpi + CPI_HEADER_BYTES) * 8)
    bits.skip(CPI_RESERVED_BITS)
    val type = bits.read(4)
    if (type != 1) return null
    val epMapPos = bits.position() / 8
    bits.skip(8)
    val streamCount = bits.read(8)
    if (streamCount <= 0 || streamCount > MAX_STREAMS) return null
    var pid = 0
    var coarseCount = 0
    var fineCount = 0
    var mapOffset = 0L
    for (index in 0 until streamCount) {
        val streamPid = bits.read(16)
        bits.skip(10)
        bits.read(4)
        val coarse = bits.read(16)
        val fine = bits.read(18)
        val offset = bits.readLong(32)
        if (index == 0) {
            pid = streamPid
            coarseCount = coarse
            fineCount = fine
            mapOffset = offset
        }
    }
    if (coarseCount <= 0 || fineCount <= 0) return null
    val streamStart = epMapPos + mapOffset
    if (streamStart < 0L || streamStart > MAX_OFFSET) return null
    val base = streamStart.toInt()
    if (base + 4 > data.size) return null
    val fineStart = be32At(data, base)
    val coarseBase = base + FINE_START_SIZE
    val fineBase = base + fineStart.toInt()
    if (coarseBase + coarseCount * COARSE_SIZE > data.size) return null
    if (fineBase + fineCount * FINE_SIZE > data.size) return null
    val finePts = IntArray(fineCount)
    val fineSpn = LongArray(fineCount)
    val angle = BooleanArray(fineCount)
    var fineIndex = 0
    while (fineIndex < fineCount) {
        val offset = fineBase + fineIndex * FINE_SIZE
        val value = be32At(data, offset)
        angle[fineIndex] = (value ushr 31) != 0L
        finePts[fineIndex] = ((value ushr 17) and 0x7FF).toInt()
        fineSpn[fineIndex] = value and 0x1FFFF
        fineIndex++
    }
    val points = ArrayList<ClpiAccessPoint>(fineCount)
    var coarseIndex = 0
    while (coarseIndex < coarseCount) {
        val offset = coarseBase + coarseIndex * COARSE_SIZE
        val packed = be32At(data, offset)
        val spnHigh = be32At(data, offset + 4)
        val refFine = ((packed ushr 14) and 0x3FFFF).toInt()
        val ptsHigh = (packed and COARSE_PTS_MASK) shl 18
        if (refFine in 0 until fineCount) {
            val pts = ptsHigh or (finePts[refFine].toLong() shl 8)
            points.add(
                ClpiAccessPoint(
                    ptsTicks = pts,
                    spn = (spnHigh and SPN_COARSE_MASK) or fineSpn[refFine],
                    angleChange = angle[refFine],
                ),
            )
        }
        coarseIndex++
    }
    if (points.isEmpty()) return null
    points.sortBy { it.ptsTicks }
    val size = be32At(data, CLIPINFO_PACKETS_OFFSET)
    return ClpiIndex(clipName, points, if (size > 0L) size else 0L)
}

private class BitReader(private val data: ByteArray, private var bit: Int) {

    fun position(): Int = bit

    fun skip(count: Int) {
        bit += count
    }

    fun read(count: Int): Int {
        var value = 0
        for (index in 0 until count) {
            val byte = data[(bit + index) / 8].toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - ((bit + index) % 8))) and 1)
        }
        bit += count
        return value
    }

    fun readLong(count: Int): Long {
        var value = 0L
        for (index in 0 until count) {
            val byte = data[(bit + index) / 8].toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - ((bit + index) % 8))) and 1).toLong()
        }
        bit += count
        return value
    }
}

private fun be32At(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)

internal const val SOURCE_PACKET_SIZE = 192L
internal const val SOURCE_PACKET_HEADER = 4L
internal const val CLPI_TICKS_PER_SECOND = 45_000L
internal const val CLPI_TICKS_PER_MILLI = CLPI_TICKS_PER_SECOND / 1_000L

private const val CLPI_SIGNATURE = "HDMV"
private const val SIGNATURE_SIZE = 4
private const val HEADER_SIZE = 32
private const val CPI_START_OFFSET = 16
private const val CLIPINFO_PACKETS_OFFSET = 56
private const val CPI_MIN_SIZE = 12
private const val CPI_HEADER_BYTES = 4
private const val CPI_RESERVED_BITS = 12
private const val CPI_TAIL_SLACK = 8
private const val FINE_START_SIZE = 4
private const val COARSE_SIZE = 8
private const val COARSE_PTS_MASK = 0x3FFEL
private const val FINE_SIZE = 4
private const val SPN_COARSE_MASK = 0xFFFFE000L
private const val MAX_STREAMS = 64
private const val MAX_OFFSET = 64L * 1024L * 1024L
