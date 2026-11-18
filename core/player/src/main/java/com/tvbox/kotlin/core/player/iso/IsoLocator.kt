package com.tvbox.kotlin.core.player.iso

import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class IsoPlayRun(val offset: Long, val length: Long)

data class IsoPlayTarget(
    val runs: List<IsoPlayRun>,
    val m2tsLength: Long,
    val name: String,
    val startOffset: Long = 0L,
    val durationMs: Long = 0L,
    val playlistRuns: List<IsoPlayRun>? = null,
    val playlistOffset: Long = 0L,
    val playlistLength: Long = 0L,
) {
    constructor(
        m2tsOffset: Long,
        m2tsLength: Long,
        name: String,
        startOffset: Long = 0L,
        durationMs: Long = 0L,
    ) : this(listOf(IsoPlayRun(m2tsOffset, m2tsLength)), m2tsLength, name, startOffset, durationMs)

    val m2tsOffset: Long get() = runs.firstOrNull()?.offset ?: 0L

    val segmentLength: Long get() = (m2tsLength - startOffset).coerceAtLeast(0L)

    val availableLength: Long get() =
        if (playlistRuns != null) (playlistLength - playlistOffset - startOffset).coerceAtLeast(0L)
        else segmentLength
}

internal fun coalesceRuns(extents: List<IsoExtent>): List<IsoPlayRun> {
    if (extents.isEmpty()) return emptyList()
    val runs = ArrayList<IsoPlayRun>(extents.size)
    var current: IsoPlayRun? = null
    for (extent in extents) {
        if (extent.length <= 0L) continue
        val open = current
        current = if (open != null && open.offset + open.length == extent.offset) {
            IsoPlayRun(open.offset, open.length + extent.length)
        } else {
            if (open != null) runs.add(open)
            IsoPlayRun(extent.offset, extent.length)
        }
    }
    current?.let { runs.add(it) }
    return runs
}

data class IsoSelection(
    val segments: List<IsoPlayTarget>,
    val playlistName: String?,
    val durationMs: Long,
    val baseMs: Long = 0L,
) {
    val primary: IsoPlayTarget get() = segments.first()

    val totalLength: Long get() = segments.sumOf { it.segmentLength }

    val multiSegment: Boolean get() = segments.size > 1
}

internal fun IsoSelection.withPlaylistTargets(): List<IsoPlayTarget> {
    val runs = segments.flatMap { it.runs }
    val total = runs.sumOf { it.length }
    var offset = 0L
    return segments.map { segment ->
        val copy = segment.copy(
            playlistRuns = runs,
            playlistOffset = offset,
            playlistLength = total,
        )
        offset += segment.runs.sumOf { it.length }
        copy
    }
}

internal fun matchPlaylistSegments(
    playlist: IsoPlaylist,
    targets: List<IsoPlayTarget>,
): List<IsoPlayTarget>? {
    if (playlist.segments.isEmpty()) return null
    val byName = HashMap<String, IsoPlayTarget>(targets.size)
    for (target in targets) byName[target.name.lowercase(Locale.ROOT)] = target
    val ordered = ArrayList<IsoPlayTarget>(playlist.segments.size)
    for (segment in playlist.segments) {
        val name = "${segment.clipId}.m2ts".lowercase(Locale.ROOT)
        val target = byName[name] ?: return null
        val duration = segment.durationMs
        ordered.add(if (duration > 0L) target.copy(durationMs = duration) else target)
    }
    return ordered
}

internal fun alignSelectionToAccessPoint(
    segments: List<IsoPlayTarget>,
    totalDurationMs: Long,
    playlistName: String?,
    seekMs: Long,
    accessPoints: (String) -> ClpiIndex?,
): IsoSelection? {
    if (segments.isEmpty() || seekMs <= 0L) return null
    var start = 0L
    for (index in segments.indices) {
        val segment = segments[index]
        val duration = segment.durationMs
        if (duration <= 0L) return null
        val end = start + duration
        if (seekMs < end || index == segments.lastIndex) {
            val clipId = segment.name.substringBeforeLast('.').lowercase(Locale.ROOT)
            val clip = accessPoints(clipId) ?: return null
            val first = clip.accessPoints.firstOrNull() ?: return null
            val local = (seekMs - start).coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
            val targetTicks = first.ptsTicks + local * CLPI_TICKS_PER_MILLI
            val point = clip.accessPointAt(targetTicks) ?: first
            val delta = ((point.ptsTicks - first.ptsTicks) / CLPI_TICKS_PER_MILLI).coerceAtLeast(0L)
            val head = segment.copy(
                startOffset = point.packetOffset,
                durationMs = (duration - delta).coerceAtLeast(0L),
            )
            return IsoSelection(
                listOf(head) + segments.drop(index + 1),
                playlistName,
                totalDurationMs,
                start + delta,
            )
        }
        start = end
    }
    return null
}

class IsoLocator(private val volume: IsoFileSource) {

