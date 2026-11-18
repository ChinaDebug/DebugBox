package com.tvbox.kotlin.core.player.iso

internal object IsoAudioFallback {

    private var contentKey: String? = null
    private var pids = LinkedHashSet<Int>()

    @Synchronized
    fun suppress(contentKey: String?, pid: Int) {
        if (contentKey.isNullOrEmpty() || pid !in 1..MAX_PID) return
        if (this.contentKey != contentKey) {
            this.contentKey = contentKey
            pids = LinkedHashSet()
        }
        pids.add(pid)
    }

    @Synchronized
    fun suppressed(contentKey: String?, pid: Int): Boolean =
        !contentKey.isNullOrEmpty() && this.contentKey == contentKey && pids.contains(pid)

    @Synchronized
    fun clear() {
        contentKey = null
        pids = LinkedHashSet()
    }

    fun pidOfFormatId(formatId: String?): Int? {
        val pid = formatId?.substringAfterLast('/')?.trim()?.toIntOrNull() ?: return null
        return if (pid in 1..MAX_PID) pid else null
    }

    private const val MAX_PID = 0x1FFF
}
