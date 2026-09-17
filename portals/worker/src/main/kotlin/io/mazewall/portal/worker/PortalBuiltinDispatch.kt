package io.mazewall.portal.worker

import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.native.LinuxNative
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalMethod
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

/**
 * Worker-side builtins. The broker must never call this; it only exists so the
 * hand-written worker loop can dispatch before KotlinPoet exists.
 */

internal object PortalBuiltinDispatch {
    fun handle(
        method: PortalMethod,
        payload: ByteArray,
        fds: List<Capability.ReadFd>,
    ): ByteArray =
        when (method) {
            PortalMethod.Echo -> payload
            PortalMethod.Checksum -> fds.single().borrow { checksum(it) }
            PortalMethod.Sleep -> {
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
            is PortalMethod.Generated -> throw UnknownPortalMethod(method)
        }

    private fun checksum(fd: FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>): ByteArray {
        val adler = Adler32()
        NativeArena.ofConfined().use { arena ->
            val buf = arena.allocate(4096)
            while (true) {
                when (val res = LinuxNative.memory.read(fd, buf, 4096)) {
                    is LinuxNative.SyscallResult.Error<*> -> error("checksum read errno=${res.errno}")
                    is LinuxNative.SyscallResult.Success -> {
                        if (res.value <= 0L) break
                        val chunk = ByteArray(res.value.toInt())
                        ManagedSegment.copy(buf, 0L, chunk, 0, chunk.size)
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

internal class UnknownPortalMethod(
    val method: PortalMethod.Generated,
) : IllegalArgumentException("unknown portal method ${method.wire}")
