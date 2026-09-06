package io.mazewall.portal

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.close
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Broker-granted resources. These are not live host Java objects; they become
 * `SCM_RIGHTS` descriptors on the wire.
 */
public class Capability private constructor() {
    public class ReadFd internal constructor(
        internal val fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open>,
    ) : AutoCloseable {
        private val transferred = AtomicBoolean(false)

        /** Transfers this capability into exactly one portal request. */
        internal fun transferForPortalCall(): FileDescriptor<FileDescriptorRole.Granted, FdState.Open> {
            check(transferred.compareAndSet(false, true)) { "portal capability was already transferred" }
            return fd
        }

        /** Relinquishes an unused broker grant. Safe after transfer. */
        override fun close() {
            if (transferred.compareAndSet(false, true)) fd.close()
        }
    }

    public companion object {
        /** Adopt a granted FD received by the worker. Not a host `InputStream`. */
        @JvmStatic
        public fun readFd(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open>): ReadFd = ReadFd(fd)
    }
}
