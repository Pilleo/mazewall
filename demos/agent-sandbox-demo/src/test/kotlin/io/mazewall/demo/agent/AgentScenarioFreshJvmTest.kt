package io.mazewall.demo.agent

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Supervision
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

class AgentScenarioFreshJvmTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `protected legitimate workload succeeds in a fresh JVM`() {
        listOf("legitimateBody", "legitimateFileBody").forEach { body ->
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `protected attacks are denied in fresh JVMs`() {
        listOf("pathTraversalBody", "maliciousRceBody", "ssrfBody").forEach { body ->
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, body)
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `custom public connect succeeds and all agent owned workers physically retire`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "publicConnectBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `incompatible JDK fork launch rejects before agent tool entry in fresh JVM`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "forkBody")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `shutdown cancels owned analysis and waits for actual child and worker death`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "cancellationBody")
    }

    @Test
    fun `unprotected comparison preserves legitimate and attack capabilities`() {
        for (scenario in listOf("file_legit", "path_traversal", "rce_legit", "rce_malicious")) {
            val result = runAgentScenario(scenario, useMazewall = false)
            assertTrue(result.passed, result.render())
        }
    }

    @Suppress("unused")
    fun publicConnectBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        Supervision.prepareSupervisor()
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec()
            .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT)
            .build()
        val timeout = java.time.Duration.ofSeconds(30)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            AgentDemoFixture.create().use { fixture ->
                AgentTools(runtime, fixture.workspace).use { tools ->
                    val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("mazewall-domain-") }
                    assertEquals(3, workers.count { !it.name.endsWith("resource-retirement") })
                    // Numeric public endpoint: no DNS/helper executor can bypass the custom CONNECT route.
                    tools.fetchWebpage("http://1.1.1.1/")
                    assertEquals("owned-process-proof\n", tools.executeDataAnalysis("echo owned-process-proof"))
                    assertEquals("SUPER_SECRET_WORKSPACE_TOKEN_12345", tools.analyzeUserFile("secret.txt"))
                    assertProtectedAttack(AgentScenarioResult("local-connect", ExpectedAgentResult.DENIED, classifyToolOutcome(captureRecoverable {
                        tools.fetchWebpage("http://127.0.0.1:9/")
                    })))
                    assertProtectedAttack(AgentScenarioResult("wrong-exec-stack", ExpectedAgentResult.DENIED, classifyToolOutcome(captureRecoverable {
                        tools.fetchWebpage("http://public.example/run_shell")
                    })))
                    tools.close()
                    workers.forEach { assertFalse(it.isAlive, "actual agent worker must physically retire") }
                }
            }
        }
    }

    @Suppress("unused")
    fun forkBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "fork")
        // FORK-mode launch bypassed these callbacks; glibc may use allowed CLONE, but the fixture is not a syscall trace.
        ProcessBuilder("true").start().let { check(it.waitFor() == 0) }
        val result = runAgentScenario("rce_malicious", useMazewall = true)
        assertFalse(result.passed)
        val failure = kotlin.test.assertIs<AgentOutcome.InfrastructureFailure>(result.outcome)
        assertTrue(failure.cause is IllegalArgumentException)
        assertTrue(failure.cause.message.orEmpty().contains("requires VFORK"))
    }

    @Suppress("unused")
    fun cancellationBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        Supervision.prepareSupervisor()
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec().block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
        val timeout = java.time.Duration.ofSeconds(10)
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            AgentDemoFixture.create().use { fixture ->
                AgentTools(runtime, fixture.workspace).use { tools ->
                    val callers = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val marker = java.io.File(fixture.root, "actual-child-pid")
                    try {
                        val result = callers.submit<String> { tools.executeDataAnalysis("echo $$ > ${marker.absolutePath}; while :; do :; done") }
                        val deadline = System.nanoTime() + timeout.toNanos()
                        while ((!marker.exists() || marker.length() == 0L) && System.nanoTime() < deadline) {
                            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5))
                        }
                        check(marker.exists() && marker.length() > 0L) { "authorized child never entered" }
                        val child = ProcessHandle.of(marker.readText().trim().toLong()).orElseThrow()
                        assertTrue(child.isAlive)
                        val workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("mazewall-domain-") }
                        tools.close()
                        assertFalse(child.isAlive, "actual acquired child must be reaped")
                        workers.forEach { assertFalse(it.isAlive) }
                        val failure = assertFailsWith<java.util.concurrent.ExecutionException> { result.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                        assertTrue(failure.cause is java.util.concurrent.CancellationException)
                    } finally {
                        callers.shutdownNow()
                        assertTrue(callers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
        }
    }

    @Test
    fun `inconclusive attack cannot pass fresh JVM verification`() {
        assertFailsWith<IllegalStateException> {
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "inconclusiveAttackBody")
        }
    }

    @Suppress("unused")
    fun legitimateBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        val result = runAgentScenario("rce_legit", useMazewall = true)
        assertTrue(result.passed, result.render())
    }

    @Suppress("unused")
    fun legitimateFileBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        val result = runAgentScenario("file_legit", useMazewall = true)
        assertTrue(result.passed, result.render())
    }

    @Suppress("unused")
    fun ssrfBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        assertProtectedAttack(runAgentScenario("ssrf", useMazewall = true))
    }

    @Suppress("unused")
    fun pathTraversalBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        assertProtectedAttack(runAgentScenario("path_traversal", useMazewall = true))
    }

    @Suppress("unused")
    fun maliciousRceBody() {
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        assertProtectedAttack(runAgentScenario("rce_malicious", useMazewall = true))
    }

    @Suppress("unused")
    fun inconclusiveAttackBody() {
        assertProtectedAttack(
            AgentScenarioResult(
                "inconclusive",
                ExpectedAgentResult.DENIED,
                AgentOutcome.Inconclusive("INFERRED_PERMISSION_FAILURE"),
            ),
        )
    }

    private fun assertProtectedAttack(result: AgentScenarioResult) {
        when (result.outcome) {
            is AgentOutcome.Denied -> assertTrue(result.passed, result.render())
            is AgentOutcome.Inconclusive -> fail("attack denial is not authenticated: ${result.render()}")
            is AgentOutcome.Completed -> fail("attack unexpectedly succeeded: ${result.render()}")
            is AgentOutcome.InfrastructureFailure -> fail(result.render())
        }
    }
}
