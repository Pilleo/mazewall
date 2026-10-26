package io.mazewall.consumer.koog

import ai.koog.serialization.typeToken
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
        fun tool(entries: AtomicInteger, action: (AgentReadArguments) -> String): ThreadToolAdapter<AgentReadArguments, String> {
            val domain = runtime.prepare(policy, DomainLimits(1, 1))
            return ThreadToolAdapter(runtime, domain, typeToken<AgentReadArguments>(), typeToken<String>(), "read_note", "Consumer-only agent read",
                { require(it.path.isNotBlank() && '\u0000' !in it.path && it.path.toByteArray().size <= 4096) },
                { require(it.toByteArray().size <= 65_536) },
                { workers.add(Thread.currentThread()); entries.incrementAndGet(); action(it) })
        }
        val entries = AtomicInteger()
        val read = tool(entries) {
            val bytes = Files.newInputStream(Path.of(it.path)).use { input -> input.readNBytes(65_537) }
            require(bytes.size <= 65_536)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        verifyExecutorRejections(read)
        verifySequenceExecutorRejections(listOf(read))
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
        check(runtime.drain(budget) == DrainReport.Complete)
    }
    verifyAgentCancellation(allowed, workers, false)
    verifyAgentCancellation(allowed, workers, true)
    for (worker in workers) { worker.join(5000); check(!worker.isAlive && !worker.isVirtual) }
    println("KOOG_AGENT_V1_OK boundary=thread loop=real validation=rejected denial=preserved overload=rejected cancellation=cooperative deadline=propagated retirement=complete replay=false")
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
