package io.mazewall.demo.agent

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Supervision
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("presentation")
@DisplayName("04 — Permit legitimate tools, reject the same capability from the wrong context")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentPresentationTest {
    @Test
    @Order(1)
    @DisplayName("04.1 — A reachable private HTTP service works without the contract; supervised SSRF fails")
    fun privateNetwork() = isolated("privateNetworkBody")

    @Test
    @Order(2)
    @DisplayName("04.2 — Authorized analysis launches; the web tool cannot borrow that authority")
    fun authorizedCallPath() = isolated("authorizedBody")

    private fun isolated(body: String) {
        check(Platform.isSupported()) { "Custom syscall supervision requires Linux native support" }
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
    }

    private fun openRuntime(): ManagedRuntime {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        Supervision.prepareSupervisor()
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec().block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
        return ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, Duration.ofSeconds(30), Duration.ofSeconds(5))
    }

    @Suppress("unused")
    fun privateNetworkBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        val serverFailure = AtomicReference<Throwable>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val server = Thread.ofPlatform().name("presentation-owned-http").start {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        while (!input.readLine().isNullOrEmpty()) { /* consume the real HTTP headers */ }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nfixture".toByteArray(StandardCharsets.US_ASCII))
                    }
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                }
            }
            try {
                AgentDemoFixture.create().use { fixture ->
                    val url = "http://127.0.0.1:${listener.localPort}/internal"
                    AgentTools(null, fixture.workspace).use { comparison -> assertEquals("fixture", comparison.fetchWebpage(url)) }
                    server.join(5_000)
                    assertFalse(server.isAlive)
                    serverFailure.get()?.let { throw it }
                    // The listener remains bound: refusal must come from the unchanged private-address classifier.
                    openRuntime().use { runtime ->
                        AgentTools(runtime, fixture.workspace).use { protected ->
                            val denied = assertFailsWith<ContainmentViolationException> { protected.fetchWebpage(url) }
                            assertEquals(io.mazewall.enforcer.api.ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
                                io.mazewall.enforcer.diagnostics.ContainmentViolationDetector.diagnose(denied)?.evidence)
                            assertEquals("SUPER_SECRET_WORKSPACE_TOKEN_12345", protected.analyzeUserFile("secret.txt"))
                        }
                    }
                    println("RESULT: same acquired private destination; comparison HTTP succeeds, custom CONNECT denies; workspace read succeeds")
                }
            } finally {
                listener.close()
                server.join(5_000)
                assertFalse(server.isAlive, "owned HTTP helper must physically end")
            }
        }
        println("CLEANUP: HTTP helper, listener, tool workers and private fixture retired\nNEXT: destination checks are one rule; exact call-path authorization is another.")
    }

    @Suppress("unused")
    fun authorizedBody() {
        openRuntime().use { runtime ->
            AgentDemoFixture.create().use { fixture ->
                AgentTools(runtime, fixture.workspace).use { tools ->
                    assertEquals("analysis-ok\n", tools.executeDataAnalysis("echo analysis-ok"))
                    val denied = assertFailsWith<ContainmentViolationException> { tools.fetchWebpage("http://example.invalid/run_shell") }
                    assertEquals(io.mazewall.enforcer.api.ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
                        io.mazewall.enforcer.diagnostics.ContainmentViolationDetector.diagnose(denied)?.evidence)
                    val traversal = assertFailsWith<ContainmentViolationException> { tools.analyzeUserFile("../passwd") }
                    assertTrue(traversal.message.orEmpty().isNotEmpty())
                    println("RESULT: executeAuthorizedAnalysis may launch; fetchWebpage spawn denied; workspace traversal denied")
                }
            }
        }
        println("CLEANUP: approved child and owned domains retired\nLIMIT: this is experimental call-path supervision, not shared-heap isolation\nNEXT: use a separate process for the bounded guest service.")
    }
}
