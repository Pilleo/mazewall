package demo.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import demo.documents.DocumentOutlineService
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.platform.syscall.Syscall
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalRuntimeConfig
import io.mazewall.portal.PortalRuntimeLimits
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalWorkerProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Serializable
data class OutlineArguments(
    val markdown: String,
)

internal fun installPortalHostBaseline() {
    check(!Thread.currentThread().isVirtual) { "Trusted process bootstrap requires a platform thread" }
    ContainedExecutors.installOnProcess(
        ProcessPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec()
        .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT)
            .build(),
    )
}

internal fun outlineConfiguration(cache: Path): PortalRuntimeConfig =
    PortalRuntimeConfig.load(
    workerBundleCache = cache,
        workerProfiles = mapOf("static-baseline" to PortalWorkerProfile()),
    limits = PortalRuntimeLimits(
        workers = 1,
        maxAdmissions = 4,
        callTimeoutMs = 10_000,
        startupTimeoutMs = 30_000,
        closeTimeoutMs = 10_000,
        maxRequestBytes = 131_072,
        maxResponseBytes = 131_072,
        maxCapabilities = 0,
    ),
)

internal fun outlineTool(runtime: PortalRuntime): PortalToolAdapter<OutlineArguments, String> =
    PortalToolAdapter(
    runtime.prepare(
        DocumentOutlineService::class.java,
        PortalServiceId("document-outline"),
        PortalOperationCall<DocumentOutlineService, OutlineArguments, String> { service, args -> service.outline(args.markdown) },
    ),
    typeToken<OutlineArguments>(),
        typeToken<String>(),
        "outline_document",
        "Extract up to128 ATX headings without rendering Markdown",
    { require(it.markdown.length <= 65_536 && Charsets.UTF_8.newEncoder().canEncode(it.markdown) && it.markdown.toByteArray(Charsets.UTF_8).size <= 65_536) },
    { require(it.length <= 16_384 && Charsets.UTF_8.newEncoder().canEncode(it) && it.toByteArray(Charsets.UTF_8).size <= 16_384) },
)

object PortalDocumentAgent {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        installPortalHostBaseline()
        val root = Files.createTempDirectory("mazewall-outline-agent-")
        try {
            try {
                Class.forName("demo.documents.MarkdownOutline", false, PortalDocumentAgent::class.java.classLoader)
                error("Worker implementation must not be loadable in the host")
            } catch (_: ClassNotFoundException) {
                // The worker bundle is opaque host data.
            }
            lateinit var worker: ProcessHandle
            lateinit var tool: PortalToolAdapter<OutlineArguments, String>
            PortalRuntime.open(outlineConfiguration(root.resolve("worker-cache"))).use { runtime ->
                tool = outlineTool(runtime)
                val pid = runtime
                    .health()
                    .single { it.isolationGroup == "document-outline" }
                    .workerPids
                    .single()
                check(pid != ProcessHandle.current().pid())
                worker = ProcessHandle.of(pid).orElseThrow()
                check(worker.isAlive)
                val arguments = Json.encodeToString(OutlineArguments("# Launch é🙂\n\n## Plan\n- useful work"))
                val observed = runBlocking { runScriptedAgent(listOf(tool), listOf(ScriptStep(MessagePart.Tool.Call("outline", tool.name, arguments), "Launch é🙂\nPlan"))) }
                check(observed.completions == 1 && observed.failures.isEmpty())
                println("OUTLINE: Launch é🙂 / Plan (actual generated process service, scripted offline model)")
                println("BOUNDARY: host=${ProcessHandle.current().pid()} worker=$pid; implementation absent from host classpath")
            }
            val closed = tool.diagnostics()
            check(!worker.isAlive && closed.state == ExecutionDiagnosticState.CLOSED && closed.retirementComplete && closed.liveWorkers == 0)
            println("RETIRED: actual outline worker stopped after canonical Portal close")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
