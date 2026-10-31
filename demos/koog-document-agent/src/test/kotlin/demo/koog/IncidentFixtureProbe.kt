package demo.koog

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Explicit fixture provider, actual Koog graph/reader/kernel; never calls a real model. */
object IncidentFixtureProbe {
    @JvmStatic fun main(args: Array<String>) {
        val status = runIncidentCommand { execute(args) }
        if (status != 0) exitProcess(status)
    }

    private fun execute(args: Array<String>) {
        require(args.size == 2 && !Thread.currentThread().isVirtual)
        val bundle = Path.of(args[0]).toRealPath()
        val docs = incidentDocuments.associateWith { Files.readString(bundle.resolve(it)) }
        val hypothesis = if (args[1] == "pool") "Pool maximum changed from20 to2." else "Database port changed from5432 to6543."
        val report = IncidentReport(hypothesis, listOf(EvidenceRef("application.log", 2), EvidenceRef("deployment.md", 3)), listOf("Review the documented deployment change."))
        val json = Json.encodeToString(report).let {
            when (args[1]) {
                "invalid" -> it.replace("application.log", "private.txt")
                "unsafe-key" -> it.replace("\"hypothesis\":", "\"\\u001b[2J\":true,\"hypothesis\":")
                "literal-control" -> it.replace(hypothesis, "\u001b[2Jhidden")
                else -> it
            }
        }
        val provider = IncidentFixtureExecutor(docs, json)
        val budget = Duration.ofSeconds(10)
        lateinit var worker: Thread
        println("MODEL: explicit offline fixture; no real model inference or fallback")
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
            runBlocking {
                if (args[1] == "invalid") {
                    try {
                        runIncidentAgent(runtime, domain, bundle, provider, incidentFixtureModel)
                        error("Invented evidence accepted")
                    } catch (
                        _: IllegalArgumentException,
                    ) {
                        println("INCIDENT_INVALID_REPORT_REFUSED unread document is not evidence")
                    }
                } else {
                    val actual = runIncidentAgent(runtime, domain, bundle, provider, incidentFixtureModel)
                    check(actual == report)
                    println(renderIncidentReport(actual))
                }
            }
            check(provider.requests.get() == 4 && provider.closes.get() == 1)
        }
        worker.join(5000)
        check(!worker.isAlive && !worker.isVirtual)
        println("INCIDENT_RETIRED actual protected platform worker physically ended")
    }
}
