package com.tvbox.kotlin.core.player

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * 网速计量：基于 UID 累计接收字节差分采样。
 *
 * 单位 KB/s；两次采样间隔过短（<300ms）时返回上次结果，避免除零与高频抖动。
 * TrafficStats 不可用时恒返回 0。
 */
class NetworkSpeedMeter {

    private var lastBytes = -1L
    private var lastSampleMs = 0L
    private var lastKbps = 0L

    fun sample(): Long {
        val now = SystemClock.elapsedRealtime()
        val bytes = TrafficStats.getUidRxBytes(Process.myUid())
        if (bytes == TrafficStats.UNSUPPORTED.toLong()) {
            lastKbps = 0
            return 0
        }
        if (lastBytes < 0) {
            lastBytes = bytes
            lastSampleMs = now
            return 0
        }
        val elapsed = now - lastSampleMs
        if (elapsed < MIN_SAMPLE_INTERVAL_MS) return lastKbps
        val delta = (bytes - lastBytes).coerceAtLeast(0)
        lastKbps = delta * 1000 / elapsed / 1024
        lastBytes = bytes
        lastSampleMs = now
        return lastKbps
    }

    /** 丢弃采样基准（切换媒体源后重新起算） */
    fun reset() {
        lastBytes = -1
        lastSampleMs = 0
        lastKbps = 0
    }

    private companion object {
        const val MIN_SAMPLE_INTERVAL_MS = 300L
    }
}
