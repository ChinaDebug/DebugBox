package com.tvbox.kotlin.core.player.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

class M2tsDataSource internal constructor(
    private val upstreamFactory: DataSource.Factory,
    private val targetProvider: (Uri) -> IsoPlayTarget,
) : BaseDataSource(false) {

    constructor(factory: IsoDataSource.Factory) : this(factory, factory::resolve)

    private val closeGuard = IsoCloseGuard()
    private var upstream: DataSource? = null
    private var stream: M2tsStream? = null
    private var dataUri: Uri? = null
    private var targetValue: IsoPlayTarget? = null

    fun target(): IsoPlayTarget? = targetValue

    fun position(): Long = stream?.position() ?: 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataUri = dataSpec.uri
        val target = targetProvider(dataSpec.uri)
        targetValue = target
        val logicalLength = m2tsLogicalLength(target.segmentLength)
        val length = m2tsReadLength(dataSpec.position, dataSpec.length, logicalLength)
        closeGuard.activate()
        if (length == 0L) {
            transferStarted(dataSpec)
            return 0
        }
        val span = m2tsSpan(dataSpec.position, M2TS_MAX_READ)
        val source = upstreamFactory.createDataSource()
        val upstreamSpec = dataSpec.buildUpon()
            .setPosition(span.offset)
            .setLength(span.length)
            .build()
        val opened = try {
            source.open(upstreamSpec)
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        upstream = source
        stream = M2tsStream(
            upstreamFactory,
            dataSpec,
            dataSpec.position,
            length,
            source,
            if (opened == C.LENGTH_UNSET.toLong()) span.length else minOf(opened, span.length),
        )
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val active = stream ?: return C.RESULT_END_OF_INPUT
        val read = active.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun close() {
        closeGuard.close {
            val active = stream
            upstream = null
            stream = null
            try {
                active?.close()
            } finally {
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = upstream?.uri ?: dataUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    class Factory(private val isoFactory: IsoDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = M2tsDataSource(isoFactory)
    }
}

internal const val M2TS_TS_SIZE = 188
internal const val M2TS_PACKET_SIZE = 192
internal const val M2TS_ATS_SIZE = 4
internal const val M2TS_MAX_READ = 8L * 1024L * 1024L

internal data class M2tsSpan(val offset: Long, val length: Long)

internal fun m2tsOffset(logical: Long): Long =
    (logical / M2TS_TS_SIZE) * M2TS_PACKET_SIZE + M2TS_ATS_SIZE + logical % M2TS_TS_SIZE

internal fun m2tsSpan(position: Long, length: Long): M2tsSpan {
    require(position >= 0) { "position must not be negative" }
    require(length > 0) { "length must be positive" }
    val offset = m2tsOffset(position)
    val end = m2tsOffset(position + length - 1) + 1
    return M2tsSpan(offset, end - offset)
}

internal fun m2tsLogicalLength(m2tsLength: Long): Long =
    if (m2tsLength <= 0) 0 else m2tsLength / M2TS_PACKET_SIZE * M2TS_TS_SIZE

internal fun m2tsReadLength(position: Long, requestedLength: Long, m2tsLength: Long): Long {
    if (position < 0 || position > m2tsLength) {
        throw IsoFormatException("position out of range: $position/$m2tsLength")
    }
    val available = m2tsLength - position
    return if (requestedLength == C.LENGTH_UNSET.toLong()) available else minOf(requestedLength, available)
}

internal class M2tsStream(
    private val factory: DataSource.Factory?,
    private val baseSpec: DataSpec?,
    start: Long,
    private var bytesRemaining: Long,
    private var upstream: DataSource,
    private var upstreamRemaining: Long,
) {
    internal constructor(
        upstream: DataSource,
        start: Long,
        bytesRemaining: Long,
        upstreamRemaining: Long,
    ) : this(null, null, start, bytesRemaining, upstream, upstreamRemaining)

    private var position = start
    private var skipRemaining = 0
    private var recoveries = 0
    private val skipBuffer = ByteArray(M2TS_ATS_SIZE)

    fun position(): Long = position

    fun close() {
        runCatching { upstream.close() }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (upstreamRemaining <= 0L && !refill()) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        if (!skipAts()) {
            if (recover()) return read(buffer, offset, length)
            throw IOException("upstream truncated at $position")
        }
        val inBlock = (position % M2TS_TS_SIZE).toInt()
        val max = minOf(length.toLong(), bytesRemaining, (M2TS_TS_SIZE - inBlock).toLong(), upstreamRemaining)
        if (max <= 0L) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        val read = try {
            upstream.read(buffer, offset, max.toInt())
        } catch (e: IOException) {
            if (recover()) return read(buffer, offset, length)
            throw e
        }
        if (read <= 0) {
            if (recover()) return read(buffer, offset, length)
            throw IOException("upstream truncated at $position")
        }
        recoveries = 0
        upstreamRemaining -= read
        position += read
        bytesRemaining -= read
        IsoThroughput.onRead(read)
        if (bytesRemaining > 0L && position % M2TS_TS_SIZE == 0L) {
            skipRemaining = M2TS_ATS_SIZE
        }
        return read
    }

    private fun skipAts(): Boolean {
        while (skipRemaining > 0) {
            if (upstreamRemaining <= 0L && !refill()) return false
            val want = minOf(skipRemaining.toLong(), upstreamRemaining).toInt()
            if (want <= 0) return false
            val read = try {
                upstream.read(skipBuffer, 0, want)
            } catch (e: IOException) {
                return false
            }
            if (read <= 0) return false
            skipRemaining -= read
            upstreamRemaining -= read
        }
        return true
    }

    private fun recover(): Boolean {
        if (factory == null || baseSpec == null) return false
        if (recoveries >= MAX_RECOVERIES) return false
        recoveries++
        return runCatching { refill() }.getOrDefault(false)
    }

    private fun refill(): Boolean {
        val source = factory ?: return false
        val template = baseSpec ?: return false
        val logical = minOf(bytesRemaining, M2TS_MAX_READ)
        if (logical <= 0L) return false
        val span = m2tsSpan(position, logical)
        upstream.close()
        val next = source.createDataSource()
        val spec = template.buildUpon()
            .setPosition(span.offset)
            .setLength(span.length)
            .build()
        val opened = try {
            next.open(spec)
        } catch (e: IOException) {
            runCatching { next.close() }
            throw e
        }
        upstream = next
        upstreamRemaining = if (opened == C.LENGTH_UNSET.toLong()) span.length else minOf(opened, span.length)
        skipRemaining = 0
        return upstreamRemaining > 0L
    }

    private companion object {
        const val MAX_RECOVERIES = 4
    }
}
