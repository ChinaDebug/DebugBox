package com.tvbox.kotlin.core.player.iso

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

class PrimaryVideoExtractor(
    private val delegate: Extractor,
    private val primaryVideoPid: () -> Int?,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val suppressedAudio: (Int) -> Boolean = { false },
) : Extractor {

    private var gate: PrimaryVideoExtractorOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        val created = PrimaryVideoExtractorOutput(
            output,
            primaryVideoPid,
            deferTracks = gate == null,
            elapsedRealtimeMs = elapsedRealtimeMs,
            suppressedAudio = suppressedAudio,
        )
        gate = created
        delegate.init(created)
    }

    override fun seek(position: Long, timeUs: Long) {
        delegate.seek(position, timeUs)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        gate?.onRead(result)
        return result
    }

    override fun release() {
        delegate.release()
    }
}

internal class PrimaryVideoExtractorOutput(
    private val inner: ExtractorOutput,
    private val primaryVideoPid: () -> Int?,
    private val deferTracks: Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val suppressedAudio: (Int) -> Boolean = { false },
) : ExtractorOutput {

    private val declaredVideo = HashSet<Int>()
    private val pending = LinkedHashMap<Int, DeferredTrackOutput>()
    private val openedAtMs = elapsedRealtimeMs()
    private var audioVideoFormatted = false
    private var videoFormatted = false
    private var awaitingEnd = false
    private var ended = false

    override fun track(id: Int, type: Int): TrackOutput {
        if (type == C.TRACK_TYPE_VIDEO) {
            val primary = primaryVideoPid()
            val primaryDeclared = primary != null && declaredVideo.contains(primary)
            if (primary != null && primary != id && primaryDeclared) {
                return DiscardTrackOutput
            }
            declaredVideo.add(id)
        }
        if (type == C.TRACK_TYPE_AUDIO && suppressedAudio(id)) {
            return DiscardTrackOutput
        }
        if (!deferTracks) return inner.track(id, type)
        val deferred = DeferredTrackOutput(this, id, type)
        pending[id] = deferred
        return deferred
    }

    override fun endTracks() {
        if (ended) return
        if (!deferTracks) {
            ended = true
            inner.endTracks()
            return
        }
        awaitingEnd = true
        if (allMaterialized()) flush()
    }

    override fun seekMap(seekMap: SeekMap) {
        inner.seekMap(seekMap)
    }

    fun onRead(result: Int) {
        if (!awaitingEnd || ended) return
        if (allMaterialized() || result == Extractor.RESULT_END_OF_INPUT) {
            flush()
            return
        }
        if (dropAllowed() && elapsedRealtimeMs() - openedAtMs >= DEFER_HOLD_MS) flush()
    }

    fun materialize(id: Int, type: Int): TrackOutput? {
        if (ended) return null
        val track = inner.track(id, type)
        if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO) audioVideoFormatted = true
        if (type == C.TRACK_TYPE_VIDEO) videoFormatted = true
        return track
    }

    private fun dropAllowed(): Boolean =
        if (declaredVideo.isEmpty()) audioVideoFormatted else videoFormatted

    private fun allMaterialized(): Boolean = pending.values.all { it.materialized }

    private fun flush() {
        if (ended) return
        ended = true
        awaitingEnd = false
        inner.endTracks()
    }
}

internal class DeferredTrackOutput(
    private val owner: PrimaryVideoExtractorOutput,
    val id: Int,
    val type: Int,
) : TrackOutput {

    private var delegate: TrackOutput? = null
    private var dropped = false
    private var scratch = ByteArray(0)

    val materialized: Boolean
        get() = delegate != null

    override fun format(format: Format) {
        val track = delegate ?: open()
        track?.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val track = delegate ?: open()
        if (track != null) return track.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (scratch.size < length) scratch = ByteArray(length)
        return input.read(scratch, 0, length)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val track = delegate ?: open()
        if (track != null) {
            track.sampleData(data, length, sampleDataPart)
        } else {
            data.skipBytes(length)
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        delegate?.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun open(): TrackOutput? {
        if (dropped) return null
        val track = owner.materialize(id, type)
        if (track == null) {
            dropped = true
            return null
        }
        delegate = track
        return track
    }
}

private object DiscardTrackOutput : TrackOutput {

    private var scratch = ByteArray(0)

    override fun format(format: Format) = Unit

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (scratch.size < length) scratch = ByteArray(length)
        return input.read(scratch, 0, length)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = Unit
}

private const val DEFER_HOLD_MS = 1_500L
