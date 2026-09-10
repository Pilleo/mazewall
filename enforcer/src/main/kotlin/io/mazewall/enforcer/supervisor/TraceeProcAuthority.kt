package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.NativeEngine
import io.mazewall.core.OpenFlags
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.TraceeProcMemory
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import java.io.ByteArrayOutputStream

internal class TraceeAuthorityFailure(
    val errno: Int,
) : java.io.IOException("Tracee authority errno=$errno")

/** Child-owned proc and pidfd handles. All proc lookups after acquisition stay relative to the pinned task. */
internal class TraceeProcAuthority private constructor(
    private val engine: NativeEngine,
    private val proc: SupervisorOwnedFd,
    private val pidfd: SupervisorOwnedFd,
    val ppid: Int,
) : AutoCloseable {
    private var memory: SupervisorOwnedFd? = null
    private val directories = mutableMapOf<DirectoryKey, SupervisorOwnedFd>()

    private sealed interface DirectoryKey {
        data object Root : DirectoryKey

        data object Cwd : DirectoryKey

        data class Imported(
            val descriptor: TraceeDirFd,
        ) : DirectoryKey
    }

    context(arena: NativeArena) fun directory(
        dirfd: TraceeDirFd,
        absolute: Boolean = false,
    ): SupervisorOwnedFd {
        val key = when {
            absolute -> DirectoryKey.Root
            dirfd.value == NativeConstants.AT_FDCWD -> DirectoryKey.Cwd
            dirfd.value >= 0 -> DirectoryKey.Imported(dirfd)
            else -> throw TraceeAuthorityFailure(NativeConstants.EBADF)
        }
        return directories.getOrPut(key) {
            when (key) {
                DirectoryKey.Root -> openRelative("root", NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)
                DirectoryKey.Cwd -> openRelative("cwd", NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)
                is DirectoryKey.Imported -> import(key.descriptor.value)
            }
        }
    }

    fun import(fd: Int): SupervisorOwnedFd = own(engine.process.pidfdGetFd(pidfd.handle, fd, 0), engine)

    context(arena: NativeArena) fun read(
        address: Long,
        length: Int,
    ): ByteArray {
        readable(address, length)
        val source = memory ?: openRelative("mem", NativeConstants.O_CLOEXEC).also { memory = it }
        return when (val result = TraceeProcMemory(engine).read(source.handle, address, length)) {
            is LinuxNative.SyscallResult.Error -> throw TraceeAuthorityFailure(if (result.errno == NativeConstants.EIO) NativeConstants.EFAULT else result.errno)
            is LinuxNative.SyscallResult.Success -> result.value.also { readable(address, it.size) }
        }
    }

    context(arena: NativeArena) fun readPath(address: Long): String? {
        if (address == 0L) return null
        val output = ByteArrayOutputStream()
        while (output.size() < 4096) {
            val cursor = address + output.size()
            val length = minOf(4096 - output.size(), 4096 - (cursor and 4095L).toInt())
            val bytes = read(cursor, length)
            val end = bytes.indexOf(0)
            output.write(bytes, 0, if (end >= 0) end else bytes.size)
            if (end >= 0) return output.toString(Charsets.UTF_8)
        }
        throw TraceeAuthorityFailure(36)
    }

    /** Check both sides of a forced proc read. Concurrent map mutation is still not an atomic snapshot. */
    context(arena: NativeArena) private fun readable(
        address: Long,
        length: Int,
    ) {
        if (address <= 0 || length <= 0 || address > Long.MAX_VALUE - length) throw TraceeAuthorityFailure(NativeConstants.EFAULT)
        val end = address + length
        var covered = address
        val maps = readFile(proc, "maps", MAX_MAPS_BYTES, engine)
        for (line in maps.lineSequence()) {
            val range = line.substringBefore(' ').split('-')
            val first = range.firstOrNull()?.toLongOrNull(16) ?: continue
            val last = range.getOrNull(1)?.toLongOrNull(16) ?: continue
            if (first <= covered && covered < last) {
                if (!line.substringAfter(' ').trimStart().startsWith('r')) throw TraceeAuthorityFailure(NativeConstants.EFAULT)
                covered = minOf(end, last)
                if (covered == end) return
            }
        }
        throw TraceeAuthorityFailure(NativeConstants.EFAULT)
    }

    context(arena: NativeArena) fun readOnlyNul(): Long {
        val maps = readFile(proc, "maps", MAX_MAPS_BYTES, engine)
        for (line in maps.lineSequence()) {
            val columns = line.split(' ', limit = 3)
            val permissions = columns.getOrNull(1) ?: continue
            if (permissions != "r-xp") continue
            val bounds = columns[0].split('-')
            val start = bounds[0].toLongOrNull(16) ?: continue
            val end = bounds.getOrNull(1)?.toLongOrNull(16) ?: continue
            if (end <= start) continue
            val bytes = read(start, minOf(64L, end - start).toInt())
            val nul = bytes.indexOf(0)
            if (nul >= 0) return start + nul
        }
        return 0
    }

    context(arena: NativeArena) private fun openRelative(
        path: String,
        flags: Int,
    ): SupervisorOwnedFd = own(engine.fileSystem.openat(proc.handle, arena.allocateFrom(path), OpenFlags(flags)), engine)

    override fun close() {
        val cleanup = SocketCleanup()
        directories.values.forEach { cleanup.attempt(it::close) }
        cleanup.attempt { memory?.close() }
        cleanup.attempt(pidfd::close)
        cleanup.attempt(proc::close)
        cleanup.rethrow()
    }

    companion object {
        private const val MAX_STATUS_BYTES = 65536
        private const val MAX_MAPS_BYTES = 4 * 1024 * 1024

        @Suppress("TooGenericExceptionCaught") // Roll back every acquired descriptor on construction failure.
        context(arena: NativeArena) fun capture(tid: Int, engine: NativeEngine): TraceeProcAuthority {
            require(tid > 0)
            val proc = own(engine.fileSystem.open(arena.allocateFrom("/proc/$tid"), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)), engine)
            var pidfd: SupervisorOwnedFd? = null
            try {
                val status = readFile(proc, "status", MAX_STATUS_BYTES, engine)
                val tgid = field(status, "Tgid:")
                val ppid = field(status, "PPid:")
                pidfd = own(engine.process.pidfdOpen(tgid, 0), engine)
                // A surviving pinned /proc/task directory cannot alias a reused task.
                // Recheck after pidfd acquisition before any memory/import operation.
                if (field(readFile(proc, "status", MAX_STATUS_BYTES, engine), "Tgid:") != tgid) throw TraceeAuthorityFailure(3)
                return TraceeProcAuthority(engine, proc, pidfd, ppid)
            } catch (failure: Throwable) {
                val cleanup = SocketCleanup(failure)
                cleanup.attempt { pidfd?.close() }
                cleanup.attempt(proc::close)
                cleanup.rethrow()
                throw failure
            }
        }

        private fun field(
            status: String,
            key: String,
        ): Int =
            status
                .lineSequence()
                .firstOrNull { it.startsWith(key) }
            ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: throw TraceeAuthorityFailure(3)

        context(arena: NativeArena) private fun readFile(
            proc: SupervisorOwnedFd,
            name: String,
            limit: Int,
            engine: NativeEngine,
        ): String =
            own(engine.fileSystem.openat(proc.handle, arena.allocateFrom(name), OpenFlags(NativeConstants.O_CLOEXEC)), engine).use { source ->
                val output = ByteArrayOutputStream()
                val buffer = arena.allocate(4096)
                while (true) {
                    val count = when (val read = engine.memory.read(source.handle, buffer, 4096)) {
                        is LinuxNative.SyscallResult.Error -> throw TraceeAuthorityFailure(read.errno)
                        is LinuxNative.SyscallResult.Success -> read.value.toInt()
                    }
                    if (count == 0) break
                    if (count !in 1..4096 || output.size() + count > limit) throw TraceeAuthorityFailure(75)
                    val bytes = ByteArray(count)
                    ManagedSegment.copy(buffer, 0, bytes, 0, count)
                    output.write(bytes)
                }
                output.toString(Charsets.UTF_8)
            }

        private fun own(
            result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
            engine: NativeEngine,
        ): SupervisorOwnedFd =
            when (val acquired = SupervisorDescriptorResult.from(result, engine)) {
                is SupervisorDescriptorResult.Opened -> acquired.descriptor
                is SupervisorDescriptorResult.Denied -> throw TraceeAuthorityFailure(acquired.errno)
            }
    }
}
