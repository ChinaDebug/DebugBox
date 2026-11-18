package com.tvbox.kotlin.core.player.iso

object IsoPlaylistState {

    @Volatile
    private var baseOffset = 0L

    @Volatile
    private var totalDuration = 0L

    @Volatile
    private var windowDurations: List<Long> = emptyList()

    @Volatile
    private var fullBytes = 0L

    @Volatile
    private var streamLanguages: Map<Int, String> = emptyMap()

    @Volatile
    private var primaryVideoPid = -1

    fun reset() {
        baseOffset = 0L
        totalDuration = 0L
        windowDurations = emptyList()
        fullBytes = 0L
        streamLanguages = emptyMap()
        primaryVideoPid = -1
    }

    fun update(selection: IsoSelection) {
        baseOffset = selection.baseMs
        totalDuration = selection.durationMs
        windowDurations = selection.segments.map { it.durationMs }
        fullBytes = selection.segments.sumOf { it.m2tsLength }
    }

    internal fun publishStreams(streams: List<IsoStream>) {
        if (streams.isEmpty()) return
        val languages = HashMap<Int, String>()
        for (stream in streams) {
            val language = stream.language ?: continue
            if (language.isEmpty()) continue
            if (!languages.containsKey(stream.pid)) languages[stream.pid] = language
        }
        streamLanguages = languages
    }

    internal fun languageForPid(pid: Int): String? = streamLanguages[pid]

    internal fun publishVideoPid(pid: Int?) {
        primaryVideoPid = pid?.takeIf { it > 0 } ?: -1
    }

    internal fun videoPid(): Int? = primaryVideoPid.takeIf { it > 0 }

    fun totalBytes(): Long = fullBytes

    fun durationMs(): Long = totalDuration

    fun baseOffsetMs(): Long = baseOffset

    fun segments(): Int = windowDurations.size

    fun seekable(): Boolean {
        val durations = windowDurations
        if (durations.isEmpty()) return false
        for (duration in durations) {
            if (duration <= 0L) return false
        }
        return true
    }

    fun windowDurationMs(index: Int): Long? = windowDurations.getOrNull(index)

    fun windowStartMs(index: Int): Long? {
        val durations = windowDurations
        if (index < 0 || index >= durations.size) return null
        var start = baseOffset
        for (position in 0 until index) start += durations[position]
        return start
    }
}
