package demo

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionEnd
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("01 — From unrestricted behavior to kernel restrictions")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EnforcementPresentationTest {
    @Test
    @Order(1)
    @DisplayName("01.1 — Without a contract, untrusted input can launch a process")
    fun unrestricted() = isolated("unrestrictedBody")

    @Test
    @Order(2)
    @DisplayName("01.2 — The permanent process baseline prevents that shell effect")
    fun processContract() = isolated("processBody")

    @Test
    @Order(3)
    @DisplayName("01.3 — Seccomp removes network access while ordinary work still succeeds")
    fun networkContract() = isolated("networkBody")

    @Test
    @Order(4)
    @DisplayName("01.4 — Landlock narrows file access to the declared workspace")
    fun filesystemContract() = isolated("filesystemBody")

    private fun isolated(body: String) {
        check(Platform.isSupported()) { "The presentation requires Linux Seccomp and Landlock; this is not a skipped demo" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
    }

    @Suppress("unused")
    fun unrestrictedBody() {
        val result = runUnsafe()
        assertIs<ScenarioOutcome.Completed>(result.outcome)
        assertTrue(result.passed, result.render())
        println("FEATURE: unrestricted trusted code handling malicious input\n${result.render()}")
        println("NEXT: declare the OS behavior this operation actually needs. This payload is synthetic, not a real Log4j CVE fixture.")
    }

    @Suppress("unused")
    fun processBody() {
        DemoFixture.create("presentation-no-exec").use { fixture ->
            // This command has no external input and can only create our acquired marker.
            val command = ProcessBuilder("sh", "-c", "printf escaped > '${fixture.marker.absolutePath}'")
            OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
                val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), DomainLimits(1, 1))
                assertEquals("ordinary work", OwnedDemoRuntime.await(runtime.submit(domain, Callable { "ordinary work" })))
                val denied = runtime.submit(domain, Callable { command.start() })
                val failure = assertFailsWith<ExecutionException> { denied.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }
                val violation = assertIs<ContainmentViolationException>(failure.cause)
                val cause = assertIs<java.io.IOException>(violation.cause)
                assertTrue(Regex("\\berror[=:]\\s*(1|13)\\b").containsMatchIn(cause.message.orEmpty()), cause.toString())
                assertIs<ExecutionEnd.Finished>(denied.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                kotlin.test.assertFalse(fixture.marker.exists())
                println("RESULT: ordinary work succeeds; shell launch has native errno1/13 and no marker effect")
                println("EVIDENCE: INFERRED_PERMISSION_FAILURE; the controlled operation is denied, not a universal RCE claim")
            }
        }
        println("CLEANUP: owned runtime and marker fixture retired\nNEXT: narrower worker contracts can also remove network access.")
    }

    @Suppress("unused")
    fun networkBody() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            listener.soTimeout = 2_000
            Socket(loopback, listener.localPort).use { listener.accept().close() }
            println("CONTROL: the exact loopback destination is reachable before containment")
            OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                    .base(Policy.NO_NETWORK).build()
                val domain = runtime.prepare(policy, DomainLimits(1, 1))
                assertEquals(42, OwnedDemoRuntime.await(runtime.submit(domain, Callable { 6 * 7 })))
                val denied = runtime.submit(domain, Callable {
                    Socket().use { it.connect(InetSocketAddress(loopback, listener.localPort), 2_000) }
                })
                val failure = assertFailsWith<ExecutionException> {
                    denied.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
                assertIs<ContainmentViolationException>(failure.cause)
                assertIs<ExecutionEnd.Finished>(denied.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                println("RESULT: calculation = 42; connecting to the reachable destination fails under NO_NETWORK")
                println("EVIDENCE: ${io.mazewall.enforcer.diagnostics.ContainmentViolationDetector.diagnose(failure)?.evidence}")
            }
        }
        assertTrue(OwnedDemoRuntime.drainRetained().isEmpty())
        println("CLEANUP: listener closed and owned runtime drained\nNEXT: syscall rules do not describe which file may be read; use Landlock.")
    }

    @Suppress("unused")
    fun filesystemBody() {
        WorkspaceExample.main(emptyArray())
        println("NEXT: discover required behavior by profiling the actual workload.")
    }
}
