package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

internal class ProvisionalFormatReader(
    private val inner: ElementaryStreamReader,
    private val provisional: Format?,
) : ElementaryStreamReader {

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        inner.createTracks(ProvisionalExtractorOutput(extractorOutput, provisional), idGenerator)
    }

    override fun packetStarted(timeUs: Long, flags: Int) = inner.packetStarted(timeUs, flags)

    override fun consume(data: ParsableByteArray) = inner.consume(data)

    override fun seek() = inner.seek()

    override fun packetFinished() = inner.packetFinished()

    override fun endOfInputReached() = inner.endOfInputReached()
}

private class ProvisionalExtractorOutput(
    private val inner: ExtractorOutput,
    private val provisional: Format?,
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val track = inner.track(id, type)
        if (provisional != null) track.format(provisional)
        return track
    }

    override fun endTracks() = inner.endTracks()

    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}
