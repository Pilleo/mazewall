package io.mazewall.enforcer.internal.supervisor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val IO_QUIESCENCE_TIMEOUT_SECONDS = 5L

/** Stop admission immediately; commit each independent release only after it succeeds. */
internal class SocketHelperLifetime(
    private val reap: () -> Unit,
    private val closeChannel: () -> Unit,
    private val closeEndpoint: () -> Unit,
) : AutoCloseable {
    private enum class ConfinedIo { NotStarted, Active, Quiesced }

    private val ioGate = Any()
    private var confinedIo = ConfinedIo.NotStarted
    private val ioQuiesced = CountDownLatch(1)

    fun runConfinedIo(action: () -> Unit) {
        synchronized(ioGate) {
            check(accepting && confinedIo == ConfinedIo.NotStarted) { "Confined helper I/O owner already started or retired" }
            confinedIo = ConfinedIo.Active
        }
        try {
            action()
        } finally {
            synchronized(ioGate) { confinedIo = ConfinedIo.Quiesced }
            ioQuiesced.countDown()
        }
    }

    @Volatile
    var accepting: Boolean = true
        private set

    private var reaped = false
    private var channelClosed = false
    private var endpointClosed = false

    @Synchronized
    override fun close() {
        val active = synchronized(ioGate) {
            accepting = false
            confinedIo == ConfinedIo.Active
        }
        if (active) SocketCleanup.await(TimeUnit.SECONDS.toNanos(IO_QUIESCENCE_TIMEOUT_SECONDS)) { ioQuiesced.await(it, TimeUnit.NANOSECONDS) }
        val cleanup = SocketCleanup()
        if (!reaped) {
            cleanup.attempt {
            reap()
            reaped = true
        }
        }
        if (!channelClosed) {
            cleanup.attempt {
            closeChannel()
            channelClosed = true
        }
        }
        if (!endpointClosed) {
            cleanup.attempt {
            closeEndpoint()
            endpointClosed = true
        }
        }
        cleanup.rethrow()
    }
}
