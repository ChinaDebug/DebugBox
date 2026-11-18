package com.tvbox.kotlin.core.player.iso

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class UdfVolume private constructor(
    private val parser: UdfParser,
    private val blockSize: Int,
    private val partitions: List<Partition>,
    private val root: IsoEntry,
) : IsoFileSource {

    private val resolver: (Int, Long) -> Long = { ref, block ->
        resolveBlock(partitions, blockSize, ref, block)
    }

    override fun root(): IsoEntry = root

    override fun list(directory: IsoEntry): List<IsoEntry> {
        if (!directory.directory) {
            throw IsoFormatException("not a directory: ${directory.name}")
        }
        return parseDirectory(readEntry(directory)).map { readChild(it) }
    }

    override fun find(directory: IsoEntry, name: String): IsoEntry? {
        if (!directory.directory) {
            throw IsoFormatException("not a directory: ${directory.name}")
        }
        val child = parseDirectory(readEntry(directory))
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        return readChild(child)
    }

    private fun readChild(child: FidChild): IsoEntry =
        try {
            readEntry(parser, child.name, child.location, child.directory, resolver)
        } catch (e: IsoFormatException) {
            throw IsoFormatException(
                "${e.message} entry=${child.name} block=${child.location.block} ref=${child.location.partitionRef}",
            )
        }

    internal fun listAvailable(directory: IsoEntry): IsoListing {
        if (!directory.directory) return IsoListing(emptyList(), 0)
        val children = parseDirectory(readEntry(directory))
        val entries = ArrayList<IsoEntry>(children.size)
        var skipped = 0
        for (child in children) {
            val entry = try {
                readChild(child)
            } catch (e: IsoFormatException) {
                skipped++
                continue
            }
            entries.add(entry)
        }
        return IsoListing(entries, skipped)
    }

    private fun parseDirectory(data: ByteArray): List<FidChild> {
        val children = ArrayList<FidChild>()
        var offset = 0
        while (offset + FID_HEADER_SIZE <= data.size) {
            if (le16(data, offset) != TAG_FILE_IDENTIFIER) {
                offset = nextDirectoryBlock(data, offset)
                if (offset < 0) break
                continue
            }
            val characteristics = data[offset + FID_CHARACTERISTICS_OFFSET].toInt() and 0xFF
            val nameLength = data[offset + FID_NAME_LENGTH_OFFSET].toInt() and 0xFF
            val implUseLength = le16(data, offset + FID_IMPL_USE_LENGTH_OFFSET)
            val recordLength = FID_HEADER_SIZE + implUseLength + nameLength
            if (offset + recordLength > data.size) break
            if (characteristics and FID_DELETED == 0 && characteristics and FID_PARENT == 0) {
                val child = FileLocation(
                    le32(data, offset + FID_ICB_OFFSET + 4),
                    le16(data, offset + FID_ICB_OFFSET + 8),
                )
                val nameOffset = offset + FID_HEADER_SIZE + implUseLength
                val name = decodeDString(data, nameOffset, nameLength)
                if (name.isNotEmpty()) {
                    children.add(
                        FidChild(name, child, characteristics and FID_DIRECTORY != 0),
                    )
                }
            }
            offset += align4(recordLength)
        }
        return children
    }

    private fun nextDirectoryBlock(data: ByteArray, offset: Int): Int {
        val next = alignUp(offset, blockSize)
        if (next <= offset || next + FID_HEADER_SIZE > data.size) return -1
        return if (le16(data, next) == TAG_FILE_IDENTIFIER) next else -1
    }

    internal fun readImageRange(offset: Long, length: Int): ByteArray =
        parser.readRange(offset, length)

    override fun readEntry(entry: IsoEntry): ByteArray =
        when (val data = entry.data) {
            is EntryData.Embedded -> data.bytes
            is EntryData.Extents -> {
                val total = data.extents.sumOf { it.length }
                if (total > MAX_IN_MEMORY_DATA) {
                    throw IsoFormatException("entry too large: ${entry.name}")
                }
                val bytes = ByteArray(total.toInt())
                var written = 0
                for (extent in data.extents) {
                    if (extent.length == 0L) continue
                    val chunk = parser.readRange(extent.offset, extent.length.toInt())
                    System.arraycopy(chunk, 0, bytes, written, chunk.size)
                    written += chunk.size
                }
                bytes
            }
        }

    override fun prefetch(offset: Long, length: Int): Boolean = parser.prefetch(offset, length)

    private class FidChild(
        val name: String,
        val location: FileLocation,
        val directory: Boolean,
    )

    companion object {
        fun open(reader: RangeBlockReader, totalLength: Long): UdfVolume =
            Builder(reader, totalLength).build()
    }

    private class Builder(
        private val reader: RangeBlockReader,
        private val totalLength: Long,
    ) {
        private val blockSize = SECTOR_SIZE
        private val fileEntryCache = ConcurrentHashMap<FileLocation, ParsedFileEntry>()
        private val parser = UdfParser(reader, blockSize, totalLength, fileEntryCache)
        private val physicalMapIndexes = HashMap<Int, Int>()
        private val physicalByMapIndex = HashMap<Int, Partition.Physical>()
        private lateinit var partitions: List<Partition>

        fun build(): UdfVolume {
            readVolumeRecognition()
            val anchor = readAnchor()
            val descriptors = readVolumeDescriptors(anchor)
            val volume = descriptors.logical
                ?: throw IsoFormatException("logical volume descriptor missing")
            if (volume.blockSize != SECTOR_SIZE) {
                throw IsoFormatException("unsupported block size: ${volume.blockSize}")
            }
            partitions = buildPartitions(volume, descriptors.partitions)
            prefetchMetadata()
            val fileSetLocation = volume.fileSetLocation
            val fileSet = parser.readPartitionBytes(
                fileSetLocation.partitionRef,
                fileSetLocation.block,
                blockSize,
                ::resolveOffset,
            )
            if (le16(fileSet, 0) != TAG_FILE_SET) {
                throw IsoFormatException("file set descriptor tag")
            }
            val rootLocation = FileLocation(
                le32(fileSet, FSD_ROOT_EXTENT_OFFSET + 4),
                le16(fileSet, FSD_ROOT_EXTENT_OFFSET + 8),
            )
            val root = readEntry("", rootLocation, true)
            return UdfVolume(parser, blockSize, partitions, root)
        }

        private fun buildPartitions(
            volume: LogicalVolume,
            descriptors: Map<Int, PartitionDescriptor>,
        ): List<Partition> {
            val resolved = arrayOfNulls<Partition>(volume.maps.size)
            for ((index, map) in volume.maps.withIndex()) {
                if (map !is PartitionMap.Physical) continue
                val descriptor = descriptors[map.partitionNumber]
                    ?: throw IsoFormatException("partition descriptor missing: ${map.partitionNumber}")
                val partition = Partition.Physical(descriptor.startBlock)
                physicalMapIndexes[map.partitionNumber] = index
                physicalByMapIndex[index] = partition
                resolved[index] = partition
            }
            for ((index, map) in volume.maps.withIndex()) {
                if (map !is PartitionMap.Metadata) continue
                val parentIndex = physicalMapIndexes[map.partitionNumber]
                    ?: throw IsoFormatException("metadata parent missing: ${map.partitionNumber}")
                val metadataEntry = parser.readFileEntry(
                    FileLocation(map.metadataFileLocation, parentIndex),
                    ::physicalResolver,
                )
                val extents = metadataEntry.extents
                if (extents.isEmpty()) throw IsoFormatException("metadata file empty")
                resolved[index] = Partition.Metadata(extents)
            }
            return resolved.map { it ?: throw IsoFormatException("partition map unresolved") }
        }

        private fun prefetchMetadata() {
            for (partition in partitions) {
                if (partition !is Partition.Metadata) continue
                for (extent in partition.extents) {
                    if (extent.length <= 0 || extent.length > MAX_PREFETCH_BYTES) continue
                    parser.prefetch(extent.offset, extent.length.toInt())
                }
            }
        }

        private fun physicalResolver(partitionRef: Int, block: Long): Long {
            val partition = physicalByMapIndex[partitionRef]
                ?: throw IsoFormatException("partition not ready: $partitionRef")
            return (partition.startBlock + block) * blockSize
        }

        private fun readEntry(name: String, location: FileLocation, directoryHint: Boolean?): IsoEntry =
            readEntry(parser, name, location, directoryHint, ::resolveOffset)

        private fun resolveOffset(partitionRef: Int, block: Long): Long =
            resolveBlock(partitions, blockSize, partitionRef, block)

        private fun readVolumeRecognition(): String {
            if (totalLength < 32L * SECTOR_SIZE) throw IsoFormatException("image too small")
            val start = VRS_FIRST_SECTOR * SECTOR_SIZE
            val prefetch = minOf(VRS_PREFETCH_BYTES, totalLength - start).toInt()
            val data = parser.readRange(start, prefetch)
            var version: String? = null
            for (index in 0 until VRS_SECTORS) {
                val sector = index * SECTOR_SIZE
                if (isBlankSector(data, sector)) break
                val id = String(data, sector + VRS_TAG_OFFSET, VRS_TAG_LENGTH, StandardCharsets.US_ASCII)
                when (id) {
                    VSD_BEGIN -> Unit
                    VSD_END -> break
                    UDF_NSR02, UDF_NSR03 -> version = id
                    ISO_DESCRIPTOR -> Unit
                    else -> break
                }
            }
            return version ?: throw IsoFormatException("volume recognition missing")
        }

        private fun isBlankSector(data: ByteArray, offset: Int): Boolean {
            if (offset + SECTOR_SIZE > data.size) return true
            for (index in offset until offset + SECTOR_SIZE) {
                if (data[index].toInt() != 0) return false
            }
            return true
        }

        private fun readAnchor(): Anchor {
            val lastSector = totalLength / SECTOR_SIZE - 1
            for (sector in listOf(256L, lastSector, lastSector - 256).distinct()) {
                if (sector < 0 || (sector + 1) * SECTOR_SIZE > totalLength) continue
                val data = parser.readRange(sector * SECTOR_SIZE, 512)
                if (le16(data, 0) != TAG_ANCHOR) continue
                val mainLength = le32(data, AVDP_MAIN_EXTENT_OFFSET)
                val mainLocation = le32(data, AVDP_MAIN_EXTENT_OFFSET + 4)
                if (mainLength >= 512 && mainLocation >= 0) return Anchor(mainLocation, mainLength)
                val reserveLength = le32(data, AVDP_MAIN_EXTENT_OFFSET + 8)
                val reserveLocation = le32(data, AVDP_MAIN_EXTENT_OFFSET + 12)
                if (reserveLength >= 512 && reserveLocation >= 0) {
                    return Anchor(reserveLocation, reserveLength)
                }
            }
            throw IsoFormatException("anchor not found")
        }

        private fun readVolumeDescriptors(anchor: Anchor): VolumeDescriptors {
            val partitionDescriptors = HashMap<Int, PartitionDescriptor>()
            var logical: LogicalVolume? = null
            var location = anchor.mainLocation
            var remaining = anchor.mainLength
            while (remaining >= SECTOR_SIZE) {
                val chunk = minOf(remaining, MAX_DESCRIPTOR_CHUNK.toLong()).toInt()
                val data = parser.readRange(location * SECTOR_SIZE, chunk)
                var offset = 0
                while (offset + 512 <= data.size) {
                    when (le16(data, offset)) {
                        TAG_PARTITION -> {
                            val number = le16(data, offset + PD_NUMBER_OFFSET)
                            partitionDescriptors[number] = PartitionDescriptor(
                                number,
                                le32(data, offset + PD_START_OFFSET),
                                le32(data, offset + PD_LENGTH_OFFSET),
                            )
                        }
                        TAG_LOGICAL_VOLUME -> logical = parseLogicalVolume(data, offset)
                        TAG_TERMINATING -> {
                            if (logical == null) throw IsoFormatException("logical volume descriptor missing")
                            return VolumeDescriptors(logical, partitionDescriptors)
                        }
                    }
                    offset += SECTOR_SIZE
                }
                val consumed = chunk / SECTOR_SIZE * SECTOR_SIZE
                location += consumed / SECTOR_SIZE
                remaining -= consumed
            }
            if (logical == null) throw IsoFormatException("logical volume descriptor missing")
            return VolumeDescriptors(logical, partitionDescriptors)
        }

        private fun parseLogicalVolume(data: ByteArray, offset: Int): LogicalVolume {
            val blockSize = le32(data, offset + LVD_BLOCK_SIZE_OFFSET).toInt()
            val fileSet = FileLocation(
                le32(data, offset + LVD_FILE_SET_EXTENT_OFFSET + 4),
                le16(data, offset + LVD_FILE_SET_EXTENT_OFFSET + 8),
            )
            val mapCount = le32(data, offset + LVD_MAP_COUNT_OFFSET).toInt()
            if (mapCount <= 0 || mapCount > MAX_PARTITION_MAPS) {
                throw IsoFormatException("partition map count: $mapCount")
            }
            return LogicalVolume(blockSize, fileSet, parsePartitionMaps(data, offset, mapCount))
        }

        private fun parsePartitionMaps(data: ByteArray, base: Int, count: Int): List<PartitionMap> {
            val maps = ArrayList<PartitionMap>(count)
            var offset = base + PARTITION_MAP_START
            repeat(count) {
                if (offset + 2 > data.size) throw IsoFormatException("partition map truncated")
                val type = data[offset].toInt() and 0xFF
                val length = data[offset + 1].toInt() and 0xFF
                if (length < 2 || offset + length > data.size) {
                    throw IsoFormatException("partition map length: $length")
                }
                when (type) {
                    PARTITION_MAP_PHYSICAL -> {
                        if (length < 6) throw IsoFormatException("physical map length: $length")
                        maps.add(PartitionMap.Physical(le16(data, offset + 4)))
                    }
                    PARTITION_MAP_METADATA -> {
                        if (length < METADATA_MAP_MIN_LENGTH) {
                            throw IsoFormatException("metadata map length: $length")
                        }
                        val identifier = String(
                            data,
                            offset + 5,
                            METADATA_IDENTIFIER.length,
                            StandardCharsets.US_ASCII,
                        )
                        if (identifier != METADATA_IDENTIFIER) {
                            throw IsoFormatException("metadata identifier: $identifier")
                        }
                        maps.add(
                            PartitionMap.Metadata(
                                le16(data, offset + 38),
                                le32(data, offset + 40),
                            ),
                        )
                    }
                    else -> throw IsoFormatException("unsupported partition map: $type")
                }
                offset += length
            }
            return maps
        }
    }
}

