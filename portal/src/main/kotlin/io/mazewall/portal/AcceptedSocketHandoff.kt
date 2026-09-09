package io.mazewall.portal

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/** Owns an accepted descriptor until exactly one caller claims it or cancellation retires it. */
internal class AcceptedSocketHandoff(
    private val retire: (FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) -> Unit,
) {
    private enum class State { Open, Claimed, Cancelled }

    private var state: State = State.Open
    private var descriptor: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? = null
    private var retiring = false
    private var producerDone = false

    fun publish(accepted: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>) {
        val cancel = synchronized(this) {
            check(descriptor == null) { "accepted descriptor already published" }
            descriptor = accepted
            state == State.Cancelled
        }
        if (cancel) retirePending()
    }

    fun claim(): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>? =
        synchronized(this) {
        if (state != State.Open) return null
        val claimed = descriptor ?: return null
        descriptor = null
        state = State.Claimed
        claimed
    }

    fun cancel(): Boolean {
        synchronized(this) {
            if (state == State.Claimed) return true
            state = State.Cancelled
        }
        return retirePending()
    }

    fun completeProducer() {
        synchronized(this) { producerDone = true }
    }

    val fullyRetired: Boolean
        get() = synchronized(this) { state == State.Claimed || state == State.Cancelled && producerDone && descriptor == null }

    private fun retirePending(): Boolean {
        val pending = synchronized(this) {
            if (retiring) return false
            val owned = descriptor ?: return true
            retiring = true
            owned
        }
        val failure = runCatching { retire(pending) }.exceptionOrNull()
        if (failure != null) {
            synchronized(this) {
                if (failure is io.mazewall.core.NativeCloseFailure && descriptor === pending) descriptor = null
                retiring = false
            }
            throw failure
        }
        synchronized(this) {
            if (descriptor === pending) descriptor = null
            retiring = false
        }
        return true
    }
}
