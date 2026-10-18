package io.mazewall.consumer.koog

import ai.koog.serialization.typeToken
import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.KotlinxSerializer
import io.mazewall.demo.koog.TextTransformerService
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.demo.koog.TextArguments
import io.mazewall.demo.koog.TextTools
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalStateLostException
import io.mazewall.portal.PortalServiceHealthState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Serializable
data class ProbeArguments(val value: String)

/** Disposable native executable: every required failure is fatal to this probe, never a skip. */
fun main() = runBlocking {
    val directory = Path.of("").toAbsolutePath()
    val forbidden = directory.resolve("sibling-secret.txt")
    Files.writeString(forbidden, "HOST_SECRET_NEVER_RETURNED")
    val cache = directory.resolve("worker-cache")
    check(!Files.exists(cache)) { "fresh probe must start with an empty worker cache" }
    val config = TextTools.configuration(cache)
    check(config.limits.maxRequestBytes == 131_072 && config.limits.maxResponseBytes == 131_072)
    check(config.workerArtifacts.isNotEmpty())
    var observedTool: PortalToolAdapter<TextArguments, String>? = null
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val registry = TextTools.registry(runtime)
        captureReadyWorkers(runtime, workers)
        check(registry.tools.map { it.name } == listOf("uppercase_ascii"))
        check(registry.getToolOrNull("test-denied") == null)
        val tool = registry.getTool<PortalToolAdapter<TextArguments, String>>()
        observedTool = tool
        check(tool.operationId() == "uppercase_ascii")
        val ready = tool.diagnostics()
        check(ready.state == ExecutionDiagnosticState.READY && ready.generation is ExecutionGeneration.PortalInputs)
        check(ready.invocations.admitted == 0L && ready.liveWorkers == 1 && !ready.retirementComplete)
        val serializer = KotlinxSerializer(kotlinx.serialization.json.Json { ignoreUnknownKeys = false })
        val decoded = tool.decodeArgs(serializer.decodeJSONElementFromString("{\"text\":\"abc\"}") as JSONObject, serializer)
        check(decoded == TextArguments("abc"))
        expectFailure<kotlinx.serialization.SerializationException> {
            tool.decodeArgs(serializer.decodeJSONElementFromString("{}") as JSONObject, serializer)
        }
        expectFailure<kotlinx.serialization.SerializationException> {
            tool.decodeArgs(serializer.decodeJSONElementFromString("{\"text\":\"abc\",\"policy\":\"unrestricted\"}") as JSONObject, serializer)
        }
        check(registry.getTool("uppercase_ascii") === tool)
        check(tool.execute(TextArguments("aBc éß🙂")) == "ABC éß🙂")
        check(tool.diagnostics().invocations.admitted == 1L)
        check(ready.invocations.admitted == 0L)
        check(tool.execute(TextArguments("x".repeat(65_536))) == "X".repeat(65_536))
        check(tool.execute(TextArguments("é".repeat(32_768))) == "é".repeat(32_768))
        expectFailure<IllegalArgumentException> { tool.execute(TextArguments("é".repeat(32_769))) }
        // Selector-looking content is ordinary data and confers no policy/operation authority.
        check(tool.execute(TextArguments("policy=unrestricted;operation=read")) == "POLICY=UNRESTRICTED;OPERATION=READ")
        val raw = runtime.prepare(TextTransformerService::class.java, PortalServiceId("bounded-text"),
            PortalOperationCall<TextTransformerService, String, String> { service, text -> service.uppercaseAscii(text) })
        val wireRejected = raw.submit("x".repeat(131_072))
        val wireFailure = expectFailure<ExecutionException> { wireRejected.result().toCompletableFuture().get(10, TimeUnit.SECONDS) }
        check((wireFailure.cause as PortalCallException).evidence == PortalInvocationEvidence.NOT_STARTED)
        val wireEnd = wireRejected.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS)
        check(wireEnd is ExecutionEnd.NotStarted && wireEnd.failure === wireFailure.cause)
        expectFailure<ClassNotFoundException> { Class.forName("io.mazewall.demo.koog.worker.AsciiTextTransformer") }
        expectFailure<ClassNotFoundException> { Class.forName("io.mazewall.portal.TestPreparedOperations") }
        val denied = boundProbe(runtime, "test_read") { service, args -> service.read(args.value) }
        captureReadyWorkers(runtime, workers)
        verifyFilesystemContainment(runtime, directory)
        check(tool.execute(TextArguments("after filesystem denial")) == "AFTER FILESYSTEM DENIAL")
        val denial = expectFailure<PortalRemoteCallException> { denied.execute(ProbeArguments(forbidden.toString())) }
        check(denial.remoteType == "java.nio.file.AccessDeniedException") { "unexpected remote failure: $denial" }
        check(denial.evidence == PortalInvocationEvidence.COMPLETED)
        check(denial.message?.contains("HOST_SECRET_NEVER_RETURNED") != true)
        check(Files.readString(forbidden) == "HOST_SECRET_NEVER_RETURNED")
        // A completed business denial keeps the session usable; a crash is terminal.
        expectFailure<PortalCallException> { denied.execute(ProbeArguments(forbidden.toString())) }
        val oversized = boundProbe(runtime, "test_oversized") { service, _ -> service.oversizedText() }
        expectFailure<IllegalArgumentException> { oversized.execute(ProbeArguments("")) }
        // An application-size result rejection is completed, not state loss or a replay.
        expectFailure<PortalRemoteCallException> { denied.execute(ProbeArguments(forbidden.toString())) }
        verifyNetworkAndProcessContainment(runtime)
        check(tool.execute(TextArguments("after network and process denial")) == "AFTER NETWORK AND PROCESS DENIAL")
        val crash = boundProbe(runtime, "test_crash") { service, _ -> service.crash() }
        val ownerBefore = runtime.health().single { it.isolationGroup == "test-denied" }
        check(ownerBefore.state == PortalServiceHealthState.AVAILABLE && ownerBefore.workerPids.size == 1)
        val childrenBefore = ProcessHandle.current().children().use { it.map(ProcessHandle::pid).toList() }
        val lost = expectFailure<PortalCallException> { crash.execute(ProbeArguments("")) }
        check(lost.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION)
        expectFailure<PortalStateLostException> { denied.execute(ProbeArguments(forbidden.toString())) }
        val ownerAfter = runtime.health().single { it.isolationGroup == "test-denied" }
        check(ownerAfter.state == PortalServiceHealthState.STATE_LOST)
        check(ownerAfter.lastWorkerLifecycle.pid == ownerBefore.workerPids.single())
        val childrenAfter = ProcessHandle.current().children().use { it.map(ProcessHandle::pid).toList() }
        check(childrenAfter.all { it in childrenBefore }) { "terminal session must not reconstruct or replay" }
        check(tool.execute(TextArguments("independent")) == "INDEPENDENT")
    }
    val closed = checkNotNull(observedTool).diagnostics()
    check(closed.state == ExecutionDiagnosticState.CLOSED && closed.retirementComplete && closed.liveWorkers == 0)
    check(closed.invocations.admitted > 0 && closed.invocations.ended > 0)
    // Explicit new runtime is the only way to recreate the test singleton after loss.
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val waiting = boundProbe(runtime, "test_wait") { service, _ -> service.waitForever() }
        captureReadyWorkers(runtime, workers)
        val result = async(start = CoroutineStart.UNDISPATCHED) { waiting.execute(ProbeArguments("")) }
        repeat(100) {
            if (ProcessHandle.current().children().use { it.anyMatch { child -> child.isAlive } }) return@repeat
            delay(20)
        }
        delay(500)
        check(!result.isCompleted)
        result.cancel()
        result.join()
        check(result.isCancelled)
        expectFailure<PortalStateLostException> { waiting.execute(ProbeArguments("")) }
    }
    PortalRuntime.open(config).useWithWorkerRetirement { runtime, workers ->
        val tooLarge = boundProbe(runtime, "test_wire") { service, _ -> service.oversizedWire() }
        captureReadyWorkers(runtime, workers)
        val failure = expectFailure<PortalCallException> { tooLarge.execute(ProbeArguments("")) }
        check(failure.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION)
        expectFailure<PortalStateLostException> { tooLarge.execute(ProbeArguments("")) }
    }
    check(ProcessHandle.current().children().use { it.noneMatch { child -> child.isAlive } }) { "runtime close must account for physical worker retirement" }
    println("KOOG_NATIVE_FRESH_OK registry=upstream typed=generated text=65536 wire=131072 denial=true terminal=true cancellation=true diagnostics=true fallback=false replay=false")
    println("KOOG_CONTAINMENT_V1_OK read=denied write=denied symlink=denied network=denied process=denied controls=pass retirement=pass")
}

