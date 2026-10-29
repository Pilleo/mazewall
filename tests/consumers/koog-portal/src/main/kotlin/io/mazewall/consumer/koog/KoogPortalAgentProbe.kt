package io.mazewall.consumer.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import io.mazewall.demo.koog.TextArguments
import io.mazewall.demo.koog.TextTools
import io.mazewall.demo.koog.TextTransformerService
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalRuntimeConfig
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalStateLostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        verifySequenceExecutorRejections(listOf(denied, text))
        val failures = verifyAgentSequence(listOf(denied, text),
            ScriptedToolStep(MessagePart.Tool.Call("denied-1", denied.name, Json.encodeToString(ProbeArguments(sibling.toString()))), null),
            ScriptedToolStep(MessagePart.Tool.Call("allowed-2", text.name, Json.encodeToString(TextArguments("agent recovery"))), "AGENT RECOVERY"))
        val sequenceFailure = failures.single()
        check(sequenceFailure is PortalRemoteCallException && sequenceFailure.remoteType == "java.nio.file.AccessDeniedException")
        check(sequenceFailure.evidence == PortalInvocationEvidence.COMPLETED && Files.readString(sibling) == "HOST_AGENT_CANARY")
        val repeatJson = Json.encodeToString(TextArguments("same-input"))
        check(verifyAgentSequence(listOf(text),
            ScriptedToolStep(MessagePart.Tool.Call("repeat-1", text.name, repeatJson), "SAME-INPUT"),
            ScriptedToolStep(MessagePart.Tool.Call("repeat-2", text.name, repeatJson), "SAME-INPUT")).isEmpty())
        check(text.diagnostics().invocations.admitted == 4L)
        println("PORTAL_AGENT_SEQUENCE deniedThenAllowed=pass explicitRepeatedSubmissions=2 policy=unchanged")
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
    verifyTerminalAgentSequence(config, sibling)
    verifyPortalQueuedAgents(config)
    verifyPortalAgentCancellation(config, false)
    verifyPortalAgentCancellation(config, true)
    println("KOOG_AGENT_V1_OK boundary=portal loop=real validation=rejected denial=preserved unknown=terminal cancellation=cooperative deadline=propagated retirement=complete replay=false")
    println("KOOG_AGENT_SEQUENCE_V1_OK boundary=portal history=ordered turns=3 deniedThenAllowed=pass repeated=two-explicit-calls unknown=terminal queue=bounded queuedCancellation=not-started retirement=complete")
}

private suspend fun verifyPortalQueuedAgents(base: PortalRuntimeConfig) = supervisorScope {
    val config = PortalRuntimeConfig(
        manifests = base.manifests, workerArtifacts = base.workerArtifacts, workerBundleCache = base.workerBundleCache,
        workerProfiles = base.workerProfiles, cgroupRoot = base.cgroupRoot, grantRoots = base.grantRoots,
        limits = base.limits.copy(maxAdmissions = 2), launchProfiles = base.launchProfiles,
        requiredLaunchProfiles = base.requiredLaunchProfiles,
    )
    val unexpectedMappings = AtomicInteger()
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val activeMappings = AtomicInteger()
        val operation = runtime.prepare(TextTransformerService::class.java, PortalServiceId("bounded-text"),
            PortalOperationCall<TextTransformerService, ProbeArguments, String> { service, args ->
                if (args.value != "running") { unexpectedMappings.incrementAndGet(); error("Queued or rejected mapping entered") }
                activeMappings.incrementAndGet(); entered.complete(Unit)
                // Hold the real host mapping before RPC: mappings and wire calls share admission capacity.
                check(release.await(5, TimeUnit.SECONDS))
                service.uppercaseAscii(args.value)
            })
        val tool = PortalToolAdapter(operation, typeToken<ProbeArguments>(), typeToken<String>(), "queued_transform", "Test-only queued-agent ownership",
            { require(it.value.length <= 4096) }, { require(it.length <= 65_536) })
        captureReadyWorkers(runtime, workers)
        val pids = runtime.health().single { it.isolationGroup == "bounded-text" }.workerPids
        AgentAttempt(tool, "{\"value\":\"running\"}", "RUNNING").use { active ->
            AgentAttempt(tool, "{\"value\":\"queued\"}", null).use { waiting ->
                val running = async(start = CoroutineStart.UNDISPATCHED) { active.agent.run("running") }
                var queued: Deferred<String>? = null
                try {
                    withTimeout(5000) {
                        entered.await()
                        val next = async(start = CoroutineStart.UNDISPATCHED) { waiting.agent.run("queued") }
                        queued = next
                        awaitAgentSnapshot(tool, 2, 2)
                        AgentAttempt(tool, "{\"value\":\"third\"}", null).use {
                            val failure = it.verifyFailure()
                            check(failure is PortalCallException && failure.evidence == PortalInvocationEvidence.NOT_STARTED)
                        }
                        next.cancel(); next.join()
                        awaitAgentSnapshot(tool, 2, 1)
                        check(next.isCancelled && waiting.executor.calls.get() == 1 && waiting.completions.get() == 0 && waiting.failures.isEmpty())
                        check(activeMappings.get() == 1 && unexpectedMappings.get() == 0)
                        release.countDown()
                        check(running.await() == "observed-tool-success")
                        check(active.executor.calls.get() == 2 && active.completions.get() == 1 && active.failures.isEmpty())
                    }
                } finally {
                    release.countDown(); queued?.cancel(); running.cancel()
                }
            }
        }
        val observed = tool.diagnostics()
        check(observed.invocations.admitted == 2L && observed.invocations.rejected == 1L && observed.invocations.cancellationRequests == 1L)
        check(runtime.health().single { it.isolationGroup == "bounded-text" }.workerPids == pids)
    }
    check(unexpectedMappings.get() == 0)
    println("PORTAL_AGENT_QUEUE admitted=2 rejected=1 queuedMappings=0 activeResult=RUNNING")
}

private suspend fun verifyTerminalAgentSequence(config: PortalRuntimeConfig, sibling: Path) {
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val crashEntries = AtomicInteger()
        val lostMappings = AtomicInteger()
        val crash = boundProbe(runtime, "sequence_crash") { service, _ -> crashEntries.incrementAndGet(); service.crash() }
        val denied = boundProbe(runtime, "sequence_lost") { service, args -> lostMappings.incrementAndGet(); service.read(args.value) }
        captureReadyWorkers(runtime, workers)
        val before = runtime.health().single { it.isolationGroup == "test-denied" }
        val children = ProcessHandle.current().children().use { it.map(ProcessHandle::pid).toList() }
        val failures = verifyAgentSequence(listOf(crash, denied),
            ScriptedToolStep(MessagePart.Tool.Call("lost-1", crash.name, "{\"value\":\"\"}"), null),
            ScriptedToolStep(MessagePart.Tool.Call("lost-2", denied.name, Json.encodeToString(ProbeArguments(sibling.toString()))), null))
        val unknown = failures.first()
        check(unknown is PortalCallException && unknown.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION)
        check(failures[1] is PortalStateLostException)
        val after = runtime.health().single { it.isolationGroup == "test-denied" }
        check(after.state == PortalServiceHealthState.STATE_LOST && after.lastWorkerLifecycle.pid == before.workerPids.single())
        check(ProcessHandle.current().children().use { it.allMatch { child -> child.pid() in children } })
        check(crashEntries.get() == 1 && lostMappings.get() <= 1) { "An explicit request must not replay its host mapping" }
    }
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
