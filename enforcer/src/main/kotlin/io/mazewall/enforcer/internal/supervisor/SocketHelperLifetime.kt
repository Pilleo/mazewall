package io.mazewall.enforcer.internal.supervisor

/** Stop admission immediately; commit each independent release only after it succeeds. */
internal class SocketHelperLifetime(
    private val reap: () -> Unit,
    private val closeChannel: () -> Unit,
    private val closeEndpoint: () -> Unit,
) : AutoCloseable {
    @Volatile
    var accepting: Boolean = true
        private set

    private var reaped = false
    private var channelClosed = false
    private var endpointClosed = false

    @Synchronized
    override fun close() {
        accepting = false
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
