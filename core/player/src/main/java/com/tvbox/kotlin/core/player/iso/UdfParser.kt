package com.tvbox.kotlin.core.player.iso

internal class UdfParser(
    private val reader: RangeBlockReader,
    private val blockSize: Int,
    private val totalLength: Long,
    private val cache: MutableMap<FileLocation, ParsedFileEntry>,
) {

    private val spans = ArrayList<Span>()
    private var spanBytes = 0L

    fun readFileEntry(
        location: FileLocation,
        resolver: (Int, Long) -> Long,
    ): ParsedFileEntry {
        cache[location]?.let { return it }
        val header = readPartitionBytes(location.partitionRef, location.block, blockSize, resolver)
        val tag = le16(header, 0)
        val extended = tag == TAG_EXTENDED_FILE_ENTRY
        if (tag != TAG_FILE_ENTRY && !extended) {
            throw IsoFormatException("file entry tag: ${location.block}")
        }
        val fileType = header[FILE_TYPE_OFFSET].toInt() and 0xFF
        val flags = le16(header, ICB_FLAGS_OFFSET)
        val infoLength = le64(header, FILE_INFO_LENGTH_OFFSET)
        val lengthOffset = if (extended) EXTENDED_LENGTH_OFFSET else FILE_ENTRY_LENGTH_OFFSET
        val dataOffset = if (extended) EXTENDED_DATA_OFFSET else FILE_ENTRY_DATA_OFFSET
        val extendedLength = le32(header, lengthOffset).toInt()
        val allocationLength = le32(header, lengthOffset + 4).toInt()
        val required = dataOffset + extendedLength + allocationLength
        if (extendedLength < 0 || allocationLength < 0 || required > blockSize * MAX_FILE_ENTRY_BLOCKS) {
            throw IsoFormatException("file entry size: ${location.block}")
        }
        val raw = if (required <= blockSize) {
            header
        } else {
            readPartitionBytes(location.partitionRef, location.block, required, resolver)
        }
        val data = parseAllocation(
            location.partitionRef,
            raw,
            dataOffset + extendedLength,
            allocationLength,
            flags and 0x7,
            resolver,
        )
        val extents = when (data) {
            is EntryData.Extents -> data.extents
            is EntryData.Embedded -> emptyList()
        }
        val length = if (infoLength > 0) infoLength else extents.sumOf { it.length }
        val parsed = ParsedFileEntry(fileType, length, extents, data)
        cache[location] = parsed
        return parsed
    }

    fun readPartitionBytes(
        partitionRef: Int,
        startBlock: Long,
        length: Int,
        resolver: (Int, Long) -> Long,
    ): ByteArray {
        val bytes = ByteArray(length)
        var copied = 0
        var block = startBlock
        while (copied < length) {
            val offset = resolver(partitionRef, block)
            val chunk = minOf(length - copied, blockSize)
            val data = readRange(offset, chunk)
            System.arraycopy(data, 0, bytes, copied, chunk)
            copied += chunk
            block++
        }
        return bytes
    }

    fun readRange(start: Long, length: Int): ByteArray {
        if (start < 0 || length <= 0) throw IsoFormatException("invalid range: $start/$length")
        if (start + length > totalLength) throw IsoFormatException("range beyond image: $start/$length")
        cached(start, length)?.let { return it }
        val fetch = minOf(maxOf(length.toLong(), PREFETCH_BYTES.toLong()), totalLength - start).toInt()
        val bytes = reader.read(start, fetch)
        if (bytes.size < length) throw IsoFormatException("short read: ${bytes.size}/$length")
        remember(start, bytes)
        return bytes.copyOfRange(0, length)
    }

    fun prefetch(start: Long, length: Int): Boolean {
        if (length <= 0 || start < 0 || start >= totalLength) return false
        val size = minOf(length.toLong(), totalLength - start).toInt()
        if (size <= 0) return false
        if (cached(start, size) != null) return false
        val bytes = reader.read(start, size)
        if (bytes.isEmpty()) return false
        remember(start, bytes)
        return true
    }

    private fun cached(start: Long, length: Int): ByteArray? {
        synchronized(spans) {
            for (span in spans) {
                if (start >= span.start && start + length <= span.start + span.bytes.size) {
                    val from = (start - span.start).toInt()
                    return span.bytes.copyOfRange(from, from + length)
                }
            }
        }
        return null
    }

    private fun remember(start: Long, bytes: ByteArray) {
        synchronized(spans) {
            if (spans.size >= MAX_SPANS) spanBytes -= spans.removeAt(0).bytes.size
            spans.add(Span(start, bytes))
            spanBytes += bytes.size
            while (spans.size > 1 && spanBytes > MAX_SPAN_BYTES) {
                spanBytes -= spans.removeAt(0).bytes.size
            }
        }
    }

    private class Span(val start: Long, val bytes: ByteArray)

    private fun parseAllocation(
        partitionRef: Int,
        raw: ByteArray,
        offset: Int,
        length: Int,
        adType: Int,
        resolver: (Int, Long) -> Long,
    ): EntryData {
        if (length == 0) return EntryData.Extents(emptyList())
        if (offset + length > raw.size) throw IsoFormatException("allocation descriptor out of range")
        return when (adType) {
            ICB_AD_SHORT -> EntryData.Extents(parseShortAd(partitionRef, raw, offset, length, resolver))
            ICB_AD_LONG -> EntryData.Extents(parseLongAd(raw, offset, length, resolver))
            ICB_AD_EMBEDDED -> EntryData.Embedded(raw.copyOfRange(offset, offset + length))
            else -> throw IsoFormatException("unsupported allocation type: $adType")
        }
    }

    private fun parseShortAd(
        partitionRef: Int,
        raw: ByteArray,
        offset: Int,
        length: Int,
        resolver: (Int, Long) -> Long,
    ): List<IsoExtent> {
        val extents = ArrayList<IsoExtent>()
        var cursor = offset
        val end = offset + length
        while (cursor + 8 <= end) {
            val rawLength = le32(raw, cursor)
            val extentType = ((rawLength shr 30) and EXTENT_TYPE_MASK.toLong()).toInt()
            val extentLength = rawLength and EXTENT_LENGTH_MASK.toLong()
            if (extentType != 0 && extentLength > 0) {
                throw IsoFormatException("sparse extent type=$extentType len=$extentLength ad=short")
            }
            if (extentLength > 0) {
                extents.add(IsoExtent(resolver(partitionRef, le32(raw, cursor + 4)), extentLength))
            }
            cursor += 8
        }
        return extents
    }

    private fun parseLongAd(
        raw: ByteArray,
        offset: Int,
        length: Int,
        resolver: (Int, Long) -> Long,
    ): List<IsoExtent> {
        val extents = ArrayList<IsoExtent>()
        var cursor = offset
        val end = offset + length
        while (cursor + 16 <= end) {
            val rawLength = le32(raw, cursor)
            val extentType = ((rawLength shr 30) and EXTENT_TYPE_MASK.toLong()).toInt()
            val extentLength = rawLength and EXTENT_LENGTH_MASK.toLong()
            if (extentType != 0 && extentLength > 0) {
                throw IsoFormatException("sparse extent type=$extentType len=$extentLength ad=long")
            }
            if (extentLength > 0) {
                val block = le32(raw, cursor + 4)
                val ref = le16(raw, cursor + 8)
                extents.add(IsoExtent(resolver(ref, block), extentLength))
            }
            cursor += 16
        }
        return extents
    }

    private companion object {
        const val MAX_FILE_ENTRY_BLOCKS = 4
        const val PREFETCH_BYTES = 128 * 1024
        const val MAX_SPANS = 32
        const val MAX_SPAN_BYTES = 8L * 1024L * 1024L
    }
}
