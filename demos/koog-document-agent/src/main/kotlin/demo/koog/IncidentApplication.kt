package demo.koog

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OllamaLLMProvider
import ai.koog.prompt.llm.OpenAILLMProvider
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal enum class IncidentProvider { OPENAI, OLLAMA }

internal data class IncidentInvocation(
    val bundle: Path,
    val provider: IncidentProvider,
    val model: String,
)

internal fun parseIncidentInvocation(args: Array<String>): IncidentInvocation {
    require(args.size == 3) { "Usage: triage <synthetic-bundle-directory> openai|ollama <prepared-model-id>" }
    val provider = when (args[1]) {
        "openai" -> IncidentProvider.OPENAI
        "ollama" -> IncidentProvider.OLLAMA
        else -> throw IllegalArgumentException("Explicit openai or ollama provider required; no automatic or fixture fallback")
    }
    require(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}").matches(args[2])) { "Bounded model identifier required" }
    return IncidentInvocation(Path.of(args[0]), provider, args[2])
}

internal fun requireIncidentOpenAIKey(key: String?): String {
    require(!key.isNullOrBlank()) { "OPENAI_API_KEY must be configured by the operator; no provider fallback" }
    return key
}

internal fun runIncidentCommand(action: () -> Unit): Int {
    return try {
        action()
        0
    } catch (failure: Exception) {
        // Parser/provider diagnostics can contain untrusted document/model text.
        System.err.println("INCIDENT_FAILED ${toolFailureKind(failure)}; no fallback (diagnostic text withheld)")
        1
    }
}

object IncidentApplication {
    @JvmStatic fun main(args: Array<String>) {
        val status = runIncidentCommand { execute(args) }
        if (status != 0) exitProcess(status)
    }

    private fun execute(args: Array<String>) {
        val invocation = parseIncidentInvocation(args)
        check(!Thread.currentThread().isVirtual)
        val bundle = invocation.bundle.toRealPath()
        require(Files.isDirectory(bundle)) { "Incident bundle directory required" }
        val key = when (invocation.provider) {
            IncidentProvider.OPENAI -> requireIncidentOpenAIKey(System.getenv("OPENAI_API_KEY"))
            IncidentProvider.OLLAMA -> null
        }
        println("MODEL: live ${invocation.provider.name.lowercase()} / ${invocation.model}; operator-selected disclosure of supplied data")
        val budget = Duration.ofSeconds(10)
        lateinit var worker: Thread
        val report = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
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
            val executor = when (invocation.provider) {
                IncidentProvider.OPENAI -> MultiLLMPromptExecutor(OpenAILLMClient(requireNotNull(key)))
                IncidentProvider.OLLAMA -> MultiLLMPromptExecutor(OllamaClient(baseUrl = "http://127.0.0.1:11434"))
            }
            val provider = when (invocation.provider) {
                IncidentProvider.OPENAI -> OpenAILLMProvider
                IncidentProvider.OLLAMA -> OllamaLLMProvider
            }
            val model = LLModel(provider, invocation.model, listOf(LLMCapability.Tools), 16_384, 1024)
            runBlocking { runIncidentAgent(runtime, domain, bundle, executor, model) }
        }
        worker.join(5000)
        check(!worker.isAlive && !worker.isVirtual)
        println(renderIncidentReport(report))
        println("INCIDENT_RETIRED actual protected platform worker physically ended; live model quality is not kernel evidence")
    }
}
