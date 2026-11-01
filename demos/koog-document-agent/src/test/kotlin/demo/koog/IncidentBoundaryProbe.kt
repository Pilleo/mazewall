package demo.koog

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Ordinary control is test-only and creates its own synthetic canary, never a user-data mode. */
object IncidentBoundaryProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && !Thread.currentThread().isVirtual)
        val source = Path.of(args.single()).toRealPath()
        val root = Files.createTempDirectory("mazewall-incident-control-")
        try {
            val bundle = Files.createDirectory(root.resolve("bundle"))
            incidentDocuments.forEach { Files.copy(source.resolve(it), bundle.resolve(it)) }
            val canary = Files.writeString(root.resolve("private-canary.txt"), "PRIVATE_CANARY_MUST_NOT_BE_A_MODEL_RESULT")
            val argument = ReadDocumentArguments("../private-canary.txt")
            validateReadArguments(argument)
            check(DocumentReader.readBoundedDocument(bundle.resolve(argument.path)) == "PRIVATE_CANARY_MUST_NOT_BE_A_MODEL_RESULT")
            println("CONTROL_OUTSIDE_OPEN ordinary identical reader action accessed a synthetic canary; contents withheld")
            val docs = incidentDocuments.associateWith { Files.readString(bundle.resolve(it)) }
            val expected = IncidentReport("Pool capacity was reduced.", listOf(EvidenceRef("application.log", 2)), listOf("Review deployment.md before changing production."))
            val provider = IncidentFixtureExecutor(docs, Json.encodeToString(expected))
            val budget = Duration.ofSeconds(10)
            lateinit var worker: Thread
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                val domain = runtime.prepare(
                    WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowJvmClasspath()
                    .allowFsRead(bundle)
                    .block(Syscall.SOCKET)
                    .build(),
                        DomainLimits(1, 4),
                )
                worker = runtime
                    .submit(domain, Callable { Thread.currentThread() })
                    .result()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                val reader = documentReadTool(runtime, domain, bundle)
                runBlocking {
                    try {
                        reader.execute(argument)
                        error("Protected outside open unexpectedly succeeded")
                    } catch (refused: ContainmentViolationException) {
                        check(refused.cause is AccessDeniedException)
                        println("BOUNDARY_REFUSED actual protected open denied; evidence=INFERRED_PERMISSION_FAILURE; policy unchanged")
                    }
                    val report = runIncidentAgent(runtime, domain, bundle, provider, incidentFixtureModel)
                    check(report == expected)
                    println("MODEL: explicit offline fixture; this is not model injection resistance or a live inference result")
                    println(renderIncidentReport(report))
                }
            }
            worker.join(5000)
            check(!worker.isAlive && !worker.isVirtual)
            check(Files.readString(canary) == "PRIVATE_CANARY_MUST_NOT_BE_A_MODEL_RESULT")
            println("INCIDENT_RETIRED actual platform worker ended; host access unchanged")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
