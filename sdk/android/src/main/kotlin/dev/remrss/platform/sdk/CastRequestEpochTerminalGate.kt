package dev.remrss.platform.sdk

/** Serializes progress and terminal delivery for one exact SDK Binder epoch. */
internal class CastRequestEpochTerminalGate(private val ownerEpoch: Any) {
    private val lock = Any()
    private var terminal = false

    fun dispatch(epoch: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (terminal || epoch !== ownerEpoch) return@synchronized false
        action()
        true
    }

    fun terminate(epoch: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (terminal || epoch !== ownerEpoch) return@synchronized false
        terminal = true
        action()
        true
    }

    fun isOpenFor(epoch: Any): Boolean = synchronized(lock) { !terminal && epoch === ownerEpoch }
}
