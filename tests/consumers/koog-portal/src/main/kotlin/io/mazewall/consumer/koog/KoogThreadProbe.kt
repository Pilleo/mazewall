package io.mazewall.consumer.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Serializable
private data class ThreadReadArguments(val path: String)

/** Required native qualification runs only in this fresh JVM, never in Gradle or Portal. */
fun main() = runBlocking {
    val directory = Path.of("").toAbsolutePath()
    val allowed = Files.createDirectory(directory.resolve("allowed"))
    val note = Files.writeString(allowed.resolve("note.txt"), "public é🙂")
    val hostOnly = Files.writeString(directory.resolve("host-only.txt"), "HOST_ONLY_CANARY")
    val maximum = Files.writeString(allowed.resolve("maximum.txt"), "é".repeat(32_768))
    val oversized = Files.writeString(allowed.resolve("oversized.txt"), "x".repeat(65_537))
    val malformed = Files.write(allowed.resolve("malformed.txt"), byteArrayOf(0xc3.toByte(), 0x28))
    val observedWorkers = ConcurrentHashMap.newKeySet<Thread>()
    val budget = Duration.ofSeconds(10)
    val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build()
    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
        val domain = runtime.prepare(policy, DomainLimits(1, 8))
        val entries = AtomicInteger()
        val bodyPid = AtomicReference<Long>()
        val caller = Thread.currentThread()
        val tool = ThreadToolAdapter<ThreadReadArguments, String>(
            runtime, domain, typeToken<ThreadReadArguments>(), typeToken<String>(),
            "read_note", "Read at most 64 KiB of strict UTF-8 from the fixed allowed directory.",
            validateArguments = {
                require(it.path.isNotBlank() && '\u0000' !in it.path)
                require(it.path.toByteArray(Charsets.UTF_8).size <= 4096)
            },
            validateResult = {
                check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
                require(it.toByteArray(Charsets.UTF_8).size <= 65_536)
            },
            action = {
                val actualWorker = Thread.currentThread()
                check(actualWorker !== caller && !actualWorker.isVirtual)
                observedWorkers.add(actualWorker)
                bodyPid.set(ProcessHandle.current().pid())
                entries.incrementAndGet()
                readBoundedUtf8(Path.of(it.path))
            },
        )
        val registry = ToolRegistry { tool(tool) }
        check(registry.tools.map { it.name } == listOf("read_note"))
        check(registry.getTool("read_note") === tool)
        check(tool.operationId() == "read_note")
        val initial = tool.diagnostics()
        check(initial.state == ExecutionDiagnosticState.READY)
        check(initial.generation is ExecutionGeneration.InProcess)
        check(initial.generation == ExecutionGeneration.InProcess(domain.generation))
        check(initial.invocations.admitted == 0L)
        check(tool.diagnostics() == initial && entries.get() == 0)
        val serializer = KotlinxSerializer(kotlinx.serialization.json.Json { ignoreUnknownKeys = false })
        val arguments = tool.decodeArgs(
            serializer.decodeJSONElementFromString("{\"path\":\"${note}\"}") as JSONObject, serializer,
        )
        check(arguments == ThreadReadArguments(note.toString()))
        val result = registry.getTool<ThreadToolAdapter<ThreadReadArguments, String>>().execute(arguments)
        check(result == "public é🙂")
        check(ProcessHandle.current().pid() == bodyPid.get())
        check(entries.get() == 1 && tool.diagnostics().invocations.admitted == 1L)
        check(initial.invocations.admitted == 0L)
        val denied = threadFailure<ContainmentViolationException> { tool.execute(ThreadReadArguments(hostOnly.toString())) }
        check(denied.cause is AccessDeniedException)
        check(denied.evidence == ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE)
        check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
        println("THREAD_FS allowed=public_é🙂 denied=${denied.cause!!::class.java.name} evidence=${denied.evidence}")

        verifyThreadSocket(runtime, domain)
        check(tool.execute(arguments) == "public é🙂")
        verifyThreadCallers(tool, arguments, hostOnly)
        val validationTool = ThreadToolAdapter<ThreadReadArguments, String>(
            runtime, domain, typeToken<ThreadReadArguments>(), typeToken<String>(), "validation_control", "Test-only validation isolation",
            { check(Files.readString(hostOnly) == "HOST_ONLY_CANARY") },
            { check(Files.readString(hostOnly) == "HOST_ONLY_CANARY") },
            { readBoundedUtf8(Path.of(it.path)) },
        )
        verifyThreadCallers(validationTool, arguments, hostOnly)

        val beforeInvalid = tool.diagnostics().invocations.admitted
        val beforeEntries = entries.get()
        for (invalid in listOf("", " ", "bad\u0000path", "x".repeat(4097), "é".repeat(2048) + "x")) {
            threadFailure<IllegalArgumentException> { tool.execute(ThreadReadArguments(invalid)) }
        }
        threadFailure<SerializationException> {
            tool.decodeArgs(serializer.decodeJSONElementFromString("{\"path\":\"note.txt\",\"policy\":\"unrestricted\"}") as JSONObject, serializer)
        }
        check(tool.diagnostics().invocations.admitted == beforeInvalid && entries.get() == beforeEntries)
        check(tool.execute(ThreadReadArguments(maximum.toString())) == "é".repeat(32_768))
        val beforeOversized = entries.get()
        threadFailure<IllegalArgumentException> { tool.execute(ThreadReadArguments(oversized.toString())) }
        check(entries.get() == beforeOversized + 1)
        threadFailure<CharacterCodingException> { tool.execute(ThreadReadArguments(malformed.toString())) }
        verifyThreadResultAndFailure(runtime, domain)

        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { foreign ->
            val foreignDomain = foreign.prepare(policy, DomainLimits(1, 8))
            check(foreignDomain.generation == domain.generation)
            observedWorkers.add(foreign.submit(foreignDomain, Callable { Thread.currentThread() }).result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            val wrongOwnerEntries = AtomicInteger()
            val wrongOwner = threadControl(foreign, domain, "wrong_owner") { wrongOwnerEntries.incrementAndGet(); "unexpected" }
            val beforeForeign = foreignDomain.diagnostics().invocations.admitted
            val beforeOwned = tool.diagnostics().invocations.admitted
            threadFailure<IllegalArgumentException> { wrongOwner.execute(arguments) }
            check(wrongOwnerEntries.get() == 0 && entries.get() == beforeOversized + 2)
            check(tool.diagnostics().invocations.admitted == beforeOwned)
            check(foreignDomain.diagnostics().invocations.admitted == beforeForeign)
            check(foreign.drain(budget) == DrainReport.Complete)
        }
        val beforeNested = entries.get()
        val beforeNestedAdmissions = tool.diagnostics().invocations.admitted
        val outerEntries = AtomicInteger()
        val outer = threadControl(runtime, domain, "nested_control") {
            observedWorkers.add(Thread.currentThread())
            outerEntries.incrementAndGet()
            runBlocking { threadFailure<RejectedExecutionException> { tool.execute(arguments) } }
            "nested rejected"
        }
        check(outer.execute(arguments) == "nested rejected")
        check(outerEntries.get() == 1 && entries.get() == beforeNested)
        check(tool.diagnostics().invocations.admitted == beforeNestedAdmissions + 1)
        check(tool.execute(arguments) == "public é🙂")
        println("THREAD_ADMISSION validation=pass result_bytes=65536 oversized=65537 ownership=equal_generation_rejected nested=rejected replay=false")

        verifyThreadCancellation(runtime, runtime.prepare(policy, DomainLimits(1, 2)), observedWorkers)
        check(tool.diagnostics().retirementComplete)
        check(tool.diagnostics().state == ExecutionDiagnosticState.CLOSED)
        val finalEntries = entries.get()
        val finalAdmissions = tool.diagnostics().invocations.admitted
        threadFailure<RejectedExecutionException> { tool.execute(arguments) }
        check(entries.get() == finalEntries && tool.diagnostics().invocations.admitted == finalAdmissions)
        check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
        println("THREAD_RETIREMENT captured=${observedWorkers.size} alive=0 drain=complete closed=rejected")
    }
    println("KOOG_THREAD_V1_OK registry=upstream worker=platform fs=isolated socket=denied caller=unchanged virtual=true unconfined=true validation=pass overload=rejected cancellation=cooperative ownership=checked retirement=complete fallback=false replay=false")
}

