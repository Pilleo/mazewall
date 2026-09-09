package io.mazewall.enforcer.supervisor

/** No tracee pointers or confined segments cross the worker boundary. */
internal sealed interface SocketOperation {
    class Connect(
        address: ByteArray,
    ) : SocketOperation {
        private val bytes = address.copyOf().also { require(it.size in 2..128) }
        val address: ByteArray get() = bytes.copyOf()
    }

    data class Accept(
        val flags: Int,
    ) : SocketOperation
}

internal sealed interface SocketHelperResult {
    data object Connected : SocketHelperResult

    data class Failed(
        val errno: Int,
    ) : SocketHelperResult

    class Accepted(
        val descriptor: SupervisorOwnedFd,
        address: ByteArray,
        val length: Int,
    ) : SocketHelperResult {
        private val bytes = address.copyOf()
        val address: ByteArray get() = bytes.copyOf()
    }
}

/** Close must terminate/reap the independent process, including a blocked execute. */
internal interface SocketHelper : AutoCloseable {
    fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult
}

internal fun SocketHelperResult.dispose() {
    when (this) {
        is SocketHelperResult.Accepted -> descriptor.close()
        SocketHelperResult.Connected, is SocketHelperResult.Failed -> Unit
    }
}
