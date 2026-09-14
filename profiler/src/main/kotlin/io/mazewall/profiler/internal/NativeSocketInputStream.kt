package io.mazewall.profiler.internal

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.NativeDescriptorInputStream
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.NativeMemory

internal class NativeSocketInputStream(
    socketFd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
    arena: NativeArena,
    memory: NativeMemory,
) : NativeDescriptorInputStream(socketFd, arena, "native socket", memory)
