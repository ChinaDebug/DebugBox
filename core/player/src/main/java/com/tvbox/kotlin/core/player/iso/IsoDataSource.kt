package com.tvbox.kotlin.core.player.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tvbox.kotlin.core.player.HttpHeaders
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.net.ProtocolException
import java.util.HashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient

class IsoDataSource internal constructor(
    private val upstreamFactory: DataSource.Factory,
    private val targetProvider: (Uri) -> IsoPlayTarget,
) : BaseDataSource(false) {

    constructor(upstreamFactory: DataSource.Factory) : this(
        upstreamFactory,
        IsoTargetProvider(upstreamFactory, IsoResolver(), 0L, {}).provider(),
    )

    private val closeGuard = IsoCloseGuard()
    private var upstream: DataSource? = null
    private var windows: List<IsoReadWindow> = emptyList()
    private var windowIndex = 0
    private var windowRemaining = 0L
    private var windowDelivered = 0L
    private var requestSpec: DataSpec? = null
    private var bytesRemaining = 0L
    private var dataUri: Uri? = null
    private var targetValue: IsoPlayTarget? = null

    fun target(): IsoPlayTarget? = targetValue

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataUri = dataSpec.uri
        requestSpec = dataSpec
        val target = targetProvider(dataSpec.uri)
        targetValue = target
        val planned = isoReadWindows(target, dataSpec.position, dataSpec.length)
        var total = 0L
        for (window in planned) total += window.length
        if (total == 0L) {
            closeGuard.activate()
            transferStarted(dataSpec)
            return 0
        }
        windows = planned
        windowIndex = 0
        bytesRemaining = total
        closeGuard.activate()
        openWindow(planned.first())
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openWindow(window: IsoReadWindow) {
        val spec = requireNotNull(requestSpec) { "missing data spec" }
        val source = upstreamFactory.createDataSource()
        val upstreamSpec = spec.buildUpon()
            .setPosition(window.isoOffset)
            .setLength(window.length)
            .build()
        val openedLength = try {
            source.open(upstreamSpec)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        upstream = source
        windowRemaining = if (openedLength == C.LENGTH_UNSET.toLong()) {
            window.length
        } else {
            minOf(openedLength, window.length)
        }
        windowDelivered = 0L
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (windowRemaining == 0L && !advanceWindow()) return C.RESULT_END_OF_INPUT
        val source = requireNotNull(upstream) { "not open" }
        val max = minOf(length.toLong(), bytesRemaining, windowRemaining).toInt()
        return try {
            val read = source.read(buffer, offset, max)
            if (read > 0) {
                windowRemaining -= read
                windowDelivered += read
                bytesRemaining -= read
                bytesTransferred(read)
            } else if (windowRemaining > 0L) {
                bytesRemaining = 0
            }
            read
        } catch (e: IOException) {
            if (isUpstreamTruncation(e)) {
                bytesRemaining = 0
                return C.RESULT_END_OF_INPUT
            }
            throw e
        }
    }

    private fun isUpstreamTruncation(e: IOException): Boolean {
        val cause = e.cause ?: return false
        if (cause !is ProtocolException) return false
        return cause.message?.contains("unexpected end of stream") == true
    }

    private fun advanceWindow(): Boolean {
        val current = windows.getOrNull(windowIndex)
        if (current != null && windowDelivered < current.length) {
            bytesRemaining = 0
            return false
        }
        val next = windows.getOrNull(windowIndex + 1) ?: return false
        upstream?.let { runCatching { it.close() } }
        upstream = null
        windowIndex += 1
        openWindow(next)
        return true
    }

    override fun close() {
        closeGuard.close {
            val source = upstream
            upstream = null
            windows = emptyList()
            windowIndex = 0
            windowRemaining = 0L
            windowDelivered = 0L
            bytesRemaining = 0
            try {
                source?.close()
            } finally {
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: dataUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val target: IsoPlayTarget? = null,
        private val onSelection: ((IsoSelection) -> Unit)? = null,
        private val seekMs: Long = 0L,
    ) : DataSource.Factory {

        internal val resolver = IsoResolver()
        private val selectionNotified = java.util.concurrent.atomic.AtomicBoolean(false)
        private val provider = IsoTargetProvider(
            upstreamFactory,
            resolver,
            seekMs,
            { selection -> notifySelection(selection) },
            target,
        )

        override fun createDataSource(): DataSource =
            IsoDataSource(upstreamFactory, provider.provider())

        fun resolve(uri: Uri): IsoPlayTarget = provider.resolve(uri)

        private fun notifySelection(selection: IsoSelection) {
            val callback = onSelection ?: return
            if (selectionNotified.compareAndSet(false, true)) callback(selection)
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun factory(headers: Map<String, String>?): Factory = factory(headers, defaultClient)

        fun factory(headers: Map<String, String>?, onSelection: ((IsoSelection) -> Unit)?): Factory =
            factory(headers, defaultClient, null, onSelection, 0L)

        fun factory(headers: Map<String, String>?, seekMs: Long): Factory =
            factory(headers, defaultClient, null, null, seekMs)

        fun factory(
            headers: Map<String, String>?,
            seekMs: Long,
            onSelection: ((IsoSelection) -> Unit)?,
        ): Factory = factory(headers, defaultClient, null, onSelection, seekMs)

        fun factory(headers: Map<String, String>?, target: IsoPlayTarget): Factory =
            factory(headers, defaultClient, target, null, 0L)

        /** 外部提供上游数据源（可同时支持 http/file 等 scheme），请求头由调用方负责设置 */
        fun factory(
            upstreamFactory: DataSource.Factory,
            seekMs: Long,
            onSelection: ((IsoSelection) -> Unit)?,
        ): Factory = Factory(upstreamFactory, null, onSelection, seekMs)

        /** 指定已解析的分段目标，跳过重复探测 */
        fun factory(upstreamFactory: DataSource.Factory, target: IsoPlayTarget): Factory =
            Factory(upstreamFactory, target, null, 0L)

        fun factory(
            headers: Map<String, String>?,
            client: OkHttpClient,
            target: IsoPlayTarget? = null,
            onSelection: ((IsoSelection) -> Unit)? = null,
            seekMs: Long = 0L,
        ): Factory {
            val requestHeaders = HttpHeaders.toRequestHeaders(headers)
            var userAgent: String? = null
            val defaultHeaders = HashMap<String, String>()
            for ((key, value) in requestHeaders) {
                if (key.equals("User-Agent", ignoreCase = true)) {
                    userAgent = value
                } else {
                    defaultHeaders[key] = value
                }
            }
            val httpFactory = OkHttpDataSource.Factory(client)
            httpFactory.setUserAgent(userAgent)
            httpFactory.setDefaultRequestProperties(defaultHeaders)
            return Factory(httpFactory, target, onSelection, seekMs)
        }
    }
}

internal class IsoTargetProvider(
    private val upstreamFactory: DataSource.Factory,
    private val resolver: IsoResolver,
    private val seekMs: Long,
    private val onSelection: (IsoSelection) -> Unit,
    explicit: IsoPlayTarget? = null,
) {

    private val resolved = AtomicReference<IsoPlayTarget?>(explicit)

    fun provider(): (Uri) -> IsoPlayTarget = { uri -> resolve(uri) }

    fun resolve(uri: Uri): IsoPlayTarget {
        resolved.get()?.let { return it }
        val probe = resolver.probe(uri, upstreamFactory)
        val info = resolver.resolve(
            probe,
            DataSourceRangeReader(upstreamFactory, uri),
            seekMs,
        )
        IsoPlaylistState.update(info.selection)
        IsoThroughput.targetLength = info.selection.totalLength
        onSelection(info.selection)
        val target = info.selection.withPlaylistTargets().first()
        resolved.compareAndSet(null, target)
        return resolved.get() ?: target
    }
}

internal data class IsoReadWindow(val isoOffset: Long, val length: Long)

internal fun isoReadWindows(
    target: IsoPlayTarget,
    position: Long,
    requestedLength: Long,
): List<IsoReadWindow> {
    val available = target.availableLength - position
    if (position < 0 || available < 0) {
        throw IsoFormatException("position out of range: $position/${target.availableLength}")
    }
    val length = if (requestedLength == C.LENGTH_UNSET.toLong()) {
        available
    } else {
        minOf(requestedLength, available)
    }
    if (length <= 0L) return emptyList()
    val runs = target.playlistRuns ?: target.runs
    val base = target.playlistOffset + target.startOffset
    val windows = ArrayList<IsoReadWindow>(runs.size)
    var cursor = base + position
    var remaining = length
    var runStart = 0L
    for (run in runs) {
        val runEnd = runStart + run.length
        if (remaining > 0L && cursor >= runStart && cursor < runEnd) {
            val inside = cursor - runStart
            val take = minOf(remaining, run.length - inside)
            windows.add(IsoReadWindow(run.offset + inside, take))
            cursor += take
            remaining -= take
        }
        runStart = runEnd
        if (remaining <= 0L) break
    }
    if (remaining > 0L) throw IsoFormatException("target runs short: $remaining")
    return windows
}

internal fun isoReadWindow(target: IsoPlayTarget, position: Long, requestedLength: Long): IsoReadWindow {
    val windows = isoReadWindows(target, position, requestedLength)
    if (windows.isEmpty()) {
        return IsoReadWindow(target.m2tsOffset + target.startOffset + position, 0)
    }
    return windows.first()
}

internal class IsoCloseGuard {
    private var active = false

    fun activate() {
        active = true
    }

    fun close(action: () -> Unit) {
        if (!active) return
        active = false
        action()
    }
}
