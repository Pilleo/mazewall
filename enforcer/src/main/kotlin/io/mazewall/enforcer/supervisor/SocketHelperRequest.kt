package io.mazewall.enforcer.supervisor

/** A counted helper job owns either one imported descriptor or a two-phase authority lease. */
internal sealed interface SocketHelperRequest : AutoCloseable {
    fun execute(helper: SocketHelper): SocketHelperResult

    fun cancel()

    data object Prewarm : SocketHelperRequest {
        override fun execute(helper: SocketHelper): SocketHelperResult = SocketHelperResult.Connected

        override fun cancel() = Unit

        override fun close() = Unit
    }

    class Operation(
        private val operation: SocketOperation,
        private val descriptor: SupervisorOwnedFd,
    ) : SocketHelperRequest {
        override fun execute(helper: SocketHelper): SocketHelperResult = helper.execute(operation, descriptor)

        override fun cancel() = Unit

        override fun close() = descriptor.close()
    }

    class Authority(
        private val job: TraceeAuthorityJob,
    ) : SocketHelperRequest {
        override fun execute(helper: SocketHelper): SocketHelperResult = SocketHelperResult.Authority(helper.executeAuthority(job))

        override fun cancel() = job.close()

        override fun close() = job.close()
    }
}
