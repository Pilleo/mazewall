package io.mazewall.consumer.koog

import ai.koog.serialization.typeToken
import ai.koog.prompt.message.MessagePart
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
internal data class AgentReadArguments(val path: String)

/** Fresh thread-only JVM: no process-launch weakening to accommodate Portal. */
fun main() = runBlocking {
    val root = Path.of("").toAbsolutePath()
    val allowed = Files.createDirectory(root.resolve("agent-allowed"))
    val note = Files.writeString(allowed.resolve("note.txt"), "approved agent input")
    val sibling = Files.writeString(root.resolve("agent-secret.txt"), "HOST_AGENT_CANARY")
    val workers = ConcurrentHashMap.newKeySet<Thread>()
    val budget = Duration.ofSeconds(10)
    val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build()
    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
        fun tool(entries: AtomicInteger, limits: DomainLimits = DomainLimits(1, 1), action: (AgentReadArguments) -> String): ThreadToolAdapter<AgentReadArguments, String> {
            val domain = runtime.prepare(policy, limits)
            return ThreadToolAdapter(runtime, domain, typeToken<AgentReadArguments>(), typeToken<String>(), "read_note", "Consumer-only agent read",
                { require(it.path.isNotBlank() && '\u0000' !in it.path && it.path.toByteArray().size <= 4096) },
                { require(it.toByteArray().size <= 65_536) },
                { workers.add(Thread.currentThread()); entries.incrementAndGet(); action(it) })
        }
        val entries = AtomicInteger()
        val read = tool(entries) { readBoundedUtf8(Path.of(it.path)) }
        verifyExecutorRejections(read)
        verifySequenceExecutorRejections(listOf(read))
        verifySequenceHistoryRejections(read.name)
        check(entries.get() == 0 && read.diagnostics().invocations.admitted == 0L)
        AgentAttempt(read, Json.encodeToString(AgentReadArguments(note.toString())), "approved agent input").use { it.verifySuccess() }
        check(entries.get() == 1)
        for ((name, json) in listOf("read_note" to "{}", "read_note" to "not-json", "read_note" to "{\"path\":\"\"}", "unknown_tool" to "{}")) {
            AgentAttempt(read, json, null, name).use { it.verifyFailure() }
        }
        check(entries.get() == 1 && read.diagnostics().invocations.admitted == 1L)
        val deniedEntries = AtomicInteger()
        val denied = tool(deniedEntries) { Files.readString(Path.of(it.path)) }
        AgentAttempt(denied, Json.encodeToString(AgentReadArguments(sibling.toString())), null).use {
            val failure = it.verifyFailure()
            check(failure is ContainmentViolationException && failure.cause is AccessDeniedException)
        }
        check(deniedEntries.get() == 1 && Files.readString(sibling) == "HOST_AGENT_CANARY")
        val oversizedEntries = AtomicInteger()
        val oversized = tool(oversizedEntries) { "x".repeat(65_537) }
        AgentAttempt(oversized, "{\"path\":\"result-control\"}", null).use { check(it.verifyFailure() is IllegalArgumentException) }
        check(oversizedEntries.get() == 1)
        val sequenceEntries = AtomicInteger()
        val sequenceRead = tool(sequenceEntries, DomainLimits(1, 2)) { readBoundedUtf8(Path.of(it.path)) }
        val allowedJson = Json.encodeToString(AgentReadArguments(note.toString()))
        val failures = verifyAgentSequence(listOf(sequenceRead),
            ScriptedToolStep(MessagePart.Tool.Call("denied-1", sequenceRead.name, Json.encodeToString(AgentReadArguments(sibling.toString()))), null),
            ScriptedToolStep(MessagePart.Tool.Call("allowed-2", sequenceRead.name, allowedJson), "approved agent input"))
        val sequenceFailure = failures.single()
        check(sequenceFailure is ContainmentViolationException && sequenceFailure.cause is AccessDeniedException)
        check(sequenceEntries.get() == 2 && Files.readString(sibling) == "HOST_AGENT_CANARY")
        check(verifyAgentSequence(listOf(sequenceRead),
            ScriptedToolStep(MessagePart.Tool.Call("read-1", sequenceRead.name, allowedJson), "approved agent input"),
            ScriptedToolStep(MessagePart.Tool.Call("read-2", sequenceRead.name, allowedJson), "approved agent input")).isEmpty())
        check(sequenceEntries.get() == 4 && sequenceRead.diagnostics().invocations.admitted == 4L)
        println("THREAD_AGENT_SEQUENCE deniedThenAllowed=pass explicitRepeatedSubmissions=2 policy=unchanged")
        check(runtime.drain(budget) == DrainReport.Complete)
    }
    verifyThreadQueuedAgents(allowed, workers)
    verifyAgentCancellation(allowed, workers, false)
    verifyAgentCancellation(allowed, workers, true)
    for (worker in workers) { worker.join(5000); check(!worker.isAlive && !worker.isVirtual) }
    println("KOOG_AGENT_V1_OK boundary=thread loop=real validation=rejected denial=preserved overload=rejected cancellation=cooperative deadline=propagated retirement=complete replay=false")
}

