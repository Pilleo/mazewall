package io.mazewall.platform.seccomp

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserNotifSharedTest {

    @Test
    fun `encodeContinue writes CONTINUE flag and zero error`() {
        NativeArena.ofConfined().use { arena ->
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            UserNotifReply.encodeContinue(resp, id = 42L)
            assertEquals(42L, resp.readLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET))
            assertEquals(0L, resp.readLong(Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET))
            assertEquals(0, resp.readInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET))
            assertEquals(
                NativeConstants.SECCOMP_USER_NOTIF_FLAG_CONTINUE.toInt(),
                resp.readInt(Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET),
            )
        }
    }

    @Test
    fun `encodeError writes negated errno`() {
        NativeArena.ofConfined().use { arena ->
            val resp = arena.allocate(Layouts.SECCOMP_NOTIF_RESP)
            UserNotifReply.encodeError(resp, id = 7L, errorNr = NativeConstants.EPERM)
            assertEquals(7L, resp.readLong(Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET))
            assertEquals(-1L, resp.readLong(Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET))
            assertEquals(-NativeConstants.EPERM, resp.readInt(Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET))
            assertEquals(0, resp.readInt(Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET))
        }
    }

    @Test
    fun `read parses id pid arch nr and six args`() {
        NativeArena.ofConfined().use { arena ->
            val notif = arena.allocate(Layouts.SECCOMP_NOTIF)
            notif.writeLong(Layouts.SECCOMP_NOTIF_ID_OFFSET, 99L)
            notif.writeInt(Layouts.SECCOMP_NOTIF_PID_OFFSET, 1234)
            notif.writeInt(Layouts.SECCOMP_NOTIF_ARCH_OFFSET, -1073741762)
            notif.writeInt(Layouts.SECCOMP_NOTIF_NR_OFFSET, 257)
            for (i in 0 until 6) {
                notif.writeLong(Layouts.SECCOMP_NOTIF_ARGS_OFFSET + i * 8L, (i + 1).toLong())
            }
            val parsed = SeccompNotifications.read(notif)
            assertEquals(99L, parsed.id)
            assertEquals(1234, parsed.pid)
            assertEquals(-1073741762, parsed.arch)
            assertEquals(257, parsed.nr)
            assertArrayEquals(longArrayOf(1, 2, 3, 4, 5, 6), parsed.args)
        }
    }
}
