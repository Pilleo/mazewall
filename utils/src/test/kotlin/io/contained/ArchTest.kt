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
}
