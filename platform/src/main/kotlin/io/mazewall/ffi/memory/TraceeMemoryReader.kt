package io.mazewall.ffi.memory

import io.mazewall.LinuxNative
import io.mazewall.core.Pid
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.map
import io.mazewall.onFailure
import io.mazewall.onSuccess
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Shared utility for reading memory and resolving paths from remote processes/threads using process_vm_readv and readlink.
 */
public interface TraceeMemoryReader {
    context(arena: NativeArena)
    public fun readString(
        tid: Tid,
        remoteAddr: Long,
        maxLen: Int = 4096,
    ): String?

    context(arena: NativeArena)
    public fun readBytes(
        tid: Tid,
        remoteAddr: Long,
        len: Int,
    ): ByteArray?

    context(arena: NativeArena)
    public fun resolveLink(
        tid: Tid,
        link: String,
    ): String?

    public companion object Real : TraceeMemoryReader {
        context(arena: NativeArena)
        override fun readString(
            tid: Tid,
            remoteAddr: Long,
            maxLen: Int,
        ): String? {
            if (remoteAddr == 0L) return null
            val bytes = readBytes(tid, remoteAddr, maxLen) ?: return null
            var len = 0
            var hasNullTerminator = false
            while (len < bytes.size) {
                if (bytes[len] == 0.toByte()) {
                    hasNullTerminator = true
                    break
                }
                len++
            }
            if (!hasNullTerminator) {
                val preview = bytes.take(64).joinToString("") { "%02X".format(it) }
                throw IllegalStateException(
                    "Remote string from TID ${tid.value} at address 0x${remoteAddr.toString(16)} lacks null terminator within $maxLen bytes. Read: $len bytes. Hex preview: $preview"
                )
            }
            return String(bytes, 0, len, StandardCharsets.UTF_8)
        }

        context(arena: NativeArena)
        override fun readBytes(
            tid: Tid,
            remoteAddr: Long,
            len: Int,
        ): ByteArray? {
            if (remoteAddr == 0L) return null
            val localBuf = arena.allocate(len.toLong())
            localBuf.fill(0)
            val localIov = IovecSegment(arena.allocate(Layouts.IOVEC).unwrap)
            localIov.setIovBase(localBuf.unwrap)
            localIov.setIovLen(len.toLong())
            val remoteIov = IovecSegment(arena.allocate(Layouts.IOVEC).unwrap)
            remoteIov.setIovBase(MemorySegment.ofAddress(remoteAddr))
            remoteIov.setIovLen(len.toLong())

            var res: LinuxNative.SyscallResult<Long, *>
            while (true) {
                res = LinuxNative.memory.processVmReadv(Pid(tid.value), ConfinedSegment(localIov.segment), 1, ConfinedSegment(remoteIov.segment), 1, 0)
                if (res is LinuxNative.SyscallResult.Error && res.errno == io.mazewall.ffi.NativeConstants.EINTR) {
                    continue
                }
                break
            }
            return if (res is LinuxNative.SyscallResult.Success && res.value > 0) {
                val bytesRead = res.value.toInt()
                val dest = ByteArray(bytesRead)
                MemorySegment.copy(localBuf.unwrap, ValueLayout.JAVA_BYTE, 0L, dest, 0, bytesRead)
                dest
            } else {
                if (res is LinuxNative.SyscallResult.Error && res.errno == NativeConstants.EPERM) {
                    throw IllegalStateException(
                        "Permission denied reading memory from TID ${tid.value} at address 0x${remoteAddr.toString(16)}"
                    )
                } else {
                    null
                }
            }
        }

        private const val PATH_MAX_VAL = 4096L

        context(arena: NativeArena)
        override fun resolveLink(
            tid: Tid,
            link: String,
        ): String? {
            val procPath = "/proc/${tid.value}/$link"
            val pathSeg = arena.allocateFrom(procPath)
            val buf = arena.allocate(PATH_MAX_VAL)
            val res =
                LinuxNative.fileSystem.readlink(ConfinedSegment(pathSeg.unwrap), ConfinedSegment(buf.unwrap), PATH_MAX_VAL)

            return res.onSuccess { }.map { buf.unwrap.copyToString(it.toInt()).removeSuffix(" (deleted)") }.getOrNull()
        }

        private fun MemorySegment.copyToString(len: Int): String {
            val bytes = this.asSlice(0L, len.toLong()).toArray(ValueLayout.JAVA_BYTE)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }
}
