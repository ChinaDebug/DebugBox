package com.tvbox.kotlin.core.player.iso

internal class IsoThroughputWindow(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val buckets = LongArray(BUCKET_COUNT)
    private val epochs = LongArray(BUCKET_COUNT) { NO_EPOCH }
    private var firstReadAt = NO_EPOCH
    private var lastReadAt = NO_EPOCH

    fun reset() {
        synchronized(lock) {
            buckets.fill(0)
            epochs.fill(NO_EPOCH)
            firstReadAt = NO_EPOCH
            lastReadAt = NO_EPOCH
        }
    }

    fun onRead(bytes: Int) {
        if (bytes <= 0) return
        val now = clock()
        synchronized(lock) {
            if (firstReadAt == NO_EPOCH) firstReadAt = now
            lastReadAt = now
            val epoch = now / BUCKET_MS
            val slot = (epoch % BUCKET_COUNT).toInt()
            if (epochs[slot] != epoch) {
                epochs[slot] = epoch
                buckets[slot] = 0
            }
            buckets[slot] += bytes.toLong()
        }
    }

    fun hasReads(): Boolean = synchronized(lock) { firstReadAt != NO_EPOCH }

    fun lastReadAgeMs(): Long = synchronized(lock) {
        if (lastReadAt == NO_EPOCH) -1L else clock() - lastReadAt
    }

    fun bytesPerSecond(): Long {
        val now = clock()
        synchronized(lock) {
            if (firstReadAt == NO_EPOCH) return 0
            val start = maxOf(firstReadAt, now - WINDOW_MS)
            val span = now - start
            if (span < MIN_SPAN_MS) return 0
            var sum = 0L
            for (index in buckets.indices) {
                val epoch = epochs[index]
                if (epoch == NO_EPOCH) continue
                val at = epoch * BUCKET_MS
                if (at <= now && at + BUCKET_MS > start) sum += buckets[index]
            }
            return sum * MILLIS_PER_SECOND / span
        }
    }

    private companion object {
        const val BUCKET_MS = 250L
        const val BUCKET_COUNT = 16
        const val WINDOW_MS = BUCKET_MS * BUCKET_COUNT
        const val MIN_SPAN_MS = 1500L
        const val MILLIS_PER_SECOND = 1000L
        const val NO_EPOCH = Long.MIN_VALUE
    }
}

object IsoThroughput {

    private val window = IsoThroughputWindow()

    @Volatile
    var targetLength = 0L

    fun beginSession() {
        window.reset()
        targetLength = 0L
    }

    fun onRead(bytes: Int) {
        window.onRead(bytes)
    }

    fun bytesPerSecond(): Long = window.bytesPerSecond()

    fun hasReads(): Boolean = window.hasReads()

    fun lastReadAgeMs(): Long = window.lastReadAgeMs()

    fun targetLength(): Long = targetLength
}
