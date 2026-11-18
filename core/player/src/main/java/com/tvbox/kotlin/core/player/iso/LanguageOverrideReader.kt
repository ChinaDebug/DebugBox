package com.tvbox.kotlin.core.player.iso

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader

internal class LanguageOverrideReader(
    private val inner: ElementaryStreamReader,
    private val languageForPid: (Int) -> String?,
) : ElementaryStreamReader {

    override fun createTracks(
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        inner.createTracks(LanguageExtractorOutput(extractorOutput, languageForPid), idGenerator)
    }

    override fun packetStarted(timeUs: Long, flags: Int) = inner.packetStarted(timeUs, flags)

    override fun consume(data: ParsableByteArray) = inner.consume(data)

    override fun seek() = inner.seek()

    override fun packetFinished() = inner.packetFinished()

    override fun endOfInputReached() = inner.endOfInputReached()
}

private class LanguageExtractorOutput(
    private val inner: ExtractorOutput,
    private val languageForPid: (Int) -> String?,
) : ExtractorOutput {

    override fun track(id: Int, type: Int): TrackOutput {
        val output = inner.track(id, type)
        val language = languageForPid(id)
        return if (language.isNullOrEmpty()) output else LanguageTrackOutput(output, language)
    }

    override fun endTracks() = inner.endTracks()

    override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
}

private class LanguageTrackOutput(
    private val inner: TrackOutput,
    private val language: String,
) : TrackOutput {

    override fun format(format: Format) {
        inner.format(format.buildUpon().setLanguage(language).build())
    }

    override fun sampleData(data: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        inner.sampleData(data, length, allowEndOfInput)

    override fun sampleData(
        data: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int = inner.sampleData(data, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int) = inner.sampleData(data, length)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
        inner.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)

    override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)
}