private fun resolveBlock(
    partitions: List<Partition>,
    blockSize: Int,
    partitionRef: Int,
    block: Long,
): Long {
    val partition = partitions.getOrNull(partitionRef)
        ?: throw IsoFormatException("partition ref out of range: $partitionRef")
    return when (partition) {
        is Partition.Physical -> (partition.startBlock + block) * blockSize
        is Partition.Metadata -> {
            val position = block * blockSize
            var remaining = position
            for (extent in partition.extents) {
                if (remaining < extent.length) return extent.offset + remaining
                remaining -= extent.length
            }
            throw IsoFormatException("metadata block out of range: $block")
        }
    }
}

private fun readEntry(
    parser: UdfParser,
    name: String,
    location: FileLocation,
    directoryHint: Boolean?,
    resolver: (Int, Long) -> Long,
): IsoEntry {
    val parsed = parser.readFileEntry(location, resolver)
    val directory = directoryHint ?: (parsed.fileType == FILE_TYPE_DIRECTORY)
    return IsoEntry(name, directory, parsed.length, parsed.extents, parsed.data)
}

private const val MAX_DESCRIPTOR_CHUNK = 64 * 1024
private const val MAX_PREFETCH_BYTES = 64L * 1024L
private const val MAX_PARTITION_MAPS = 64
private const val METADATA_MAP_MIN_LENGTH = 58
private const val VRS_FIRST_SECTOR = 16L
private const val VRS_SECTORS = 16
private const val VRS_PREFETCH_BYTES = 512L * 1024L
private const val VRS_TAG_OFFSET = 1
private const val VRS_TAG_LENGTH = 5
private const val VSD_BEGIN = "BEA01"
private const val VSD_END = "TEA01"
private const val UDF_NSR02 = "NSR02"
private const val UDF_NSR03 = "NSR03"
private const val ISO_DESCRIPTOR = "CD001"

private class VolumeDescriptors(
    val logical: LogicalVolume?,
    val partitions: Map<Int, PartitionDescriptor>,
)