private suspend fun verifyThreadQueuedAgents(allowed: Path, workers: MutableSet<Thread>) = supervisorScope {
    val budget = Duration.ofSeconds(10)
    val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build()
    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val activeEntries = AtomicInteger()
        val unexpectedEntries = AtomicInteger()
        val domain = runtime.prepare(policy, DomainLimits(1, 2))
        val tool = ThreadToolAdapter<AgentReadArguments, String>(runtime, domain, typeToken<AgentReadArguments>(), typeToken<String>(),
            "queued_read", "Test-only queued-agent ownership", {}, {}, { args ->
                workers.add(Thread.currentThread())
                if (args.path != "running") { unexpectedEntries.incrementAndGet(); error("Queued or rejected body entered") }
                activeEntries.incrementAndGet(); entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                "RUNNING"
            })
        AgentAttempt(tool, "{\"path\":\"running\"}", "RUNNING").use { active ->
            AgentAttempt(tool, "{\"path\":\"queued\"}", null).use { waiting ->
                val running = async(start = CoroutineStart.UNDISPATCHED) { active.agent.run("running") }
                var queued: Deferred<String>? = null
                try {
                    withTimeout(5000) {
                        entered.await()
                        val next = async(start = CoroutineStart.UNDISPATCHED) { waiting.agent.run("queued") }
                        queued = next
                        awaitAgentSnapshot(tool, 2, 2)
                        AgentAttempt(tool, "{\"path\":\"third\"}", null).use { check(it.verifyFailure() is RejectedExecutionException) }
                        next.cancel(); next.join()
                        awaitAgentSnapshot(tool, 2, 1)
                        check(next.isCancelled && waiting.executor.calls.get() == 1 && waiting.completions.get() == 0 && waiting.failures.isEmpty())
                        check(activeEntries.get() == 1 && unexpectedEntries.get() == 0 && tool.diagnostics().invocations.cancellationRequests == 1L)
                        release.countDown()
                        check(running.await() == "observed-tool-success")
                        check(active.executor.calls.get() == 2 && active.completions.get() == 1 && active.failures.isEmpty())
                    }
                } finally {
                    release.countDown(); queued?.cancel(); running.cancel()
                }
            }
        }
        check(runtime.drain(budget) == DrainReport.Complete)
        val closed = tool.diagnostics()
        check(closed.retirementComplete && closed.invocations.admitted == 2L && closed.invocations.rejected == 1L)
        check(activeEntries.get() == 1 && unexpectedEntries.get() == 0)
    }
    println("THREAD_AGENT_QUEUE admitted=2 rejected=1 queuedEntries=0 activeResult=RUNNING")
}

private suspend fun verifyAgentCancellation(allowed: Path, workers: MutableSet<Thread>, deadline: Boolean) = supervisorScope {
    val budget = Duration.ofSeconds(10)
    val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build()
    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val bodyRelease = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val entries = AtomicInteger()
        val domain = runtime.prepare(policy, DomainLimits(1, 1))
        val tool = ThreadToolAdapter<AgentReadArguments, String>(runtime, domain, typeToken<AgentReadArguments>(), typeToken<String>(), "held_read", "Test-only held cleanup", {}, {}, {
            workers.add(Thread.currentThread()); entries.incrementAndGet(); entered.complete(Unit)
            try { check(bodyRelease.await(5, TimeUnit.SECONDS)); "released" }
            finally { cleanup.complete(Unit); awaitHeldCleanup(cleanupRelease) }
        })
        AgentAttempt(tool, "{\"path\":\"held\"}", null).use { attempt ->
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                if (deadline) withTimeout(1000) { attempt.agent.run("deadline") } else attempt.agent.run("cancel")
            }
            try {
                withTimeout(5000) { entered.await() }
                AgentAttempt(tool, "{\"path\":\"overload\"}", null).use { check(it.verifyFailure() is RejectedExecutionException) }
                if (!deadline) call.cancel()
                withTimeout(5000) { call.join(); cleanup.await() }
                check(call.isCancelled && attempt.executor.calls.get() == 1 && entries.get() == 1)
                val held = tool.diagnostics()
                check(held.invocations.cancellationRequests == 1L && held.unfinishedInvocations == 1 && !held.retirementComplete)
                check(runtime.drain(Duration.ofMillis(20)) is DrainReport.Incomplete)
            } finally {
                bodyRelease.countDown(); cleanupRelease.countDown(); call.cancel()
            }
        }
        check(runtime.drain(budget) == DrainReport.Complete)
        check(tool.diagnostics().retirementComplete && entries.get() == 1)
    }
}
