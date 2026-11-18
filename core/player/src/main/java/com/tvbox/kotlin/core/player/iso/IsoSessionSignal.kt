package com.tvbox.kotlin.core.player.iso

internal class IsoSessionSignalState(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    private var escalatedAt = 0L

    @Volatile
    private var windowExtended = false

    @Volatile
    private var stubBytes = 0L

    fun markStub(bytes: Long) {
        stubBytes = bytes
    }

    fun stubBytes(): Long = stubBytes

    fun markEscalated() {
        escalatedAt = clock()
        windowExtended = false
    }

    fun escalated(): Boolean = escalatedAt > 0L

    fun consumeWindowExtension(): Boolean {
        val at = escalatedAt
        if (at <= 0L || windowExtended) return false
        if (clock() - at > EXTENSION_WINDOW_MS) return false
        windowExtended = true
        return true
    }

    fun reset() {
        escalatedAt = 0L
        windowExtended = false
        stubBytes = 0L
    }

    private companion object {
        const val EXTENSION_WINDOW_MS = 120_000L
    }
}

object IsoSessionSignal {

    private val state = IsoSessionSignalState()

    fun markEscalated() {
        state.markEscalated()
    }

    fun escalated(): Boolean = state.escalated()

    fun consumeWindowExtension(): Boolean = state.consumeWindowExtension()

    fun markStub(bytes: Long) {
        state.markStub(bytes)
    }

    fun stubBytes(): Long = state.stubBytes()

    fun reset() {
        state.reset()
    }
}
