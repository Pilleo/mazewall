package io.mazewall.profiler.engine

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.map
import io.mazewall.onFailure
import io.mazewall.onSuccess
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Interface for reading memory and resolving paths from a tracee process or thread.
 */
interface ProfilerMemoryReader {
    context(arena: Arena)
    fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String?

    context(arena: Arena)
    fun resolveLink(
        tid: Tid,
        link: String,
    ): String?
}

/**
 * Real implementation of [ProfilerMemoryReader] using process_vm_readv and readlink.
 */
object RealMemoryReader : ProfilerMemoryReader {
    private const val PATH_MAX_VAL = 4096L
    private const val IOV_LEN_OFF = 8L

    context(arena: Arena)
    override fun readStringFromProcess(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int,
    ): String? {
        return io.mazewall.ffi.memory.SupervisorProcessMemoryReader.readString(tid, remoteAddr, maxLen, warnOnEperm = true)
    }

    context(arena: Arena)
    override fun resolveLink(
        tid: Tid,
        link: String,
    ): String? {
        val procPath = "/proc/${tid.value}/$link"
        val pathSeg = arena.allocateFrom(procPath)
        val buf = arena.allocate(PATH_MAX_VAL)
        val res = LinuxNative.withTransaction {
            LinuxNative.fileSystem.readlink(pathSeg, buf, PATH_MAX_VAL)
        }
        return res.onSuccess { }.map { buf.copyToString(it.toInt()).removeSuffix(" (deleted)") }.getOrNull()
    }

    private fun MemorySegment.copyToString(len: Int): String {
        val bytes = this.asSlice(0L, len.toLong()).toArray(ValueLayout.JAVA_BYTE)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
