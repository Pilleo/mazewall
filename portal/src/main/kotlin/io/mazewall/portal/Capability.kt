package io.mazewall.portal

import io.mazewall.core.Deadline
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Broker-granted resources. These are not live host Java objects; they become
 * `SCM_RIGHTS` descriptors on the wire.
 */
public class Capability private constructor() {
    public class ReadFd internal constructor(
        private val fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership.Owned>,
    ) : AutoCloseable {
        private val lock = ReentrantLock()
        private var closed = false
        private var retired = false

        /** The descriptor must not escape this borrow. Close waits until the action returns. */
        public fun <T> borrow(action: (FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>) -> T): T =
            lock.withLock {
            check(!closed) { "capability is closed" }
            action(fd)
        }

        internal fun <T> borrowUntil(
            deadline: Deadline,
            action: (FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>) -> T,
        ): T {
            if (!lock.tryLock(deadline.remainingMillis().toLong(), TimeUnit.MILLISECONDS)) throw java.io.IOException("capability borrow timed out")
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
                val result = io.mazewall.LinuxNative.fileSystem
                    .close(fd)
                retired = true // Native result is terminal, even errno: its integer may already be reused.
                result.getOrThrow("close portal capability")
            }
        }
    }

    public companion object {
        /** Release every in-process owner, preserving fatal failures and avoiding skipped descriptors. */
        @io.mazewall.MazewallInternal
        @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // Cleanup must drain all owners and prefer fatal failures.
        public fun closeAll(capabilities: List<ReadFd>) {
            var failure: Throwable? = null
            capabilities.forEach { capability ->
                try {
                    capability.close()
                } catch (next: Throwable) {
                    val previous = failure
                    if (previous == null) {
                        failure = next
                    } else if (next is Error && previous !is Error) {
                        next.addSuppressed(previous)
                        failure = next
                    } else if (previous !== next) {
                            previous.addSuppressed(next)
                        }
                }
            }
            failure?.let { throw it }
        }

        /** Adopt a granted FD received by the worker. Not a host `InputStream`. */
        @JvmStatic
        public fun readFd(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership.Owned>): ReadFd = ReadFd(fd)
    }
}
