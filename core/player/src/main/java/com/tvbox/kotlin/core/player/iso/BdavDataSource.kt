package com.tvbox.kotlin.core.player.iso

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

internal const val BDAV_SYNC_CHECKS = 4

internal val TS_SYNC_BYTE: Byte = 0x47.toByte()

internal const val PMT_TABLE_ID: Byte = 0x02

internal fun isBdav192(header: ByteArray, size: Int): Boolean {
    if (size < M2TS_ATS_SIZE + M2TS_PACKET_SIZE * (BDAV_SYNC_CHECKS - 1) + 1) return false
    for (index in 0 until BDAV_SYNC_CHECKS) {
        if (header[M2TS_ATS_SIZE + index * M2TS_PACKET_SIZE] != TS_SYNC_BYTE) return false
    }
    return true
}

internal fun hasStreamData(data: ByteArray, size: Int): Boolean {
    var index = 0
    while (index + M2TS_PACKET_SIZE <= size) {
        val base = index + M2TS_ATS_SIZE
        val end = base + M2TS_TS_SIZE
        if (data[base] == TS_SYNC_BYTE) {
            val pusi = (data[base + 1].toInt() shr 6) and 1
            val afc = (data[base + 3].toInt() shr 4) and 3
            if (pusi == 1 && afc != 0 && afc != 2) {
                var payload = base + 4
                if (afc == 3) payload += 1 + (data[base + 4].toInt() and 0xFF)
                if (payload + 3 < end) {
                    if (data[payload] == 0.toByte() &&
                        data[payload + 1] == 0.toByte() &&
                        data[payload + 2] == 1.toByte()
                    ) {
                        return true
                    }
                    val section = payload + 1 + (data[payload].toInt() and 0xFF)
                    if (section < end && data[section] == PMT_TABLE_ID) return true
                }
            }
        }
        index += M2TS_PACKET_SIZE
    }
    return false
}

internal sealed interface BdavVerdict {

    data class Hit(val target: IsoPlayTarget) : BdavVerdict

    object Miss : BdavVerdict

    object Unknown : BdavVerdict
}

internal class BdavSkipException : IOException("not bdav stream")

internal object BdavProbe {

    private const val MAX_ENTRIES = 8

    private const val SCAN_BYTES = 64 * 1024

    private const val MIN_BDAV_BYTES = 1024L * 1024L

    private class Cached(val verdict: BdavVerdict)

    private val lock = Any()

    private val entries = object : LinkedHashMap<String, Cached>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > MAX_ENTRIES
    }

    fun verdict(factory: DataSource.Factory, uri: Uri): BdavVerdict {
        val key = uri.toString()
        val cached = synchronized(lock) { if (entries.containsKey(key)) entries[key] else null }
        if (cached != null) return cached.verdict
        val verdict = probe(factory, uri)
        if (verdict != BdavVerdict.Unknown) {
            synchronized(lock) { entries[key] = Cached(verdict) }
        }
        return verdict
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun probe(factory: DataSource.Factory, uri: Uri): BdavVerdict {
        val source = factory.createDataSource()
        return try {
            val total = source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build(),
            )
            if (total == C.LENGTH_UNSET.toLong()) {
                return BdavVerdict.Miss
            }
            if (total < MIN_BDAV_BYTES) {
                IsoSessionSignal.markStub(total)
                return BdavVerdict.Miss
            }
            val scan = ByteArray(minOf(total, SCAN_BYTES.toLong()).toInt())
            var read = 0
            while (read < scan.size) {
                val count = source.read(scan, read, scan.size - read)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            if (!isBdav192(scan, read)) {
                return BdavVerdict.Miss
            }
            if (!hasStreamData(scan, read)) {
                IsoSessionSignal.markStub(total)
                return BdavVerdict.Miss
            }
            IsoThroughput.targetLength = total
            IsoSessionSignal.markEscalated()
            BdavVerdict.Hit(IsoPlayTarget(0, total, ""))
        } catch (e: IOException) {
            BdavVerdict.Unknown
        } finally {
            source.close()
        }
    }
}

internal class BdavLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception is BdavSkipException) {
            C.TIME_UNSET
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
        }
}

class BdavDataSource(
    private val upstreamFactory: DataSource.Factory,
) : DataSource {

    private val listeners = ArrayList<TransferListener>()

    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        releaseDelegate()
        val target = when (val verdict = BdavProbe.verdict(upstreamFactory, dataSpec.uri)) {
            is BdavVerdict.Hit -> verdict.target
            BdavVerdict.Miss -> throw BdavSkipException()
            BdavVerdict.Unknown -> throw IOException("bdav probe failed")
        }
        val source: DataSource = M2tsDataSource(upstreamFactory) { target }
        for (listener in listeners) source.addTransferListener(listener)
        val opened = try {
            source.open(dataSpec)
        } catch (e: Throwable) {
            runCatching { source.close() }
            throw e
        }
        delegate = source
        return opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun close() {
        releaseDelegate()
    }

    private fun releaseDelegate() {
        val source = delegate
        delegate = null
        runCatching { source?.close() }
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = BdavDataSource(upstream)
    }
}
