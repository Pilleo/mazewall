package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.execution.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * High-level behavioral contract tests for built-in [Policy] presets.
 *
 * These tests exercise the security guarantees at the Java API level, verifying that
 * [Policy] presets block the intended operations on live Linux kernel seccomp filters.
 *
 * For granular per-syscall BPF instruction tests, see:
 * - [io.mazewall.seccomp.AllowListTest]       — allow/block per individual syscall
 * - [io.mazewall.seccomp.MmapProtectionTest]  — PROT_EXEC blocking via BPF argument inspection
 * - [io.mazewall.seccomp.BpfHardeningTest]    — prctl, clone argument inspection sequences
 * - [io.mazewall.seccomp.ProcessContainmentTest] — process-wide and thread-inheritance tests
 */
class SecurityPolicyTest : BaseIntegrationTest() {
    @Test
    fun `PURE_COMPUTE blocks filesystem modifications`() {
        assumeLandlockAbiAtLeast(5)
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyPureCompute")
    }

    fun verifyPureCompute() {
        val timeout = Duration.ofSeconds(10)
        val tempFile = Files.createTempFile("mazewall-test", ".txt").toFile()
        tempFile.writeText("original")

        try {
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.PURE_COMPUTE, DomainLimits(1, 1))
            // Test RENAME (Files.move throws IOException on failure)
            val renameFuture =
                runtime.submit(
                    domain,
                    Callable {
                    val newPath = tempFile.toPath().resolveSibling("renamed-${System.currentTimeMillis()}.txt")
                    Files.move(tempFile.toPath(), newPath)
                },
                )
            val ex =
                org.junit.jupiter.api.assertThrows<ExecutionException> {
                    renameFuture.result().toCompletableFuture().get(10, TimeUnit.SECONDS)
                }
            assertTrue(ex.cause is ContainmentViolationException, "Expected violation for rename, got ${ex.cause}")
            assertIs<ExecutionEnd.Finished>(renameFuture.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))

            // Test MKDIR (Files.createDirectory throws IOException on failure)
            val mkdirFuture =
                runtime.submit(
                    domain,
                    Callable {
                    val dir = tempFile.toPath().resolveSibling("new-dir-${System.currentTimeMillis()}")
                    Files.createDirectory(dir)
                },
                )
            val ex2 =
                org.junit.jupiter.api.assertThrows<ExecutionException> {
                    mkdirFuture.result().toCompletableFuture().get(10, TimeUnit.SECONDS)
                }
            assertTrue(ex2.cause is ContainmentViolationException, "Expected violation for mkdir, got ${ex2.cause}")
            assertIs<ExecutionEnd.Finished>(mkdirFuture.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `NO_NETWORK blocks outbound connect() at the kernel level`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyNoNetwork")
    }

    fun verifyNoNetwork() {
        val timeout = Duration.ofSeconds(10)
        // Load the JDK's actual socket native library before the strict no-exec-mapping worker.
        Socket().use { it.bind(InetSocketAddress("127.0.0.1", 0)) }
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_NETWORK, DomainLimits(1, 1))
            val future =
                runtime.submit(
                    domain,
                    Callable {
                    // Connect to a local port — we don't care if it's listening,
                    // the kernel should block the connect() syscall before TCP handshake.
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", 65432), 500) }
                },
                )
            val ex = org.junit.jupiter.api
                .assertThrows<ExecutionException> { future.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertTrue(
                ex.cause is ContainmentViolationException,
                "Expected a containment violation for connect(), got: ${ex.cause?.javaClass?.name}: ${ex.cause?.message}",
            )
            assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }

    @Test
    fun `NO_EXEC blocks process spawning via ProcessBuilder`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyNoExec")
    }

    fun verifyNoExec() {
        val timeout = Duration.ofSeconds(10)
        // The process floor deliberately permits launch; the original worker preset must deny it.
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(Policy.NO_EXEC, DomainLimits(1, 1))
            val future =
                runtime.submit(
                    domain,
                    Callable {
                    // execve() is blocked by NO_EXEC — ProcessBuilder.start() should fail with EPERM.
                    ProcessBuilder("echo", "should-be-blocked").start()
                },
                )
            val ex = org.junit.jupiter.api
                .assertThrows<ExecutionException> { future.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
            assertTrue(
                ex.cause is ContainmentViolationException,
                "Expected a containment violation for execve(), got: ${ex.cause?.javaClass?.name}: ${ex.cause?.message}",
            )
            assertIs<ExecutionEnd.Finished>(future.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
        }
    }
}
