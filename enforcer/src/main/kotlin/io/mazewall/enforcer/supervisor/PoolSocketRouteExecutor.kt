package io.mazewall.enforcer.supervisor

import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import java.util.concurrent.CompletableFuture

/** Imports once in the authorized supervisor, then transfers only bounded values and OFD references. */
internal class PoolSocketRouteExecutor(
    private val descriptors: TraceeDescriptorImporter,
    private val session: SocketHelperPool.Session,
) : AutoCloseable {
    fun connect(
        tid: Tid,
        fd: Int,
        address: ByteArray,
    ): CompletableFuture<SupervisorReplyEffect> =
        submit(tid, fd, SocketOperation.Connect(address)) { result ->
            when (result) {
                SocketHelperResult.Connected -> SupervisorReplyEffect.Value(0)
                is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
                is SocketHelperResult.Accepted, is SocketHelperResult.Opened -> {
                    result.dispose()
                    SupervisorReplyEffect.Recycle("Unexpected accepted descriptor for connect")
                }
            }
        }

    fun accept(request: AcceptRequest): CompletableFuture<SupervisorReplyEffect> =
        submit(request.tid, request.fd, SocketOperation.Accept(request.flags)) { result ->
            when (result) {
                SocketHelperResult.Connected -> SupervisorReplyEffect.Recycle("Unexpected connect result for accept")
                is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
                is SocketHelperResult.Opened -> {
                    result.dispose()
                    SupervisorReplyEffect.Recycle("Unexpected opened descriptor for accept")
                }
                is SocketHelperResult.Accepted -> {
                    var transferred = false
                    try {
                        SupervisorReplyEffect.AcceptedSocket(result.descriptor, request, result.address, result.length).also {
                            transferred = true
                        }
                    } finally {
                        if (!transferred) result.descriptor.close()
                    }
                }
            }
        }

    private fun submit(
        tid: Tid,
        fd: Int,
        operation: SocketOperation,
        map: (SocketHelperResult) -> SupervisorReplyEffect,
    ): CompletableFuture<SupervisorReplyEffect> =
        when (val imported = descriptors.import(tid, fd)) {
            is SupervisorDescriptorResult.Denied -> CompletableFuture.completedFuture(SupervisorReplyEffect.Error(imported.errno))
            is SupervisorDescriptorResult.Opened -> session.submit(operation, imported.descriptor).thenApply(map)
        }

    override fun close() = session.close()

    fun cancel() = session.cancel()
}

/** One capacity owner per supervisor JVM, shared across all listener sessions. */
internal object SupervisorSocketHelpers {
    val admission = SupervisorAdmission()
    val pool: SocketHelperPool by lazy { SocketHelperPool(factory = SocketHelperProcess::start) }
}
