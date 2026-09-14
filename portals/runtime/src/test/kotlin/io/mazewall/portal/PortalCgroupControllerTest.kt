package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalCgroupControllerTest {
    @Test
    fun `applies limits before attaching worker pid`() {
        val root = cgroupRoot("memory cpu")
        val profile = PortalWorkerProfile(64L * MIB, 128L * MIB, PortalCpuLimit(Duration.ofMillis(50), Duration.ofMillis(100)))

        val handle = RealPortalCgroupController.createAndAttach(root, 4242L, profile)
        val child = checkNotNull(handle)

        assertEquals("134217728", Files.readString(child.directory.resolve("memory.max")).trim())
        assertEquals("50000 100000", Files.readString(child.directory.resolve("cpu.max")).trim())
        assertEquals("4242", Files.readString(child.directory.resolve("cgroup.procs")).trim())
    }

    @Test
    fun `missing requested controller fails closed`() {
        val failure = assertFailsWith<PortalCgroupUnavailableException> {
            RealPortalCgroupController.createAndAttach(
                cgroupRoot("memory"),
                4242L,
                PortalWorkerProfile(64L * MIB, 128L * MIB, PortalCpuLimit(Duration.ofMillis(50), Duration.ofMillis(100))),
            )
        }

        assertTrue(failure.message!!.contains("cpu"))
    }

    @Test
    fun `unbounded profile does not create a cgroup`() {
        val root = cgroupRoot("memory cpu")

        assertEquals(null, RealPortalCgroupController.createAndAttach(root, 4242L, PortalWorkerProfile()))
        assertEquals(3, Files.list(root).use { it.count() })
    }

    private fun cgroupRoot(controllers: String) =
        Files.createTempDirectory("portal-cgroup").also { root ->
            Files.writeString(root.resolve("cgroup.controllers"), controllers)
            Files.writeString(root.resolve("memory.max"), "max")
            Files.writeString(root.resolve("cpu.max"), "max 100000")
        }

    private companion object {
        const val MIB: Long = 1024L * 1024L
    }
}
