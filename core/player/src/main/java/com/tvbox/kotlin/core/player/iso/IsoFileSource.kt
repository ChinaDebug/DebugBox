package com.tvbox.kotlin.core.player.iso


interface IsoFileSource {
    fun root(): IsoEntry
    fun list(directory: IsoEntry): List<IsoEntry>
    fun find(directory: IsoEntry, name: String): IsoEntry?
    fun readEntry(entry: IsoEntry): ByteArray
    fun prefetch(offset: Long, length: Int): Boolean = false
}

internal const val ISO_ROOT_PATH = ""
internal const val ISO_BDMV_PATH = "BDMV"
internal const val ISO_STREAM_PATH = "BDMV/STREAM"
internal const val ISO_PLAYLIST_PATH = "BDMV/PLAYLIST"
internal const val ISO_CLIPINF_PATH = "BDMV/CLIPINF"