internal fun readBoundedUtf8(path: Path): String {
    val bytes = Files.newInputStream(path).use { it.readNBytes(65_537) }
    require(bytes.size <= 65_536) { "File exceeds 64 KiB" }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}

private suspend fun verifyThreadSocket(runtime: ManagedRuntime, domain: InProcessDomain) {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
        listener.soTimeout = 5000
        Socket().use { host ->
            host.connect(listener.localSocketAddress, 5000)
            listener.accept().use { accepted -> check(accepted.isConnected) }
        }
        val entries = AtomicInteger()
        val socket = threadControl(runtime, domain, "socket_control") {
            entries.incrementAndGet()
            Socket().use { it.connect(listener.localSocketAddress, 5000) }
            "unexpected"
        }
        val failure = threadFailure<ContainmentViolationException> { socket.execute(ThreadReadArguments("socket")) }
        check(entries.get() == 1 && failure.cause is SocketException)
        check(failure.evidence == ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE)
        check(Regex("(?i)\\b(operation not permitted|permission denied)\\b").containsMatchIn(failure.cause?.message.orEmpty()))
        println("THREAD_SOCKET host=connected body_entries=1 cause=${failure.cause!!::class.java.name} message=${failure.cause!!.message} evidence=${failure.evidence}")
    }
}

