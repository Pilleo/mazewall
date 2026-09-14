package io.mazewall.profiler.internal

import io.mazewall.NativeMemory
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.NativeDescriptorInputStream

internal class NativeSocketInputStream(
    socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    arena: NativeArena,
    memory: NativeMemory,
) : NativeDescriptorInputStream(socketFd, arena, "native socket", memory)