private fun captureReadyWorkers(runtime: PortalRuntime, workers: MutableSet<ProcessHandle>) {
    for (health in runtime.health()) {
        for (pid in health.workerPids) {
            val worker = ProcessHandle.of(pid).orElseThrow { IllegalStateException("ready worker $pid disappeared") }
            workers.add(worker)
            check(health.state == PortalServiceHealthState.AVAILABLE && worker.isAlive)
        }
    }
    check(workers.isNotEmpty()) { "expected an actual ready worker" }
}

private inline fun PortalRuntime.useWithWorkerRetirement(action: (PortalRuntime, MutableSet<ProcessHandle>) -> Unit) {
    val workers = mutableSetOf<ProcessHandle>()
    val outcome = runCatching { use { runtime -> action(runtime, workers) } }
    var failure = outcome.exceptionOrNull()
    for (worker in workers) {
        val retirementFailure = runCatching {
            check(!worker.isAlive) { "worker ${worker.pid()} is still alive after runtime close" }
        }.exceptionOrNull()
        if (retirementFailure != null) {
            if (failure == null) failure = retirementFailure else failure.addSuppressed(retirementFailure)
        }
    }
    failure?.let { throw it }
}

internal fun boundProbe(
    runtime: PortalRuntime,
    name: String,
    mapping: (DeniedResourceService, ProbeArguments) -> String,
): PortalToolAdapter<ProbeArguments, String> = PortalToolAdapter(
    runtime.prepare(DeniedResourceService::class.java, PortalServiceId("test-denied"), PortalOperationCall(mapping)),
    typeToken<ProbeArguments>(), typeToken<String>(), name, "Test-only native probe",
    { require(it.value.length <= 4096) }, { require(it.length <= 65_536) },
)

internal suspend inline fun <reified T : Throwable> expectFailure(action: () -> Unit): T {
    try {
        action()
    } catch (failure: Throwable) {
        check(failure is T) { "expected ${T::class.java.name}, got $failure" }
        return failure
    }
    error("expected ${T::class.java.name}")
}
