package io.contained

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchTest {

    @Test
    fun testCurrentPlatformIsDetected() {
        val osArch = System.getProperty("os.arch")
        if (osArch in listOf("amd64", "x86_64", "aarch64", "arm64")) {
            val arch = Arch.current()
            assertNotNull(arch)
            assertTrue(arch.name == "amd64" || arch.name == "aarch64")
        }
    }

    @Test
    fun `amd64 has memfd_create syscall number 319`() {
        assertEquals(319, Arch.AMD64.memfdCreate)
    }

    @Test
    fun `aarch64 has memfd_create syscall number 279`() {
        assertEquals(279, Arch.AARCH64.memfdCreate)
    }

    @Test
    fun `MEMFD_CREATE syscall resolves correctly for each arch`() {
        assertEquals(319, Syscall.MEMFD_CREATE.numberFor(Arch.AMD64))
        assertEquals(279, Syscall.MEMFD_CREATE.numberFor(Arch.AARCH64))
    }

    @Test
    fun `all amd64 syscall numbers are non-negative except fork-variants on aarch64`() {
        val amd64 = Arch.AMD64
        for (syscall in Syscall.entries) {
            val nr = syscall.numberFor(amd64)
            assertTrue(nr >= 0, "$syscall should have a valid number on amd64, got $nr")
        }
    }

    @Test
    fun `aarch64 unavailable syscalls return -1`() {
        val aarch64 = Arch.AARCH64
        assertEquals(-1, Syscall.FORK.numberFor(aarch64))
        assertEquals(-1, Syscall.VFORK.numberFor(aarch64))
        assertEquals(-1, Syscall.OPEN.numberFor(aarch64))
    }

    @Test
    fun `critical security syscalls are available on all architectures`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            assertTrue(Syscall.EXECVE.numberFor(arch) >= 0, "EXECVE missing on ${arch.name}")
            assertTrue(Syscall.EXECVEAT.numberFor(arch) >= 0, "EXECVEAT missing on ${arch.name}")
            assertTrue(Syscall.MPROTECT.numberFor(arch) >= 0, "MPROTECT missing on ${arch.name}")
            assertTrue(Syscall.MMAP.numberFor(arch) >= 0, "MMAP missing on ${arch.name}")
            assertTrue(Syscall.CLONE.numberFor(arch) >= 0, "CLONE missing on ${arch.name}")
            assertTrue(Syscall.PRCTL.numberFor(arch) >= 0, "PRCTL missing on ${arch.name}")
        }
    }

    @Test
    fun `test Arch constants and audit identifiers`() {
        assertNotNull(Arch.AMD64)
        assertNotNull(Arch.AARCH64)
        assertEquals("amd64", Arch.AMD64.name)
        assertEquals("aarch64", Arch.AARCH64.name)

        assertEquals(0xC000003E.toInt(), Arch.AUDIT_ARCH_X86_64)
        assertEquals(0xC00000B7.toInt(), Arch.AUDIT_ARCH_AARCH64)

        assertTrue(Arch.AMD64.audit != 0)
        assertTrue(Arch.AARCH64.audit != 0)
    }

    @Test
    fun `test Arch companion access`() {
        // Exercise the Companion property access for coverage
        val comp = Arch.Companion
        assertNotNull(comp.AMD64)
        assertNotNull(comp.AARCH64)

        // This exercises the current() branch logic better
        val current = Arch.current()
        assertNotNull(current)
    }
}
