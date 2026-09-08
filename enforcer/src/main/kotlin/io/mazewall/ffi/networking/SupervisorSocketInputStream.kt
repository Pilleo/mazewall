package io.mazewall.ffi.networking

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.NativeArena

internal class SupervisorSocketInputStream(
    socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    arena: NativeArena,
) : NativeDescriptorInputStream(socketFd, arena, "supervisor socket")
