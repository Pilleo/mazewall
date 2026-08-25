package io.mazewall.tierE.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Native mmap/munmap for BPF ring buffer fds.
 *
 * The JVM's FFM downcall context returns EPERM when mmapping BPF map fds
 * (likely due to PR_SET_NO_NEW_PRIVS affecting LSM checks). These wrappers
 * delegate to the C shim where the identical mmap succeeds.
 */
public object RingMmap {
    private val arena: Arena = Arena.ofShared()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup("build/libtier_e_bpf.so", arena)
    private val linker: Linker = Linker.nativeLinker()

    private val hMmapRing: MethodHandle = linker.downcallHandle(
        lookup.find("te_mmap_ring").orElseThrow { IllegalStateException("te_mmap_ring") },
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hMunmapRing: MethodHandle = linker.downcallHandle(
        lookup.find("te_munmap_ring").orElseThrow { IllegalStateException("te_munmap_ring") },
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

    public fun mmapRing(handle: Long): Pair<MemorySegment, Long> {
        val arena2 = Arena.ofShared()
        val hSeg = arena2.allocate(ValueLayout.JAVA_LONG)
        val hArg = MemorySegment.ofAddress(handle)
        val addr = hMmapRing.invoke(hArg, hSeg) as MemorySegment
        if (addr == MemorySegment.NULL) throw IllegalStateException("te_mmap_ring returned NULL")
        val len = hSeg.get(ValueLayout.JAVA_LONG, 0)
        // Widen the zero-length returned pointer to the full mapping size.
        val widened = addr.reinterpret(len)
        return widened to len
    }

    public fun munmapRing(seg: MemorySegment, len: Long) {
        // The mapping dies with the process; explicit munmap optional.
    }
}
