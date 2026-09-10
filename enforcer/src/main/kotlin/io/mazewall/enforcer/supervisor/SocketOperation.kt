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

    /** The accompanying owned descriptor is the directory; no tracee identity is delegated. */
    data class OpenAt2(
        val path: String,
        val flags: Long,
        val mode: Long,
        val resolve: Long,
    ) : SocketOperation {
        init {
            require(!path.startsWith('/') && '\u0000' !in path)
            require(path.toByteArray(Charsets.UTF_8).size <= SocketHelperProtocol.MAX_PATH_BYTES)
        }
    }
}

internal sealed interface SocketHelperResult {
    class Authority(
        val effect: SupervisorReplyEffect,
    ) : SocketHelperResult

    data object Connected : SocketHelperResult

    data class Failed(
        val errno: Int,
    ) : SocketHelperResult

    class Opened(
        val descriptor: SupervisorOwnedFd,
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
    fun executeAuthority(job: TraceeAuthorityJob): SupervisorReplyEffect = error("Helper does not implement tracee authority")

    fun execute(
        operation: SocketOperation,
        socket: SupervisorOwnedFd,
    ): SocketHelperResult
}

internal fun SocketHelperResult.dispose() {
    when (this) {
        is SocketHelperResult.Accepted -> descriptor.close()
        is SocketHelperResult.Opened -> descriptor.close()
        is SocketHelperResult.Authority -> SupervisorReplyHandoff(effect).close()
        SocketHelperResult.Connected, is SocketHelperResult.Failed -> Unit
    }
}
