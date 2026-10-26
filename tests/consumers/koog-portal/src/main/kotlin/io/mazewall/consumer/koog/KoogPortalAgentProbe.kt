package io.mazewall.consumer.koog

import io.mazewall.demo.koog.TextArguments
import io.mazewall.demo.koog.TextTools
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalRuntimeConfig
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalStateLostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Independent process-backed agent qualification; never shares the thread probe's no-exec JVM. */
fun main() = runBlocking {
    val root = Path.of("").toAbsolutePath()
    val sibling = Files.writeString(root.resolve("agent-secret.txt"), "HOST_AGENT_CANARY")
    val config = TextTools.configuration(root.resolve("agent-worker-cache"))
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val text = TextTools.transformer(runtime)
        captureReadyWorkers(runtime, workers)
        AgentAttempt(text, Json.encodeToString(TextArguments("agent input")), "AGENT INPUT").use { it.verifySuccess() }
        for ((name, json) in listOf("uppercase_ascii" to "{}", "uppercase_ascii" to "not-json",
            "uppercase_ascii" to Json.encodeToString(TextArguments("x".repeat(65_537))), "unknown_tool" to "{}")) {
            AgentAttempt(text, json, null, name).use { it.verifyFailure() }
        }
        check(text.diagnostics().invocations.admitted == 1L)
        val denied = boundProbe(runtime, "denied_read") { service, args -> service.read(args.value) }
        captureReadyWorkers(runtime, workers)
        AgentAttempt(denied, Json.encodeToString(ProbeArguments(sibling.toString())), null).use {
            val failure = it.verifyFailure()
            check(failure is PortalRemoteCallException && failure.remoteType == "java.nio.file.AccessDeniedException")
            check(failure.evidence == PortalInvocationEvidence.COMPLETED)
        }
        check(Files.readString(sibling) == "HOST_AGENT_CANARY")
        val oversized = boundProbe(runtime, "oversized_result") { service, _ -> service.oversizedText() }
        AgentAttempt(oversized, "{\"value\":\"\"}", null).use { check(it.verifyFailure() is IllegalArgumentException) }
        val crashEntries = AtomicInteger()
        val crash = boundProbe(runtime, "crash_control") { service, _ -> crashEntries.incrementAndGet(); service.crash() }
        val before = runtime.health().single { it.isolationGroup == "test-denied" }
        val childrenBefore = ProcessHandle.current().children().use { it.map(ProcessHandle::pid).toList() }
        AgentAttempt(crash, "{\"value\":\"\"}", null).use {
            val failure = it.verifyFailure()
            check(failure is PortalCallException && failure.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION)
        }
        check(crashEntries.get() == 1)
        AgentAttempt(denied, Json.encodeToString(ProbeArguments(sibling.toString())), null).use {
            check(it.verifyFailure() is PortalStateLostException)
        }
        val after = runtime.health().single { it.isolationGroup == "test-denied" }
        check(after.state == PortalServiceHealthState.STATE_LOST && after.lastWorkerLifecycle.pid == before.workerPids.single())
        check(ProcessHandle.current().children().use { it.allMatch { child -> child.pid() in childrenBefore } })
        AgentAttempt(text, Json.encodeToString(TextArguments("independent")), "INDEPENDENT").use { it.verifySuccess() }
    }
    verifyPortalAgentCancellation(config, false)
    verifyPortalAgentCancellation(config, true)
    println("KOOG_AGENT_V1_OK boundary=portal loop=real validation=rejected denial=preserved unknown=terminal cancellation=cooperative deadline=propagated retirement=complete replay=false")
}

private suspend fun verifyPortalAgentCancellation(config: PortalRuntimeConfig, deadline: Boolean) = supervisorScope {
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val cleanupRelease = CountDownLatch(1)
        val mappings = AtomicInteger()
        val waiting = boundProbe(runtime, "wait_control") { service, _ ->
            mappings.incrementAndGet(); entered.complete(Unit)
            try { service.waitForever() }
            finally { cleanup.complete(Unit); awaitHeldCleanup(cleanupRelease) }
        }
        captureReadyWorkers(runtime, workers)
        AgentAttempt(waiting, "{\"value\":\"\"}", null).use { attempt ->
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                if (deadline) withTimeout(1000) { attempt.agent.run("deadline") } else attempt.agent.run("cancel")
            }
            try {
                withTimeout(5000) { entered.await() }
                if (!deadline) call.cancel()
                withTimeout(5000) { call.join(); cleanup.await() }
                check(call.isCancelled && attempt.executor.calls.get() == 1 && mappings.get() == 1)
                val held = waiting.diagnostics()
                check(held.invocations.cancellationRequests == 1L && held.unfinishedInvocations == 1 && !held.retirementComplete)
                // Mapper entry is observable; it does not authenticate exactly when the child body started.
            } finally {
                cleanupRelease.countDown(); call.cancel()
            }
        }
    } // Canonical close must join the original mapping and retire every captured actual child.
}
