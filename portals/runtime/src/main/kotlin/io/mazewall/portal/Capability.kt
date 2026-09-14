package io.mazewall.portal

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.Deadline
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Broker-granted resources. These are not live host Java objects; they become
 * `SCM_RIGHTS` descriptors on the wire.
 */
public class Capability private constructor() {
    /**
     * Sole owner of a granted descriptor, shared by every in-process wrapper alias.
     * Borrow keeps the descriptor alive through an operation. Close waits for borrows, rejects
     * later borrows, and is idempotent after native retirement; failed pre-native close is retryable.
     * A descriptor borrowed by a callback must never escape that callback.
     */
    public class ReadFd internal constructor(
        private val fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership.Owned>,
    ) : AutoCloseable {
        private val lock = ReentrantLock()
        private var closed = false
        private var retired = false
        internal val isRetired: Boolean get() = lock.withLock { retired }

        /** The descriptor must not escape this borrow. Close waits until the action returns. */
        internal fun <T> borrow(action: (FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>) -> T): T =
            lock.withLock {
            check(!closed) { "capability is closed" }
            action(fd)
        }

        internal fun <T> borrowUntil(
            deadline: Deadline,
            cancelled: () -> Boolean,
            action: (FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>) -> T,
        ): T {
            PortalAdmission.acquire(lock, deadline, cancelled)
            try {
                check(!closed) { "capability is closed" }
                return action(fd)
            } finally {
                lock.unlock()
            }
        }

        override fun close() =
            lock.withLock {
            check(lock.holdCount == 1) { "cannot close capability from its active borrow" }
            if (!retired) {
                closed = true
                val result = io.mazewall.platform.native.LinuxNative.fileSystem
                    .close(fd)
                retired = true // Native result is terminal, even errno: its integer may already be reused.
                result.getOrThrow("close portal capability")
            }
        }
    }

    public companion object {
        /**
         * Retire every supplied owner and return the owners whose close failed before native
         * dispatch. Callers must retain [CapabilityRetirementOutcome.retained] and retry them.
         */
        @Suppress("TooGenericExceptionCaught")
        internal fun retireAll(capabilities: List<ReadFd>): CapabilityRetirementOutcome {
            val failures = mutableListOf<CleanupFailure>()
            capabilities.forEachIndexed { index, capability ->
                try {
                    capability.close()
                } catch (next: Throwable) {
                    failures += CleanupFailure("portal capability[$index]", next)
                }
            }
            return CapabilityRetirementOutcome(
                failures = failures.toList(),
                retained = capabilities.filterNot(ReadFd::isRetired),
            )
        }

        /** Release every in-process owner, preserving fatal failures and avoiding skipped descriptors. */
        @io.mazewall.platform.lifecycle.MazewallInternal
        @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // Cleanup must drain all owners and prefer fatal failures.
        internal fun closeAll(capabilities: List<ReadFd>) {
            retireAll(capabilities).failureOrNull()?.let { throw it }
        }

        /** Adopt a granted FD received by the worker. Not a host `InputStream`. */
        @JvmStatic
        internal fun readFd(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership.Owned>): ReadFd = ReadFd(fd)
    }
}

internal data class CapabilityRetirementOutcome(
    val failures: List<CleanupFailure>,
    val retained: List<Capability.ReadFd>,
) {
    val complete: Boolean get() = failures.isEmpty() && retained.isEmpty()

    fun failureOrNull(): Throwable? = CleanupOutcome(failures).failureOrNull()
}
