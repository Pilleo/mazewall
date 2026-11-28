package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.syscall.Arch
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAt2HowValidationTest {
    private val arch = Arch.AMD64
    private val truncatingFlags = 513L // O_WRONLY | O_TRUNC

    @Test
    fun `invalid openat2 how is refused before an open plan`() {
        assertDenied(size = 0, flags = truncatingFlags, errno = NativeConstants.EINVAL)
        assertDenied(size = 23, flags = truncatingFlags, errno = NativeConstants.EINVAL)
        assertDenied(size = 24, flags = (1L shl 32) or truncatingFlags, errno = NativeConstants.EINVAL)
        assertDenied(size = 32, flags = truncatingFlags, tail = 1, errno = NativeConstants.E2BIG)
        assertDenied(size = 4097, flags = truncatingFlags, errno = NativeConstants.E2BIG)

        val valid = plan(size = 24, flags = 0)
        val opened = assertIs<SupervisorInjectionPlan.Open>(valid)
        assertEquals(0L, SupervisorOpenPlan.create(opened.request).flags)

        val extended = plan(size = 32, flags = 0, tail = 0)
        assertIs<SupervisorInjectionPlan.Open>(extended)
    }

    private fun assertDenied(
        size: Long,
        flags: Long,
        errno: Int,
        tail: Int = 0,
    ) {
        val decision = plan(size, flags, tail)
        val denied = assertIs<SupervisorInjectionPlan.Deny>(decision)
        assertEquals(errno, denied.errno)
    }

    private fun plan(
        size: Long,
        flags: Long,
        tail: Int = 0,
    ): SupervisorInjectionPlan {
        val how = openHow(size, flags, tail)
        val args = longArrayOf(-100, 0x1000, 0x2000, size, 0, 0)
        val extracted =
            SupervisorArgumentExtractor.extract(
                arch.openat2,
                Tid(1),
                args,
                arch,
                readString = { _, _ -> "relative-file" },
                readBytes = { _, _, length -> how.copyOf(length.coerceAtMost(how.size)) },
            )
        return SupervisorInjectionPlan.create(arch.openat2, arch, args, extracted)
    }

    private fun openHow(
        size: Long,
        flags: Long,
        tail: Int,
    ): ByteArray {
        val length = maxOf(size, Layouts.OPEN_HOW_SIZE).coerceAtMost(64).toInt()
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(Layouts.OPEN_HOW_FLAGS_OFFSET.toInt(), flags)
        if (length > Layouts.OPEN_HOW_SIZE.toInt()) bytes[Layouts.OPEN_HOW_SIZE.toInt()] = tail.toByte()
        return bytes
    }
}
