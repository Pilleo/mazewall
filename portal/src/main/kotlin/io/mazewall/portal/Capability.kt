package io.mazewall.portal

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole

/**
 * Broker-granted resources. These are not live host Java objects; they become
 * `SCM_RIGHTS` descriptors on the wire.
 */
public class Capability private constructor() {
    public class ReadFd internal constructor(
        internal val fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open>,
    )

    public companion object {
        /** Adopt a granted FD received by the worker. Not a host `InputStream`. */
        @JvmStatic
        public fun readFd(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open>): ReadFd = ReadFd(fd)
    }
}
