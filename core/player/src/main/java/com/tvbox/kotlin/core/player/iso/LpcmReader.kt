package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

class LpcmReader(
    private val language: String? = null,
) : ElementaryStreamReader {

    private var output: TrackOutput? = null
    private var formatId: String? = null
    private var config: Config? = null
    private var rejected = false
    private var atPacketStart = false
    private var pesTimeUs = C.TIME_UNSET
    private var incoming = ByteArray(0)
    private var incomingLength = 0
    private var pcm = ByteArray(0)
    private var pcmLength = 0

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        formatId = idGenerator.formatId
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_AUDIO)
    }

    override fun packetStarted(timeUs: Long, flags: Int) {
        flushSample()
        pesTimeUs = timeUs
        atPacketStart = true
    }

    override fun consume(data: ParsableByteArray) {
        val size = data.bytesLeft()
        if (size <= 0) return
        if (rejected) {
            data.skipBytes(size)
            return
        }
        append(data, size)
        if (config == null) {
            if (!atPacketStart) {
                incomingLength = 0
                return
            }
            parseHeader()
            if (rejected || config == null) return
        } else if (atPacketStart) {
            if (incomingLength < HEADER_SIZE) return
            drop(HEADER_SIZE)
            atPacketStart = false
        }
        convertFrames()
    }

    override fun packetFinished() {
        flushSample()
    }

    override fun endOfInputReached() {
        flushSample()
    }

    override fun seek() {
        incomingLength = 0
        pcmLength = 0
        atPacketStart = false
        pesTimeUs = C.TIME_UNSET
    }

    private fun append(data: ParsableByteArray, size: Int) {
        val required = incomingLength + size
        if (incoming.size < required) {
            var capacity = if (incoming.isEmpty()) INITIAL_BUFFER_SIZE else incoming.size
            while (capacity < required) capacity *= 2
            incoming = incoming.copyOf(capacity)
        }
        data.readBytes(incoming, incomingLength, size)
        incomingLength = required
    }

    private fun parseHeader() {
        if (incomingLength < HEADER_SIZE) return
        val parsed = Config.parse(incoming, incomingLength)
        if (parsed == null) {
            rejected = true
            atPacketStart = false
            incomingLength = 0
            output?.format(fallbackFormat())
            return
        }
        config = parsed
        atPacketStart = false
        drop(HEADER_SIZE)
        output?.format(parsed.toFormat(formatId, language))
    }

    private fun convertFrames() {
        val config = config ?: return
        val frameSize = config.frameSize
        if (incomingLength < frameSize) return
        val frames = incomingLength / frameSize
        val outputSize = frames * config.channels * OUTPUT_BYTES_PER_SAMPLE
        ensurePcm(outputSize)
        var source = 0
        var target = pcmLength
        for (frame in 0 until frames) {
            for (channel in config.channelMap) {
                target = writeSample(target, source + channel * config.bytesPerSample, config.bytesPerSample)
            }
            source += frameSize
        }
        pcmLength = target
        drop(source)
    }

    private fun writeSample(target: Int, source: Int, bytesPerSample: Int): Int {
        var value: Int
        if (bytesPerSample == BYTES_PER_SAMPLE_24) {
            val raw = ((incoming[source].toInt() and 0xFF) shl 16) or
                ((incoming[source + 1].toInt() and 0xFF) shl 8) or
                (incoming[source + 2].toInt() and 0xFF)
            val signed = if (raw and SIGN_24 != 0) raw - FULL_24 else raw
            value = minOf((signed + ROUNDING_OFFSET) shr 8, MAX_SAMPLE_16)
        } else {
            value = ((incoming[source].toInt() and 0xFF) shl 8) or (incoming[source + 1].toInt() and 0xFF)
        }
        value = value and 0xFFFF
        pcm[target] = value.toByte()
        pcm[target + 1] = (value shr 8).toByte()
        return target + OUTPUT_BYTES_PER_SAMPLE
    }

    private fun ensurePcm(additional: Int) {
        val required = pcmLength + additional
        if (pcm.size >= required) return
        var capacity = if (pcm.isEmpty()) INITIAL_BUFFER_SIZE else pcm.size
        while (capacity < required) capacity *= 2
        pcm = pcm.copyOf(capacity)
    }

    private fun drop(count: Int) {
        val remain = incomingLength - count
        if (remain > 0) System.arraycopy(incoming, count, incoming, 0, remain)
        incomingLength = maxOf(0, remain)
    }

    private fun flushSample() {
        val output = this.output ?: return
        val size = pcmLength
        pcmLength = 0
        if (size <= 0) return
        val timeUs = pesTimeUs
        if (timeUs == C.TIME_UNSET) return
        output.sampleData(ParsableByteArray(pcm, size), size)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
    }

    private fun fallbackFormat(): Format =
        Format.Builder()
            .setId(formatId)
            .setLanguage(language)
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(FALLBACK_CHANNELS)
            .setSampleRate(FALLBACK_SAMPLE_RATE)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    private class Config(
        val sampleRate: Int,
        val channels: Int,
        val sourceChannels: Int,
        val bytesPerSample: Int,
        val channelMap: IntArray,
    ) {

        val frameSize: Int = sourceChannels * bytesPerSample

        fun toFormat(formatId: String?, language: String?): Format =
            Format.Builder()
                .setId(formatId)
                .setLanguage(language)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setAverageBitrate(sampleRate * channels * OUTPUT_BYTES_PER_SAMPLE * 8)
                .build()

        companion object {

            fun parse(header: ByteArray, length: Int): Config? {
                if (length < HEADER_SIZE) return null
                val sampleRate = when (header[2].toInt() and RATE_MASK) {
                    RATE_CODE_48K -> 48000
                    RATE_CODE_96K -> 96000
                    RATE_CODE_192K -> 192000
                    else -> return null
                }
                val bytesPerSample = when ((header[3].toInt() and 0xFF) ushr BITS_SHIFT) {
                    BITS_CODE_16 -> BYTES_PER_SAMPLE_16
                    BITS_CODE_24 -> BYTES_PER_SAMPLE_24
                    else -> return null
                }
                val layoutCode = (header[2].toInt() and 0xFF) ushr LAYOUT_SHIFT
                if (layoutCode >= LAYOUTS.size) return null
                val layout = LAYOUTS[layoutCode] ?: return null
                return Config(sampleRate, layout.channels.size, layout.sourceChannels, bytesPerSample, layout.channels)
            }
        }
    }

    private class Layout(val sourceChannels: Int, val channels: IntArray)

    private companion object {

        const val HEADER_SIZE = 4
        const val OUTPUT_BYTES_PER_SAMPLE = 2
        const val BYTES_PER_SAMPLE_16 = 2
        const val BYTES_PER_SAMPLE_24 = 3
        const val MAX_SAMPLE_16 = 0x7FFF
        const val ROUNDING_OFFSET = 0x80
        const val SIGN_24 = 0x800000
        const val FULL_24 = 0x1000000
        const val INITIAL_BUFFER_SIZE = 4096
        const val FALLBACK_SAMPLE_RATE = 48000
        const val FALLBACK_CHANNELS = 2

        const val RATE_MASK = 0x0F
        const val RATE_CODE_48K = 1
        const val RATE_CODE_96K = 4
        const val RATE_CODE_192K = 5

        const val LAYOUT_SHIFT = 4
        const val BITS_SHIFT = 6
        const val BITS_CODE_16 = 1
        const val BITS_CODE_24 = 3

        val LAYOUTS: Array<Layout?> = arrayOf(
            null,
            Layout(2, intArrayOf(0)),
            null,
            Layout(2, intArrayOf(0, 1)),
            Layout(4, intArrayOf(0, 1, 2)),
            Layout(4, intArrayOf(0, 1, 2)),
            Layout(4, intArrayOf(0, 1, 2, 3)),
            Layout(4, intArrayOf(0, 1, 2, 3)),
            Layout(6, intArrayOf(0, 1, 2, 3, 4)),
            Layout(6, intArrayOf(0, 1, 2, 5, 3, 4)),
            Layout(8, intArrayOf(0, 1, 2, 4, 5, 3, 6)),
            Layout(8, intArrayOf(0, 1, 2, 7, 4, 5, 3, 6)),
        )
    }
}
