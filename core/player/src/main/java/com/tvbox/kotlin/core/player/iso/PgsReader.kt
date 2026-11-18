package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

class PgsReader(
    private val language: String? = null,
) : ElementaryStreamReader {

    private var output: TrackOutput? = null
    private var buffer = ByteArray(0)
    private var bufferLength = 0
    private var parsePosition = 0
    private var displaySetStart = -1
    private var displaySetTimeUs = C.TIME_UNSET
    private var pesTimeUs = C.TIME_UNSET

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        val formatId = idGenerator.formatId
        val trackOutput = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        trackOutput.format(
            Format.Builder()
                .setId(formatId)
                .setSampleMimeType(MimeTypes.APPLICATION_PGS)
                .setLanguage(language)
                .build(),
        )
        output = trackOutput
    }

    override fun packetStarted(timeUs: Long, flags: Int) {
        pesTimeUs = timeUs
    }

    override fun consume(data: ParsableByteArray) {
        if (data.bytesLeft() <= 0) return
        append(data)
        while (true) {
            if (bufferLength - parsePosition < SEGMENT_HEADER_BYTES) break
            val type = buffer[parsePosition].toInt() and 0xFF
            val size = ((buffer[parsePosition + 1].toInt() and 0xFF) shl 8) or
                (buffer[parsePosition + 2].toInt() and 0xFF)
            if (bufferLength - parsePosition - SEGMENT_HEADER_BYTES < size) break
            val end = parsePosition + SEGMENT_HEADER_BYTES + size
            if (displaySetStart < 0) {
                if (type != SEGMENT_PCS) {
                    parsePosition = end
                    continue
                }
                displaySetStart = parsePosition
                displaySetTimeUs = pesTimeUs
            }
            if (type == SEGMENT_END) {
                if (displaySetTimeUs == C.TIME_UNSET) {
                    resetDisplaySet()
                } else {
                    emitDisplaySet(end)
                }
                parsePosition = end
                continue
            }
            parsePosition = end
        }
        compact()
        if (bufferLength > MAX_DISPLAY_SET_BYTES) {
            resetBuffer()
        }
    }

    override fun seek() {
        resetBuffer()
        pesTimeUs = C.TIME_UNSET
    }

    override fun endOfInputReached() {
        resetBuffer()
    }

    private fun append(data: ParsableByteArray) {
        val size = data.bytesLeft()
        ensureCapacity(bufferLength + size)
        System.arraycopy(data.data, data.position, buffer, bufferLength, size)
        data.skipBytes(size)
        bufferLength += size
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var capacity = if (buffer.size == 0) INITIAL_CAPACITY else buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private fun compact() {
        val drop = if (displaySetStart >= 0) displaySetStart else parsePosition
        if (drop <= 0) return
        val remaining = bufferLength - drop
        if (remaining > 0) {
            System.arraycopy(buffer, drop, buffer, 0, remaining)
        }
        bufferLength = remaining
        parsePosition -= drop
        if (displaySetStart >= 0) displaySetStart -= drop
    }

    private fun emitDisplaySet(endExclusive: Int) {
        val length = endExclusive - displaySetStart
        if (length > MAX_DISPLAY_SET_BYTES) {
            resetDisplaySet()
            return
        }
        val payload = buffer.copyOfRange(displaySetStart, endExclusive)
        val trackOutput = output
        if (trackOutput != null) {
            trackOutput.sampleData(ParsableByteArray(payload), payload.size)
            trackOutput.sampleMetadata(displaySetTimeUs, C.BUFFER_FLAG_KEY_FRAME, payload.size, 0, null)
        }
        resetDisplaySet()
    }

    private fun resetDisplaySet() {
        displaySetStart = -1
        displaySetTimeUs = C.TIME_UNSET
    }

    private fun resetBuffer() {
        bufferLength = 0
        parsePosition = 0
        resetDisplaySet()
    }

    private companion object {
        const val SEGMENT_PCS = 0x16
        const val SEGMENT_END = 0x80
        const val SEGMENT_HEADER_BYTES = 3
        const val INITIAL_CAPACITY = 16 * 1024
        const val MAX_DISPLAY_SET_BYTES = 2 * 1024 * 1024
    }
}
