package io.mazewall.ffi.networking

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor

internal class SupervisorSocketInputStream(
    socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    arena: NativeArena,
) : NativeDescriptorInputStream(socketFd, arena, "supervisor socket")