private suspend fun verifyThreadCallers(tool: ThreadToolAdapter<ThreadReadArguments, String>, arguments: ThreadReadArguments, hostOnly: Path) {
    val virtualFailure = AtomicReference<Throwable>()
    val virtual = Thread.ofVirtual().start {
        try {
            runBlocking {
                check(Thread.currentThread().isVirtual)
                check(tool.execute(arguments) == "public é🙂")
                check(Thread.currentThread().isVirtual)
                check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
            }
        } catch (failure: Throwable) {
            virtualFailure.set(failure)
        }
    }
    virtual.join(5000)
    check(!virtual.isAlive)
    virtualFailure.get()?.let { throw it }
    withContext(Dispatchers.Unconfined) {
        check(tool.execute(arguments) == "public é🙂")
        check(Files.readString(hostOnly) == "HOST_ONLY_CANARY")
    }
    println("THREAD_CALLERS virtual=unchanged unconfined=unchanged validators=host_readable")
}

private suspend fun verifyThreadResultAndFailure(runtime: ManagedRuntime, domain: InProcessDomain) {
    val oversizedEntries = AtomicInteger()
    val oversized = ThreadToolAdapter<ThreadReadArguments, String>(
        runtime, domain, typeToken<ThreadReadArguments>(), typeToken<String>(), "oversized_control", "Test-only result validation",
        {}, { require(it.toByteArray(Charsets.UTF_8).size <= 65_536) }, { oversizedEntries.incrementAndGet(); "x".repeat(65_537) },
    )
    threadFailure<IllegalArgumentException> { oversized.execute(ThreadReadArguments("result")) }
    check(oversizedEntries.get() == 1)
    val failureEntries = AtomicInteger()
    val original = IllegalStateException("ordinary business failure")
    val failing = threadControl(runtime, domain, "failure_control") { failureEntries.incrementAndGet(); throw original }
    check(threadFailure<IllegalStateException> { failing.execute(ThreadReadArguments("failure")) } === original)
    check(failureEntries.get() == 1)
}

