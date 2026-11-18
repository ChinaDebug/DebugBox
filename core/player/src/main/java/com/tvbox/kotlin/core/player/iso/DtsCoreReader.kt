package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

internal class DtsCoreReader(
    private val language: String?,
    private val roleFlags: Int,
    private val containerMimeType: String,
) : ElementaryStreamReader {

    private var output: TrackOutput? = null
    private var formatId: String? = null
    private var format: Format? = null

    private val headerBytes = ByteArray(CORE_HEADER_SIZE)
    private val headerArray = ParsableByteArray(headerBytes)

    private var state = STATE_FINDING_SYNC
    private var bytesRead = 0
    private var syncBytes = 0
    private var sampleSize = 0
    private var sampleDurationUs = 0L
    private var timeUs = C.TIME_UNSET
    private var pendingTimeUs = C.TIME_UNSET

    override fun seek() {
        state = STATE_FINDING_SYNC
        bytesRead = 0
        syncBytes = 0
        sampleSize = 0
        timeUs = C.TIME_UNSET
        pendingTimeUs = C.TIME_UNSET
    }

    override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        this.output = output.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(timeUs: Long, flags: Int) {
        if (timeUs == C.TIME_UNSET) return
        if (state == STATE_FINDING_SYNC) {
            this.timeUs = timeUs
        } else {
            pendingTimeUs = timeUs
        }
    }

    override fun consume(data: ParsableByteArray) {
        val sink = output ?: return
        while (data.bytesLeft() > 0) {
            when (state) {
                STATE_FINDING_SYNC -> skipToNextCoreSync(data)
                STATE_READING_CORE_HEADER -> if (continueHeader(data)) emitHeader(sink)
                STATE_READING_SAMPLE -> readSample(data, sink)
                else -> state = STATE_FINDING_SYNC
            }
        }
    }

    override fun endOfInputReached() = Unit

    private fun skipToNextCoreSync(data: ParsableByteArray) {
        val bytes = data.data
        var position = data.position
        val limit = data.limit()
        while (position < limit) {
            syncBytes = (syncBytes shl 8) or (bytes[position++].toInt() and 0xFF)
            if (DtsUtil.getFrameType(syncBytes) != DtsUtil.FRAME_TYPE_CORE) continue
            headerBytes[0] = (syncBytes ushr 24).toByte()
            headerBytes[1] = (syncBytes ushr 16).toByte()
            headerBytes[2] = (syncBytes ushr 8).toByte()
            headerBytes[3] = syncBytes.toByte()
            syncBytes = 0
            bytesRead = 4
            state = STATE_READING_CORE_HEADER
            break
        }
        data.setPosition(position)
    }

    private fun continueHeader(data: ParsableByteArray): Boolean {
        val toRead = minOf(data.bytesLeft(), CORE_HEADER_SIZE - bytesRead)
        System.arraycopy(data.data, data.position, headerBytes, bytesRead, toRead)
        data.skipBytes(toRead)
        bytesRead += toRead
        return bytesRead == CORE_HEADER_SIZE
    }

    private fun emitHeader(sink: TrackOutput) {
        val current = format ?: DtsUtil.parseDtsFormat(
            headerBytes,
            formatId,
            language,
            roleFlags,
            containerMimeType,
            null,
        ).also {
            format = it
            sink.format(it)
        }
        val size = DtsUtil.getDtsFrameSize(headerBytes)
        if (size < CORE_HEADER_SIZE || current.sampleRate <= 0) {
            state = STATE_FINDING_SYNC
            bytesRead = 0
            syncBytes = 0
            return
        }
        sampleSize = size
        sampleDurationUs = Util.sampleCountToDurationUs(
            DtsUtil.parseDtsAudioSampleCount(headerBytes).toLong(),
            current.sampleRate,
        )
        headerArray.setPosition(0)
        sink.sampleData(headerArray, CORE_HEADER_SIZE)
        state = STATE_READING_SAMPLE
    }

    private fun readSample(data: ParsableByteArray, sink: TrackOutput) {
        val toRead = minOf(data.bytesLeft(), sampleSize - bytesRead)
        sink.sampleData(data, toRead)
        bytesRead += toRead
        if (bytesRead < sampleSize) return
        val emittedTimeUs = timeUs
        if (emittedTimeUs != C.TIME_UNSET) {
            sink.sampleMetadata(emittedTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
            timeUs = emittedTimeUs + sampleDurationUs
            if (pendingTimeUs != C.TIME_UNSET) {
                if (pendingTimeUs != emittedTimeUs) timeUs = pendingTimeUs
                pendingTimeUs = C.TIME_UNSET
            }
        }
        bytesRead = 0
        sampleSize = 0
        state = STATE_FINDING_SYNC
    }

    private companion object {
        const val CORE_HEADER_SIZE = 18
        const val STATE_FINDING_SYNC = 0
        const val STATE_READING_CORE_HEADER = 1
        const val STATE_READING_SAMPLE = 2
    }
}
