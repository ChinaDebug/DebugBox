package com.tvbox.kotlin.core.player.iso

internal const val SECTOR_SIZE = 2048
internal const val TAG_ANCHOR = 2
internal const val TAG_PRIMARY_VOLUME = 1
internal const val TAG_PARTITION = 5
internal const val TAG_LOGICAL_VOLUME = 6
internal const val TAG_TERMINATING = 8
internal const val TAG_FILE_SET = 256
internal const val TAG_FILE_IDENTIFIER = 257
internal const val TAG_FILE_ENTRY = 261
internal const val TAG_EXTENDED_FILE_ENTRY = 266

internal const val FILE_TYPE_DIRECTORY = 4
internal const val FILE_TYPE_REGULAR = 5

internal const val ICB_AD_SHORT = 0
internal const val ICB_AD_LONG = 1
internal const val ICB_AD_EMBEDDED = 3

internal const val EXTENT_TYPE_MASK = 0x3
internal const val EXTENT_LENGTH_MASK = 0x3FFFFFFF

internal const val FID_HEADER_SIZE = 38
internal const val FID_CHARACTERISTICS_OFFSET = 18
internal const val FID_NAME_LENGTH_OFFSET = 19
internal const val FID_ICB_OFFSET = 20
internal const val FID_IMPL_USE_LENGTH_OFFSET = 36
internal const val FILE_TYPE_OFFSET = 27
internal const val ICB_FLAGS_OFFSET = 34
internal const val FILE_INFO_LENGTH_OFFSET = 56
internal const val FILE_ENTRY_LENGTH_OFFSET = 168
internal const val FILE_ENTRY_DATA_OFFSET = 176
internal const val EXTENDED_LENGTH_OFFSET = 208
internal const val EXTENDED_DATA_OFFSET = 216
internal const val FID_DIRECTORY = 0x02
internal const val FID_DELETED = 0x04
internal const val FID_PARENT = 0x08
internal const val MAX_IN_MEMORY_DATA = 8 * 1024 * 1024

internal const val PARTITION_MAP_PHYSICAL = 1
internal const val PARTITION_MAP_METADATA = 2
internal const val PARTITION_MAP_START = 440
internal const val METADATA_IDENTIFIER = "*UDF Metadata Partition"
internal const val AVDP_MAIN_EXTENT_OFFSET = 16
internal const val PD_NUMBER_OFFSET = 22
internal const val PD_START_OFFSET = 188
internal const val PD_LENGTH_OFFSET = 192
internal const val LVD_BLOCK_SIZE_OFFSET = 212
internal const val LVD_FILE_SET_EXTENT_OFFSET = 248
internal const val LVD_MAP_COUNT_OFFSET = 268
internal const val FSD_ROOT_EXTENT_OFFSET = 400

internal data class FileLocation(val block: Long, val partitionRef: Int)

data class IsoExtent(val offset: Long, val length: Long)

internal sealed class EntryData {
    class Extents(val extents: List<IsoExtent>) : EntryData()

    class Embedded(val bytes: ByteArray) : EntryData()
}

class IsoEntry internal constructor(
    val name: String,
    val directory: Boolean,
    val length: Long,
    val extents: List<IsoExtent>,
    internal val data: EntryData,
)

internal class IsoListing(val entries: List<IsoEntry>, val skipped: Int)

internal sealed class Partition {
    class Physical(val startBlock: Long) : Partition()

    class Metadata(val extents: List<IsoExtent>) : Partition()
}

internal data class PartitionDescriptor(val number: Int, val startBlock: Long, val length: Long)

internal sealed class PartitionMap {
    data class Physical(val partitionNumber: Int) : PartitionMap()

    data class Metadata(val partitionNumber: Int, val metadataFileLocation: Long) : PartitionMap()
}

internal data class Anchor(val mainLocation: Long, val mainLength: Long)

internal class LogicalVolume(
    val blockSize: Int,
    val fileSetLocation: FileLocation,
    val maps: List<PartitionMap>,
)

internal class ParsedFileEntry(
    val fileType: Int,
    val length: Long,
    val extents: List<IsoExtent>,
    val data: EntryData,
)

internal fun le16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun le32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

internal fun le64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
    }
    return value
}

internal fun align4(value: Int): Int = (value + 3) and 3.inv()

internal fun alignUp(value: Int, unit: Int): Int = ((value + unit - 1) / unit) * unit

internal fun decodeDString(bytes: ByteArray, offset: Int, length: Int): String {
    if (length <= 0) return ""
    if (offset + length > bytes.size) throw IsoFormatException("dstring out of range")
    return when (val compression = bytes[offset].toInt() and 0xFF) {
        8 -> {
            val chars = CharArray(length - 1)
            for (index in 1 until length) {
                chars[index - 1] = (bytes[offset + index].toInt() and 0xFF).toChar()
            }
            String(chars)
        }
        16 -> {
            val count = (length - 1) / 2
            val chars = CharArray(count)
            for (index in 0 until count) {
                val high = bytes[offset + 1 + index * 2].toInt() and 0xFF
                val low = bytes[offset + 2 + index * 2].toInt() and 0xFF
                chars[index] = ((high shl 8) or low).toChar()
            }
            String(chars)
        }
        else -> throw IsoFormatException("unsupported dstring compression: $compression")
    }
}