private suspend fun verifyThreadCancellation(runtime: ManagedRuntime, domain: InProcessDomain, observedWorkers: MutableSet<Thread>) = coroutineScope {
    val entered = CountDownLatch(1)
    val cleanupEntered = CountDownLatch(1)
    val releaseBody = CountDownLatch(1)
    val releaseCleanup = CountDownLatch(1)
    val entries = AtomicInteger()
    val bodyInterrupted = AtomicReference<InterruptedException>()
    val heldWorker = AtomicReference<Thread>()
    val heldTool = threadControl(runtime, domain, "held_control") {
        heldWorker.set(Thread.currentThread())
        observedWorkers.add(Thread.currentThread())
        entries.incrementAndGet()
        entered.countDown()
        try {
            check(releaseBody.await(5, TimeUnit.SECONDS))
        } catch (interrupted: InterruptedException) {
            bodyInterrupted.set(interrupted)
            throw interrupted
        } finally {
            cleanupEntered.countDown()
            awaitHeldCleanup(releaseCleanup)
        }
        "released"
    }
    val arguments = ThreadReadArguments("held")
    val call = async(start = CoroutineStart.UNDISPATCHED) { heldTool.execute(arguments) }
    var queued: Deferred<String>? = null
    try {
        check(entered.await(5, TimeUnit.SECONDS))
        queued = async(start = CoroutineStart.UNDISPATCHED) { heldTool.execute(arguments) }
        check(heldTool.diagnostics().invocations.admitted == 2L && entries.get() == 1)
        threadFailure<RejectedExecutionException> { heldTool.execute(arguments) }
        check(heldTool.diagnostics().invocations.admitted == 2L && entries.get() == 1)
        queued.cancel()
        withTimeout(5000) { queued.join() }
        check(queued.isCancelled && entries.get() == 1)
        call.cancel()
        withTimeout(5000) { call.join() }
        check(cleanupEntered.await(5, TimeUnit.SECONDS))
        check(call.isCancelled)
        val held = heldTool.diagnostics()
        check(bodyInterrupted.get() != null && held.invocations.cancellationRequests == 2L)
        check(held.liveWorkers > 0 && held.unfinishedInvocations > 0)
        check(!held.retirementComplete)
        check(runtime.drain(Duration.ofMillis(20)) is DrainReport.Incomplete)
        check(entries.get() == 1 && heldWorker.get().isAlive)
        println("THREAD_CANCELLATION queued_entries=0 running_entries=1 cancelled=${call.isCancelled} interrupted=true requests=${held.invocations.cancellationRequests} live=${held.liveWorkers} unfinished=${held.unfinishedInvocations} retirement=${held.retirementComplete} drain=incomplete")
    } finally {
        releaseBody.countDown()
        releaseCleanup.countDown()
        queued?.cancel()
        call.cancel()
    }
    for (worker in observedWorkers) worker.join(5000)
    check(observedWorkers.none { it.isAlive })
    check(runtime.drain(Duration.ofSeconds(10)) == DrainReport.Complete)
    check(heldTool.diagnostics().retirementComplete)
    check(entries.get() == 1) { "cancelled queued body must never enter, including after cleanup release" }
}

internal fun awaitHeldCleanup(release: CountDownLatch) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var interrupted = false
    try {
        while (true) {
            try {
                check(release.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) { "cleanup release timed out" }
                return
            } catch (_: InterruptedException) {
                // Deliberately hold cooperative lexical cleanup across cancellation and drain interrupts.
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private fun threadControl(runtime: ManagedRuntime, domain: InProcessDomain, name: String, action: (ThreadReadArguments) -> String): ThreadToolAdapter<ThreadReadArguments, String> =
    ThreadToolAdapter(runtime, domain, typeToken<ThreadReadArguments>(), typeToken<String>(), name, "Test-only native control", {}, {}, action)

private suspend inline fun <reified T : Throwable> threadFailure(action: () -> Unit): T {
    try {
        action()
    } catch (failure: Throwable) {
        check(failure is T) { "expected ${T::class.java.name}, got $failure" }
        return failure
    }
    error("expected ${T::class.java.name}")
}
