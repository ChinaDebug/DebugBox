package com.tvbox.kotlin.core.player.iso

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.DtsReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsPayloadReader

class BdTsPayloadReaderFactory(
    private val flags: Int = DEFAULT_FLAGS,
    private val lpcmEnabled: () -> Boolean = { true },
    private val trueHdCoreEnabled: () -> Boolean = { true },
    private val pgsEnabled: () -> Boolean = { true },
    private val languageForPid: (Int) -> String? = { null },
) : TsPayloadReader.Factory {

    private val defaults = DefaultTsPayloadReaderFactory(flags)

    private val hdmvDtsEnabled: Boolean
        get() = (flags and DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS) != 0

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        defaults.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        when (streamType) {
            STREAM_TYPE_LPCM ->
                if (lpcmEnabled()) wrap(LpcmReader(esInfo.language), LPCM_PROVISIONAL) else null
            STREAM_TYPE_TRUEHD -> if (trueHdCoreEnabled()) {
                wrap(Ac3CoreReader(esInfo.language), AC3_PROVISIONAL)
            } else {
                null
            }
            STREAM_TYPE_AC3 -> wrap(
                Ac3Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T),
                AC3_PROVISIONAL,
            )
            STREAM_TYPE_EAC3 -> wrap(
                Ac3Reader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T),
                EAC3_PROVISIONAL,
            )
            STREAM_TYPE_DTS -> if (hdmvDtsEnabled) dtsReader(esInfo) else null
            STREAM_TYPE_DTS_HD, STREAM_TYPE_DTS_HD_MA -> dtsCoreReader(esInfo)
            STREAM_TYPE_PGS -> if (pgsEnabled()) wrap(PgsReader(esInfo.language)) else null
            else -> unmapped(streamType, esInfo)
        }

    private fun unmapped(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        defaults.createPayloadReader(streamType, esInfo)

    private fun dtsReader(esInfo: TsPayloadReader.EsInfo): TsPayloadReader =
        wrap(
            DtsReader(
                esInfo.language,
                esInfo.roleFlags,
                EXTSS_HEADER_SIZE_MAX,
                MimeTypes.VIDEO_MP2T,
            ),
            DTS_PROVISIONAL,
        )

    private fun dtsCoreReader(esInfo: TsPayloadReader.EsInfo): TsPayloadReader =
        wrap(
            DtsCoreReader(esInfo.language, esInfo.roleFlags, MimeTypes.VIDEO_MP2T),
            DTS_PROVISIONAL,
        )

    private fun wrap(
        reader: ElementaryStreamReader,
        provisional: Format? = null,
    ): TsPayloadReader =
        PesReader(
            LanguageOverrideReader(
                ProvisionalFormatReader(reader, provisional),
                languageForPid,
            ),
        )

    companion object {
        const val STREAM_TYPE_LPCM = 0x80
        const val STREAM_TYPE_AC3 = 0x81
        const val STREAM_TYPE_DTS = 0x82
        const val STREAM_TYPE_TRUEHD = 0x83
        const val STREAM_TYPE_EAC3 = 0x84
        const val STREAM_TYPE_DTS_HD = 0x85
        const val STREAM_TYPE_DTS_HD_MA = 0x86
        const val STREAM_TYPE_PGS = 0x90
        const val EXTSS_HEADER_SIZE_MAX = 4096

        val DEFAULT_FLAGS: Int =
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

        private const val PROVISIONAL_SAMPLE_RATE = 48_000
        private const val PROVISIONAL_SURROUND = 6
        private const val PROVISIONAL_STEREO = 2

        val LPCM_PROVISIONAL: Format =
            provisional(MimeTypes.AUDIO_RAW, PROVISIONAL_STEREO, C.ENCODING_PCM_16BIT)

        val AC3_PROVISIONAL: Format = provisional(MimeTypes.AUDIO_AC3, PROVISIONAL_SURROUND)

        val EAC3_PROVISIONAL: Format = provisional(MimeTypes.AUDIO_E_AC3, PROVISIONAL_SURROUND)

        val DTS_PROVISIONAL: Format = provisional(MimeTypes.AUDIO_DTS, PROVISIONAL_SURROUND)

        private fun provisional(mimeType: String, channels: Int, pcmEncoding: Int? = null): Format {
            val builder = Format.Builder()
                .setSampleMimeType(mimeType)
                .setSampleRate(PROVISIONAL_SAMPLE_RATE)
                .setChannelCount(channels)
            if (pcmEncoding != null) builder.setPcmEncoding(pcmEncoding)
            return builder.build()
        }
    }
}
