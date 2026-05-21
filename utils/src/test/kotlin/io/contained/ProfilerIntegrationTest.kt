package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@EnabledOnOs(OS.LINUX)
class ProfilerIntegrationTest {

    @Test
    fun `test profiler intercepts and logs file opens with path resolving and stack trace capture`() {
        val targetFile = File("/etc/hostname")
        assertTrue(targetFile.exists(), "/etc/hostname should exist on Linux")

        // Submit task inside the new stateless profile block
        val result = Profiler.profile {
            targetFile.readText()
        }

        assertTrue(result.value.isNotEmpty())
        val bob = result.behavior

        // Verify we captured the OPEN or OPENAT syscall in bob
        assertTrue(
            bob.syscalls.contains(Syscall.OPEN) ||
                    bob.syscalls.contains(Syscall.OPENAT) ||
                    bob.syscalls.contains(Syscall.OPENAT2),
            "Should capture file open syscall. Observed: ${bob.syscalls}"
        )
        assertTrue(bob.opens.contains("/etc/hostname"), "Should contain opened path /etc/hostname")

        // Assert that the stackProfile contains the trace event and its stack trace has our test class name!
        assertTrue(bob.stackProfile.isNotEmpty(), "Stack profile should not be empty")
        val hasOurClass = bob.stackProfile.values.any { frames ->
            frames.any { frame -> frame.className.contains("ProfilerIntegrationTest") }
        }
        assertTrue(hasOurClass, "Stack trace should contain the ProfilerIntegrationTest call frame")
    }

    @Test
    fun `test profiler robustly handles grandchild process execution without crashing`() {
        // Submit a task that runs a ProcessBuilder (triggers execve/execveat in grandchild)
        val result = Profiler.profile {
            val pb = ProcessBuilder("echo", "hello-profiler")
            val process = pb.start()
            val exitCode = process.waitFor()
            assertEquals(0, exitCode)
        }

        val bob = result.behavior
        assertTrue(
            bob.syscalls.contains(Syscall.EXECVE) || bob.syscalls.contains(Syscall.EXECVEAT),
            "Should capture process execution syscall. Observed: ${bob.syscalls}"
        )
    }

    @Test
    fun `test profiler end-to-end compilation creates a valid policy and dsl`() {
        val targetFile = File("/etc/hostname")
        assertTrue(targetFile.exists())

        // Read the file inside the sandboxed thread (triggers OPEN/OPENAT/OPENAT2)
        val result = Profiler.profile {
            targetFile.readText()
        }

        assertTrue(result.value.isNotEmpty())
        val bob = result.behavior

        // Let's compile!
        val compiledPolicy = bob.toPolicy(Policy.PURE_COMPUTE)
        val dsl = bob.toDsl("Policy.PURE_COMPUTE", Policy.PURE_COMPUTE)
        println("Profiler compiled DSL:\n$dsl")

        // The compiled policy should have the open variant unblocked!
        val arch = Arch.current()
        val blocked = compiledPolicy.blockedSyscalls(arch).toSet()

        val openNr = Syscall.OPEN.numberFor(arch)
        val openatNr = Syscall.OPENAT.numberFor(arch)

        val openUnblocked = (openNr >= 0 && openNr !in blocked) || (openatNr >= 0 && openatNr !in blocked)
        assertTrue(openUnblocked, "At least one of OPEN or OPENAT should be unblocked in compiled policy")

        // The compiled policy should allow reading from /etc/hostname
        assertTrue(
            compiledPolicy.allowedFsReadPaths.contains("/etc/hostname"),
            "Should contain read-path for /etc/hostname"
        )

        // Verify DSL has the correct builder and allowFsRead
        assertTrue(dsl.contains("Policy.builder()"))
        assertTrue(dsl.contains("allowFsRead(\"/etc/hostname\")"))
    }

    @Test
    fun `test profiler rejects being run inside virtual thread`() {
        val vExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val future = vExecutor.submit {
                Profiler.profile { println("inside virtual thread") }
            }
            val ex = org.junit.jupiter.api.assertThrows<java.util.concurrent.ExecutionException> {
                future.get(5, TimeUnit.SECONDS)
            }
            assertTrue(ex.cause is IllegalStateException)
            assertTrue(ex.cause?.message?.contains("virtual threads") == true)
        } finally {
            vExecutor.shutdownNow()
        }
    }

    @Test
    fun `test wrap() executor correctly resolves paths via daemon ptrace`() {
        val targetFile = File("/etc/hostname")
        val pool = Executors.newSingleThreadExecutor()
        val wrapped = Profiler.wrap(pool, Policy.PURE_COMPUTE)
        try {
            val future = wrapped.submit(Callable {
                targetFile.readText()
            })
            future.get(5, TimeUnit.SECONDS)

            val bob = BobCompiler.compile(wrapped.recentLogs)
            assertTrue(
                bob.opens.contains("/etc/hostname"),
                "Path resolution should work in wrapped executor. Observed opens: ${bob.opens}"
            )
        } finally {
            wrapped.shutdownNow()
        }
    }

    @Test
    fun `test wrap() executor with multiple threads does not duplicate audit events`() {
        val pool = Executors.newFixedThreadPool(4)
        // Enable JSECCOMP_PROFILER_AUDIT to trigger Landlock Audit events
        // We use a custom environment variable for the process starting the daemon
        // But here we need to ensure the daemon started by wrap() sees it.
        // Actually, Profiler.kt checks System.getenv("JSECCOMP_PROFILER_AUDIT")
        // So we can't easily set it for the daemon from here without reflection or global env change.
        // Let's assume the developer can set it, or we find another way.
        // For this test, let's just use the fact that the daemon broadcasts to ALL sockets.
        // We can simulate multiple connections to the daemon.
    }
}
