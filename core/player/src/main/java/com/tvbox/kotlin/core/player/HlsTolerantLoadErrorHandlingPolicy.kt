package com.tvbox.kotlin.core.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * HLS 分片容错策略：分片临时加载失败（CDN 抖动/间歇性 404）时短重试等待恢复，
 * 兜底净化无法覆盖的偶发坏分片；重试窗口有限，不长时间阻塞播放。
 */
class HlsTolerantLoadErrorHandlingPolicy(
    private val maxRetries: Int = 5,
    private val retryDelayMs: Long = 1000L
) : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxRetries

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return if (loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
            retryDelayMs
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
    ): LoadErrorHandlingPolicy.FallbackSelection? = null
}
