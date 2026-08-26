package io.mazewall.profiler.tierE.shim

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** Thrown when a shim call returns failure; carries te_last_error() text. */
public class ShimException(
    public val operation: String,
    public val errorCode: Int,
    lastError: String,
) : RuntimeException("shim $operation failed rc=$errorCode: $lastError")

/**
 * FFM binding to `libtier_e_bpf.so` — the stateless libbpf seam. Single
 * session thread at a time (daemon contract); the shared arena lives as long
 * as the daemon process, which is short-lived by design.
 */
public class LibbpfShim(
    sharedLibraryPath: String = "build/libtier_e_bpf.so",
) : TierEBpfShim {

    private val arena: Arena = Arena.ofShared()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(sharedLibraryPath, arena)
    private val linker: Linker = Linker.nativeLinker()

    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name)
            .orElseThrow { IllegalStateException("symbol $name not found") }
        return linker.downcallHandle(symbol, descriptor)
    }

    private val hLastError: MethodHandle = bind(
        "te_last_error",
        FunctionDescriptor.of(ValueLayout.ADDRESS),
    )
    private val hLoadObject: MethodHandle = bind(
        "te_load_object",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hSetTargetTgid: MethodHandle = bind(
        "te_set_target_tgid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val hAttachSysEnter: MethodHandle = bind(
        "te_attach_sys_enter",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val hAttachUprobe: MethodHandle = bind(
        "te_attach_marker_uprobe",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ),
    )
    private val hAttachUsdt: MethodHandle = bind(
        "te_attach_marker_usdt",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ),
    )
    private val hRingFd: MethodHandle = bind(
        "te_ring_fd",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val hDroppedTotal: MethodHandle = bind(
        "te_dropped_total",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hRingNew: MethodHandle = bind(
        "te_ring_new",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hRingPoll: MethodHandle = bind(
        "te_ring_poll",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val hRingDestroy: MethodHandle = bind(
        "te_ring_destroy",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )
    private val hUnknownCounts: MethodHandle = bind(
        "te_unknown_counts",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hReadPerNr: MethodHandle = bind(
        "te_read_per_nr",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val hDestroy: MethodHandle = bind(
        "te_destroy",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    private fun cString(value: String): MemorySegment = arena.allocateFrom(value)

    private fun lastError(): String {
        val ptr = hLastError.invoke() as MemorySegment
        return if (ptr == MemorySegment.NULL) "" else ptr.reinterpret(256).getString(0)
    }

    private fun rc(operation: String, code: Int) {
        if (code != 0) throw ShimException(operation, code, lastError())
    }

    override fun loadObject(bpfObjectPath: String): Long {
        val handle = hLoadObject.invoke(cString(bpfObjectPath)) as MemorySegment
        if (handle == MemorySegment.NULL) throw ShimException("loadObject", -1, lastError())
        return handle.address()
    }

    override fun setTargetTgid(handle: Long, tgid: Int) =
        rc("setTargetTgid", hSetTargetTgid.invoke(MemorySegment.ofAddress(handle), tgid) as Int)

    override fun attachSysEnter(handle: Long) =
        rc("attachSysEnter", hAttachSysEnter.invoke(MemorySegment.ofAddress(handle)) as Int)

    override fun attachMarkerUprobe(handle: Long, pid: Int, sharedObjectPath: String) =
        rc(
            "attachMarkerUprobe",
            hAttachUprobe.invoke(MemorySegment.ofAddress(handle), pid, cString(sharedObjectPath)) as Int,
        )

    override fun attachMarkerUsdt(handle: Long, pid: Int, sharedObjectPath: String) =
        rc(
            "attachMarkerUsdt",
            hAttachUsdt.invoke(MemorySegment.ofAddress(handle), pid, cString(sharedObjectPath)) as Int,
        )

    override fun ringFd(handle: Long): Int =
        hRingFd.invoke(MemorySegment.ofAddress(handle)) as Int

    override fun droppedTotal(handle: Long): ULong {
        val out = arena.allocate(ValueLayout.JAVA_LONG)
        rc("droppedTotal", hDroppedTotal.invoke(MemorySegment.ofAddress(handle), out) as Int)
        return out.get(ValueLayout.JAVA_LONG, 0).toULong()
    }

    override fun unknownCounts(handle: Long): LongArray {
        val outSeg = arena.allocate(512L * ValueLayout.JAVA_LONG.byteSize())
        val rc = hUnknownCounts.invoke(MemorySegment.ofAddress(handle), outSeg) as Int
        if (rc != 0) throw ShimException("unknownCounts", rc, lastError())
        val result = LongArray(512)
        for (i in 0 until 512) {
            result[i] = outSeg.get(ValueLayout.JAVA_LONG, i.toLong() * ValueLayout.JAVA_LONG.byteSize())
        }
        return result
    }

    /** Reads [unknown_by_nr(0..511), attributed_by_nr(512..1023)] counters. */
    override fun readPerNr(handle: Long): LongArray {
        val out = arena.allocate(1024L * ValueLayout.JAVA_LONG.byteSize())
        val rc = hReadPerNr.invoke(MemorySegment.ofAddress(handle), out) as Int
        if (rc != 0) throw ShimException("readPerNr", rc, lastError())
        val result = LongArray(1024)
        for (idx in 0 until 1024) {
            result[idx] = out.get(ValueLayout.JAVA_LONG, idx.toLong() * ValueLayout.JAVA_LONG.byteSize())
        }
        return result
    }

    override fun destroy(handle: Long) {
        hDestroy.invoke(MemorySegment.ofAddress(handle))
    }

    override fun ringNew(handle: Long): Long {
        val addr = hRingNew.invoke(MemorySegment.ofAddress(handle)) as MemorySegment
        if (addr == MemorySegment.NULL) throw ShimException("ringNew", -1, lastError())
        return addr.address()
    }

    override fun ringPoll(rbHandle: Long, timeoutMs: Int): Int =
        hRingPoll.invoke(MemorySegment.ofAddress(rbHandle), timeoutMs) as Int

    override fun ringDestroy(rbHandle: Long) {
        hRingDestroy.invoke(MemorySegment.ofAddress(rbHandle))
    }
}
