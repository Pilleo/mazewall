package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

/**
 * Worker-side builtins. The broker must never call this; it only exists so the
 * hand-written worker loop can dispatch before KotlinPoet exists.
 */
@Suppress("DMI_HARDCODED_ABSOLUTE_FILENAME")
internal object PortalBuiltinDispatch {
    fun handle(
        methodId: Int,
        payload: ByteArray,
        fds: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>,
    ): ByteArray =
        when (methodId) {
            PortalMethods.ECHO -> payload
            PortalMethods.CHECKSUM -> checksum(fds.single())
            PortalMethods.SLEEP -> {
                val ms =
                    if (payload.size >= 4) {
                        ((payload[0].toInt() and 0xff) shl 24) or
                            ((payload[1].toInt() and 0xff) shl 16) or
                            ((payload[2].toInt() and 0xff) shl 8) or
                            (payload[3].toInt() and 0xff)
                    } else {
                        60_000
                    }
                Thread.sleep(ms.toLong().coerceAtLeast(0))
                ByteArray(0)
            }

            PortalMethods.TRY_OPEN_HOST_PASSWD -> {
                java.io.FileInputStream("/etc/passwd").use { it.read() }
                error("worker opened /etc/passwd")
            }

            else -> error("unknown method $methodId")
        }

    private fun checksum(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open>): ByteArray {
        val adler = Adler32()
        NativeArena.ofConfined().use { arena ->
            val buf = arena.allocate(4096)
            while (true) {
                when (val res = LinuxNative.memory.read(fd, buf, 4096)) {
                    is LinuxNative.SyscallResult.Error<*> -> error("checksum read errno=${res.errno}")
                    is LinuxNative.SyscallResult.Success -> {
                        if (res.value <= 0L) break
                        val chunk = ByteArray(res.value.toInt())
                        for (i in chunk.indices) {
                            chunk[i] = buf.readByte(i.toLong())
                        }
                        adler.update(chunk)
                    }
                }
            }
        }
        val out = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        out.putInt(adler.value.toInt())
        return out.array()
    }
}
