package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.C
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

class Ac3CoreReader(language: String?) : ElementaryStreamReader {

    private val inner = Ac3Reader(language ?: "und")
    private val head = ByteArray(HEADER_BYTES)
    private var headFill = 0
    private var skipPes = false
    private var pesTimeUs = C.TIME_UNSET
    private var pesBytesSeen = 0
    private var frame = ByteArray(0)
    private var frameFill = 0
    private var frameTimeUs = C.TIME_UNSET
    private var frameDurationUs = 0L
    private var lastEndUs = C.TIME_UNSET

    override fun seek() {
        resetFrame()
        headFill = 0
        skipPes = false
        pesTimeUs = C.TIME_UNSET
        pesBytesSeen = 0
        lastEndUs = C.TIME_UNSET
        inner.seek()
    }

    override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        inner.createTracks(output, idGenerator)
    }

    override fun packetStarted(timeUs: Long, flags: Int) {
        pesTimeUs = timeUs
        pesBytesSeen = 0
        headFill = 0
        skipPes = false
    }

    override fun consume(data: ParsableByteArray) {
        if (data.bytesLeft() <= 0) return
        if (skipPes) {
            skipRest(data, data.bytesLeft())
            return
        }
        if (frameFill > 0) {
            fillFrame(data)
            return
        }
        if (headFill == 0 && pesBytesSeen > 0) {
            skipPes = true
            skipRest(data, data.bytesLeft())
            return
        }
        if (headFill < HEADER_BYTES) {
            val take = minOf(data.bytesLeft(), HEADER_BYTES - headFill)
            System.arraycopy(data.data, data.position, head, headFill, take)
            data.skipBytes(take)
            pesBytesSeen += take
            headFill += take
            if (headFill < HEADER_BYTES) return
            val info = parseFrameInfo(head)
            if (info == null) {
                headFill = 0
                skipPes = true
                skipRest(data, data.bytesLeft())
                return
            }
            frame = ByteArray(info.frameSize)
            frameFill = HEADER_BYTES
            System.arraycopy(head, 0, frame, 0, HEADER_BYTES)
            frameDurationUs = info.sampleCount * MICROS_PER_SECOND / info.sampleRate
            frameTimeUs = when {
                pesTimeUs != C.TIME_UNSET -> pesTimeUs
                lastEndUs != C.TIME_UNSET -> lastEndUs
                else -> 0L
            }
            if (frameFill == frame.size) {
                emitFrame()
                return
            }
        }
        fillFrame(data)
    }

    private fun fillFrame(data: ParsableByteArray) {
        if (frame.isEmpty()) {
            skipPes = true
            skipRest(data, data.bytesLeft())
            return
        }
        val take = minOf(data.bytesLeft(), frame.size - frameFill)
        System.arraycopy(data.data, data.position, frame, frameFill, take)
        data.skipBytes(take)
        pesBytesSeen += take
        frameFill += take
        if (frameFill == frame.size) {
            emitFrame()
        }
        val rest = data.bytesLeft()
        if (rest > 0) {
            skipPes = true
            skipRest(data, rest)
        }
    }

    private fun emitFrame() {
        inner.packetStarted(frameTimeUs, 0)
        inner.consume(ParsableByteArray(frame))
        lastEndUs = frameTimeUs + frameDurationUs
        resetFrame()
        headFill = 0
        skipPes = true
    }

    private fun skipRest(data: ParsableByteArray, length: Int) {
        data.skipBytes(length)
        pesBytesSeen += length
    }

    private fun resetFrame() {
        frame = ByteArray(0)
        frameFill = 0
        frameDurationUs = 0L
    }

    private fun parseFrameInfo(bytes: ByteArray): Ac3Util.SyncFrameInfo? {
        if (bytes[0] != SYNC_BYTE_0 || bytes[1] != SYNC_BYTE_1) return null
        val b4 = bytes[4].toInt() and 0xFF
        val fscod = b4 ushr 6
        val frmsizecod = b4 and 0x3F
        val bsid = (bytes[5].toInt() and 0xFF) ushr 3
        if (fscod > 2 || frmsizecod >= 38 || bsid > 10) return null
        return try {
            Ac3Util.parseAc3SyncframeInfo(ParsableBitArray(bytes))
        } catch (e: RuntimeException) {
            null
        }
    }

    private companion object {
        const val HEADER_BYTES = 32
        const val MICROS_PER_SECOND = 1_000_000L
        val SYNC_BYTE_0 = 0x0B.toByte()
        val SYNC_BYTE_1 = 0x77.toByte()
    }
}
