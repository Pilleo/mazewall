package demo.koog

import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import demo.boundary.BoundaryProbeService
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRemoteCallException
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalStateLostException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Comparator

@Serializable
data class BoundaryArguments(val path: String = "")

/** Test-only native presentation entry; never added to the ordinary application distribution. */
object PortalBoundaryProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        installPortalHostBaseline()
        val root = Files.createTempDirectory("mazewall-outline-boundary-")
        val acquired = mutableSetOf<ProcessHandle>()
        try {
            println("CONTROL: scripted offline model; denied-read/crash service is a separate test fixture, not an outline exploit")
            for (name in listOf("demo.documents.MarkdownOutline", "demo.boundary.ControlledBoundaryProbe")) {
                try { Class.forName(name, false, PortalBoundaryProbe::class.java.classLoader); error("Guest leaked onto host classpath") }
                catch (_: ClassNotFoundException) { /* Generated worker ZIPs are opaque. */ }
            }
            val privateFile = Files.writeString(root.resolve("private.txt"), "DUMMY_PRIVATE_CANARY")
            PortalRuntime.open(outlineConfiguration(root.resolve("worker-cache"))).use { runtime ->
                val outline = outlineTool(runtime)
                val read = PortalToolAdapter(runtime.prepare(BoundaryProbeService::class.java, PortalServiceId("boundary-probe"),
                    PortalOperationCall<BoundaryProbeService, BoundaryArguments, String> { service, arg -> service.read(arg.path) }),
                    typeToken<BoundaryArguments>(), typeToken<String>(), "controlled_read", "Test-only denied read",
                    { require(it.path.length <= 4096 && '\u0000' !in it.path) }, { require(it.length <= 65_536) })
                val crash = PortalToolAdapter(runtime.prepare(BoundaryProbeService::class.java, PortalServiceId("boundary-probe"),
                    PortalOperationCall<BoundaryProbeService, BoundaryArguments, String> { service, _ -> service.crash() }),
                    typeToken<BoundaryArguments>(), typeToken<String>(), "controlled_crash", "Test-only worker loss", {}, {})
                runtime.health().flatMap { it.workerPids }.forEach { acquired.add(ProcessHandle.of(it).orElseThrow()) }
                check(acquired.size == 2 && acquired.all { it.isAlive })
                val normal = Json.encodeToString(OutlineArguments("# Launch\n## Plan"))
                val forbidden = Json.encodeToString(BoundaryArguments(privateFile.toString()))
                runBlocking {
                    check(runScriptedAgent(listOf(outline), listOf(ScriptStep(MessagePart.Tool.Call("normal", outline.name, normal), "Launch\nPlan"))).completions == 1)
                    println("OUTLINE: Launch / Plan (actual generated service)")
                    val recovered = runScriptedAgent(listOf(read, outline), listOf(
                        ScriptStep(MessagePart.Tool.Call("denied", read.name, forbidden), null),
                        ScriptStep(MessagePart.Tool.Call("recover", outline.name, normal), "Launch\nPlan")))
                    val refusal = recovered.failures.single()
                    check(refusal is PortalRemoteCallException && refusal.remoteType == "java.nio.file.AccessDeniedException" && refusal.evidence == PortalInvocationEvidence.COMPLETED)
                    check(Files.readString(privateFile) == "DUMMY_PRIVATE_CANARY")
                    println("DENIED: actual test-worker sibling open refused; original failure retained")
                    println("RECOVERED: legitimate outline still succeeds; policies unchanged")
                    val original = runtime.health().single { it.isolationGroup == "boundary-probe" }.workerPids.single()
                    val lost = runScriptedAgent(listOf(crash, read), listOf(
                        ScriptStep(MessagePart.Tool.Call("crash", crash.name, "{}"), null),
                        ScriptStep(MessagePart.Tool.Call("lost", read.name, forbidden), null)))
                    val unknown = lost.failures.first()
                    check(unknown is PortalCallException && unknown.evidence == PortalInvocationEvidence.UNKNOWN_COMPLETION)
                    check(lost.failures[1] is PortalStateLostException)
                    val health = runtime.health().single { it.isolationGroup == "boundary-probe" }
                    check(health.state == PortalServiceHealthState.STATE_LOST && health.lastWorkerLifecycle.pid == original)
                    check(runScriptedAgent(listOf(outline), listOf(ScriptStep(MessagePart.Tool.Call("survive", outline.name, normal), "Launch\nPlan"))).completions == 1)
                    println("SURVIVED: host and independent outline service usable; lost service not reconstructed or replayed")
                }
            }
            check(acquired.none { it.isAlive })
            println("RETIRED: every captured actual child dead after canonical close")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
