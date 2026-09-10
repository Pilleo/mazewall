package io.mazewall.enforcer.supervisor

/** Serializes retirement against the operation worker and forbids reuse after failed reap. */
internal class SocketHelperSlot(
    private val delegate: SocketHelper,
) : SocketHelper {
    @Volatile private var accepting = true
    private var closed = false

    override fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult {
        check(accepting) { "Socket helper is retiring" }
        return delegate.execute(operation, socket)
    }

    @Synchronized
    override fun close() {
        accepting = false
        if (closed) return
        delegate.close()
        closed = true
    }
}
