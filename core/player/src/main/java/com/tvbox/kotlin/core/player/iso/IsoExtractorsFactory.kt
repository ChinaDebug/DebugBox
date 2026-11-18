package com.tvbox.kotlin.core.player.iso

import android.net.Uri
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

class IsoExtractorsFactory(
    private val readerFactory: TsPayloadReader.Factory = DEFAULT_READER_FACTORY,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> = arrayOf(newExtractor(null))

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = arrayOf(newExtractor(uri.toString()))

    private fun newExtractor(contentKey: String?): Extractor = PrimaryVideoExtractor(
        TsExtractor(
            TsExtractor.MODE_MULTI_PMT,
            0,
            DefaultSubtitleParserFactory(),
            TimestampAdjuster(TIMESTAMP_SHARED),
            readerFactory,
            TIMESTAMP_SEARCH_BYTES,
        ),
        { IsoPlaylistState.videoPid() },
        suppressedAudio = { pid -> IsoAudioFallback.suppressed(contentKey, pid) },
    )

    private companion object {
        const val TIMESTAMP_SHARED = 0L

        val TIMESTAMP_SEARCH_BYTES = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10

        val DEFAULT_READER_FACTORY: TsPayloadReader.Factory =
            BdTsPayloadReaderFactory(
                lpcmEnabled = { IsoSettings.lpcmEnabled },
                trueHdCoreEnabled = { IsoSettings.trueHdCoreEnabled },
                pgsEnabled = { IsoSettings.pgsEnabled },
                languageForPid = { pid -> IsoPlaylistState.languageForPid(pid) },
            )
    }
}
