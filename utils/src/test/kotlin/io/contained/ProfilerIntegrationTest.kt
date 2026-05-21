package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@EnabledOnOs(OS.LINUX)
class ProfilerIntegrationTest {

    @Test
    fun `test profiler intercepts and logs file opens with path resolving via process_vm_readv`() {
        if (!Platform.isSupported()) return

        val baseExecutor = Executors.newSingleThreadExecutor()
        // Create a policy blocking file opens
        val openPolicy = Policy.builder()
            .block(Syscall.OPEN, Syscall.OPENAT)
            .build()

        val profilerExecutor = Profiler.wrap(baseExecutor, openPolicy)

        try {
            val targetFile = File("/etc/hostname")
            assertTrue(targetFile.exists(), "/etc/hostname should exist on Linux")

            // Submit a task that opens and reads /etc/hostname inside the sandboxed thread
            val future = profilerExecutor.submit(java.util.concurrent.Callable {
                targetFile.readText()
            })

            // Wait for completion. Under Profiler, the daemon intercepts it, reads the path, logs it, and continues it.
            val content = future.get(10, TimeUnit.SECONDS)
            assertTrue(content.isNotEmpty())

            // Polling loop for trace events
            awaitTrace { event ->
                (event.syscallName == "OPEN" || event.syscallName == "OPENAT") &&
                        event.paths.contains("/etc/hostname")
            }

        } finally {
            profilerExecutor.shutdownNow()
            baseExecutor.shutdownNow()
        }
    }

    @Test
    fun `test profiler robustly handles grandchild process execution without crashing`() {
        if (!Platform.isSupported()) return

        val baseExecutor = Executors.newSingleThreadExecutor()
        val profilerExecutor = Profiler.wrap(baseExecutor, Policy.NO_EXEC)

        try {
            // Submit a task that runs a ProcessBuilder (triggers execve/execveat in grandchild)
            val future = profilerExecutor.submit {
                val pb = ProcessBuilder("echo", "hello-profiler")
                val process = pb.start()
                val exitCode = process.waitFor()
                assertEquals(0, exitCode)
            }

            // Wait for completion.
            future.get(10, TimeUnit.SECONDS)

            awaitTrace { event ->
                event.syscallName == "EXECVE" || event.syscallName == "EXECVEAT"
            }

        } finally {
            profilerExecutor.shutdownNow()
            baseExecutor.shutdownNow()
        }
    }

    @Test
    fun `test profiler end-to-end compilation creates a valid policy and dsl`() {
        if (!Platform.isSupported()) return

        val baseExecutor = Executors.newSingleThreadExecutor()

        // PURE_COMPUTE blocks file open and network
        val profilerExecutor = Profiler.wrap(baseExecutor, Policy.PURE_COMPUTE)

        Profiler.clear()

        try {
            val targetFile = File("/etc/hostname")
            assertTrue(targetFile.exists())

            // Read the file inside the sandboxed thread (triggers OPEN/OPENAT/OPENAT2)
            val future = profilerExecutor.submit(java.util.concurrent.Callable {
                targetFile.readText()
            })

            val content = future.get(10, TimeUnit.SECONDS)
            assertTrue(content.isNotEmpty())

            awaitTrace { event ->
                (event.syscallName == "OPEN" || event.syscallName == "OPENAT" || event.syscallName == "OPENAT2") &&
                        event.paths.contains("/etc/hostname")
            }

            // Let's compile!
            val compiledPolicy = Profiler.compilePolicy()
            val dsl = Profiler.compileToDsl()
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

        } finally {
            profilerExecutor.shutdownNow()
            baseExecutor.shutdownNow()
        }
    }

    @Test
    fun `test profiler rejects wrapping virtual thread executors`() {
        val vExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val profilerExecutor = Profiler.wrap(vExecutor, Policy.PURE_COMPUTE)
            val future = profilerExecutor.submit { println("running") }
            val ex = org.junit.jupiter.api.assertThrows<java.util.concurrent.ExecutionException> {
                future.get(5, TimeUnit.SECONDS)
            }
            assertTrue(ex.cause is IllegalStateException)
            assertTrue(ex.cause?.message?.contains("virtual threads") == true)
        } finally {
            vExecutor.shutdownNow()
        }
    }

    private fun awaitTrace(timeoutMs: Long = 5000, condition: (TraceEvent) -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (Profiler.recentLogs.any(condition)) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for expected trace event. Current events: ${Profiler.recentLogs}")
    }
}