    private val clipIndexes = ConcurrentHashMap<String, ClpiIndex>()

    private val clipLocks = ConcurrentHashMap<String, Any>()

    internal var selectedStreams: List<IsoStream> = emptyList()
        private set

    internal var selectedVideoPid: Int? = null
        private set

    private val bdmvEntry: IsoEntry by lazy { locateBdmv() }

    fun locate(): IsoPlayTarget = locateSelection().primary

    fun locateSelection(): IsoSelection {
        val bdmv = bdmvEntry
        if (!bdmv.directory) throw IsoFormatException("BDMV is not a directory")
        val stream = volume.find(bdmv, STREAM)
            ?: throw IsoFormatException("BDMV/$STREAM directory missing")
        if (!stream.directory) throw IsoFormatException("$STREAM is not a directory")
        val candidates = volume.list(stream).filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(M2TS) }
        if (candidates.isEmpty()) throw IsoFormatException("no m2ts file found")
        val largest = candidates.maxByOrNull { it.length }
            ?: throw IsoFormatException("no m2ts file found")
        val targets = ArrayList<IsoPlayTarget>(candidates.size)
        for (candidate in candidates) {
            val target = toTarget(candidate) ?: continue
            targets.add(target)
        }
        val fallback = targets.firstOrNull { it.name == largest.name }
            ?: toTarget(largest)
            ?: throw IsoFormatException("m2ts has no extents: ${largest.name}")
        val playlists = probePlaylists(bdmv)
        val chosen = pickLongestPlaylist(playlists)
        if (chosen != null) {
            val segments = matchPlaylistSegments(chosen, targets)
            if (segments != null) {
                selectedStreams = chosen.streams
                selectedVideoPid = chosen.videoPid
                return IsoSelection(segments, chosen.name, chosen.durationMs)
            }
        }
        selectedStreams = emptyList()
        selectedVideoPid = null
        return IsoSelection(listOf(fallback), null, 0L)
    }

    fun locateSeek(selection: IsoSelection, seekMs: Long): IsoSelection? =
        alignSelectionToAccessPoint(
            selection.segments,
            selection.durationMs,
            selection.playlistName,
            seekMs,
        ) { clipId -> accessPoints(clipId) }

    internal fun accessPoints(clipId: String): ClpiIndex? {
        val key = clipId.lowercase(Locale.ROOT)
        clipIndexes[key]?.let { return it }
        val fresh = Any()
        val lock = clipLocks.putIfAbsent(key, fresh) ?: fresh
        return synchronized(lock) {
            clipIndexes[key]?.let { return@synchronized it }
            val index = loadClipIndex(key) ?: return@synchronized null
            clipIndexes[key] = index
            index
        }
    }

    private fun loadClipIndex(key: String): ClpiIndex? {
        val bdmv = try {
            bdmvEntry
        } catch (e: IsoFormatException) {
            return null
        }
        val dir = volume.find(bdmv, CLIPINF) ?: return null
        if (!dir.directory) return null
        val want = "$key$CLPI"
        val entry = volume.list(dir).firstOrNull { it.name.lowercase(Locale.ROOT) == want } ?: return null
        val data = try {
            volume.readEntry(entry)
        } catch (e: IsoFormatException) {
            return null
        }
        return parseClpi(entry.name, data)
    }

    private fun locateBdmv(): IsoEntry {
        val root = volume.root()
        val direct = volume.find(root, BDMV)
        if (direct != null) return direct
        val children = volume.list(root)
        for (child in children) {
            if (!child.directory) continue
            val nested = try {
                volume.find(child, BDMV)
            } catch (e: IsoFormatException) {
                continue
            }
            if (nested != null) return nested
        }
        throw IsoFormatException("BDMV directory missing")
    }

    private fun toTarget(entry: IsoEntry): IsoPlayTarget? {
        if (entry.length <= 0) return null
        val runs = coalesceRuns(entry.extents)
        if (runs.isEmpty()) return null
        var total = 0L
        for (run in runs) total += run.length
        if (total < entry.length) return null
        if (runs.size > MAX_TARGET_RUNS) return null
        return IsoPlayTarget(runs, entry.length, entry.name)
    }

    private fun probePlaylists(bdmv: IsoEntry): List<IsoPlaylist> {
        val playlistDir = volume.find(bdmv, PLAYLIST)
        if (playlistDir == null || !playlistDir.directory) return emptyList()
        val all = volume.list(playlistDir)
            .filter { !it.directory && it.name.lowercase(Locale.ROOT).endsWith(MPLS) }
            .sortedByDescending { it.length }
        val entries = if (all.size <= FULL_SCAN_LIMIT) all else {
            val preferred = all.filter { it.name.lowercase(Locale.ROOT) in MAIN_PLAYLISTS }
            (preferred + all.take(MAX_PLAYLISTS)).distinctBy { it.name }
        }
        if (entries.isEmpty()) return emptyList()
        val fastTargets = entries.filter { it.name.lowercase(Locale.ROOT) in MAIN_PLAYLISTS }
        val covered = HashSet<String>()
        if (fastTargets.isNotEmpty() && fastTargets.size < entries.size) {
            if (prefetchPlaylists(fastTargets)) covered += fastTargets.map { it.name }
            val fastPayloads = IsoMplsFetch.load(fastTargets) { entry ->
                IsoMplRead(parseMplPlaylist(entry.name, volume.readEntry(entry)))
            }
            val fastParsed = fastTargets.indices.mapNotNull { fastPayloads[it]?.playlist }
            val fastBest = pickLongestPlaylist(fastParsed)
            if (fastBest != null && uniqueDurationMs(fastBest) >= MIN_MAIN_DURATION_MS) {
                return fastParsed
            }
        }
        val parsed = ArrayList<IsoPlaylist>(entries.size)
        prefetchPlaylists(mplPrefetchCandidates(entries, covered))
        val payloads = IsoMplsFetch.load(entries) { entry ->
            IsoMplRead(parseMplPlaylist(entry.name, volume.readEntry(entry)))
        }
        for (index in entries.indices) {
            parsed.add(payloads[index]?.playlist ?: continue)
        }
        parsed.sortByDescending { uniqueDurationMs(it) }
        return parsed
    }

    private fun prefetchPlaylists(entries: List<IsoEntry>): Boolean {
        if (entries.isEmpty()) return false
        val plan = mplPrefetchPlan(entries.flatMap { it.extents })
        if (plan.windows.isEmpty()) return false
        var loaded = 0
        var expired = 0
        for (window in plan.windows) {
            val result = IsoMplsFetch.runWithBudget(PREFETCH_BUDGET_MS) {
                try {
                    volume.prefetch(window.offset, window.length)
                } catch (e: Exception) {
                    false
                }
            }
            when (result) {
                null -> expired++
                true -> loaded++
                false -> Unit
            }
            if (expired > 0) break
        }
        return loaded == plan.windows.size
    }

    private companion object {
        const val BDMV = "BDMV"
        const val STREAM = "STREAM"
        const val PLAYLIST = "PLAYLIST"
        const val M2TS = ".m2ts"
        const val MPLS = ".mpls"
        const val CLIPINF = "CLIPINF"
        const val CLPI = ".clpi"
        const val MAX_TARGET_RUNS = 256
        const val PREFETCH_BUDGET_MS = 3_000L
    }
}

