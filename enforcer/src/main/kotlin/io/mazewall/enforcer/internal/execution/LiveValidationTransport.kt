package io.mazewall.enforcer.internal.execution

import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole

internal interface LiveReceiverRetirement {
    fun physicallyExited(): Boolean

    fun awaitPhysicalExit()

    fun failure(): Throwable?
}

/** Original-domain one-shot authority for a validation connection. */
internal sealed interface LiveValidationTransport {
    val socketPath: String

    fun registerReceiver(
        receiver: LiveReceiverRetirement,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    )

    fun requireCurrent()

    fun install(socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): LiveObservationEndpoint

    fun send(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: ConfinedDeadline,
    )
}

private class OriginalLiveValidationTransport(
    private val domain: OwnedDomain,
) : LiveValidationTransport {
    override val socketPath: String get() = domain.liveWorkerAdmission.liveValidationSocketPath(this)

    override fun registerReceiver(
        receiver: LiveReceiverRetirement,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
    ) = domain.liveWorkerAdmission.registerReceiver(this, receiver, socket)

    override fun requireCurrent() = domain.liveWorkerAdmission.requireLiveValidation(this)

    override fun install(socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>): LiveObservationEndpoint = domain.liveWorkerAdmission.installObservation(this, socket)

    override fun send(
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        deadline: ConfinedDeadline,
    ) = domain.liveWorkerAdmission.consumeLiveValidation(this, socket, deadline)
}

internal fun newLiveValidationTransport(domain: OwnedDomain): LiveValidationTransport = OriginalLiveValidationTransport(domain)

internal class LiveObservationEndpoint internal constructor(
    private val domain: OwnedDomain,
    internal val slot: LiveWorkerSlot,
    internal val socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
) {
    fun observe(tid: Int): Boolean = domain.liveTaskAdmission.observeLiveTask(this, tid)

    fun revoke() = domain.closeLiveAdmission(this)
}