internal class IsoMplRead(val playlist: IsoPlaylist?)

internal object IsoMplsFetch {

    private const val THREADS = 1
    private const val TIMEOUT_MS = 12_000L

    private val ordinal = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(THREADS) { runnable ->
        Thread(runnable, "iso-mpls-${ordinal.incrementAndGet()}").apply { isDaemon = true }
    }

    private val prefetchExecutor = Executors.newFixedThreadPool(1) { runnable ->
        Thread(runnable, "iso-mpls-prefetch-${ordinal.incrementAndGet()}").apply { isDaemon = true }
    }

    fun <T> runWithBudget(budgetMs: Long, block: () -> T): T? {
        val task = FutureTask(Callable { block() })
        prefetchExecutor.execute(task)
        return try {
            task.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(false)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(false)
            null
        }
    }

    fun load(entries: List<IsoEntry>, loader: (IsoEntry) -> IsoMplRead): Array<IsoMplRead?> {
        val results = arrayOfNulls<IsoMplRead>(entries.size)
        if (entries.size <= 1 || entries.none { it.data is EntryData.Extents }) {
            for (index in entries.indices) {
                results[index] = loadOne(entries[index], loader)
            }
            return results
        }
        val tasks = entries.map { entry -> Callable { loader(entry) } }
        val futures = try {
            executor.invokeAll(tasks, TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return serialLoad(entries, loader, results, 0)
        }
        var head = 0
        for (index in futures.indices) {
            val future = futures[index]
            if (future.isCancelled) break
            val value = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (value == null) break
            results[index] = value
            head = index + 1
        }
        return if (head == entries.size) results else serialLoad(entries, loader, results, head)
    }

    private fun serialLoad(
        entries: List<IsoEntry>,
        loader: (IsoEntry) -> IsoMplRead,
        results: Array<IsoMplRead?>,
        from: Int,
    ): Array<IsoMplRead?> {
        for (index in from until entries.size) {
            results[index] = loadOne(entries[index], loader)
        }
        return results
    }

    private fun loadOne(entry: IsoEntry, loader: (IsoEntry) -> IsoMplRead): IsoMplRead? =
        try {
            loader(entry)
        } catch (e: Exception) {
            null
        }
}

private const val MAX_PLAYLISTS = 8
private const val FULL_SCAN_LIMIT = 16
private const val MIN_MAIN_DURATION_MS = 600_000L
private val MAIN_PLAYLISTS = setOf("00800.mpls", "00801.mpls")
