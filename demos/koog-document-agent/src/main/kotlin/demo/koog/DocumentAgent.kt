package demo.koog

import ai.koog.prompt.message.MessagePart
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
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object DocumentAgent {
    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "triage") {
            IncidentApplication.main(args.drop(1).toTypedArray())
            return
        }
        require(args.isEmpty()) { "DocumentAgent uses an acquired synthetic fixture only" }
        check(!Thread.currentThread().isVirtual)
        println("MODEL: scripted (offline); no model account or prompt-injection-resistance claim")
        val root = Files.createTempDirectory("mazewall-document-agent-")
        try {
            val documents = Files.createDirectory(root.resolve("documents"))
            val content = "Project: Launch\nOwner: Ada é🙂"
            Files.writeString(documents.resolve("note.md"), content)
            val privateFile = Files.writeString(root.resolve("private.txt"), "DUMMY_PRIVATE_CANARY")
            val budget = Duration.ofSeconds(10)
            lateinit var worker: Thread
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                val policy = WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowJvmClasspath()
                    .allowFsRead(documents)
                    .block(Syscall.SOCKET)
                    .build()
                val domain = runtime.prepare(policy, DomainLimits(1, 4))
                worker = runtime
                    .submit(domain, Callable { Thread.currentThread() })
                    .result()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                val read = documentReadTool(runtime, domain, documents)
                val allowed = Json.encodeToString(ReadDocumentArguments("note.md"))
                val forbidden = Json.encodeToString(ReadDocumentArguments("../private.txt"))
                runBlocking {
                    val normal = runScriptedAgent(listOf(read), listOf(ScriptStep(MessagePart.Tool.Call("normal", read.name, allowed), content)))
                    check(normal.completions == 1 && normal.failures.isEmpty())
                    println("ALLOWED: approved document read through the real AIAgent and owned tool")
                    val before = read.diagnostics().invocations.admitted
                    try {
                        read.execute(ReadDocumentArguments(""))
                        error("Invalid path admitted")
                    } catch (_: IllegalArgumentException) {
                        check(read.diagnostics().invocations.admitted == before)
                    }
                    val observed = runScriptedAgent(
                        listOf(read),
                        listOf(
                        ScriptStep(MessagePart.Tool.Call("denied", read.name, forbidden), null),
                        ScriptStep(MessagePart.Tool.Call("recovered", read.name, allowed), content),
                        ),
                    )
                    val denied = observed.failures.single()
                    check(denied is ContainmentViolationException && denied.cause is AccessDeniedException)
                    check(observed.completions == 1 && Files.readString(privateFile) == "DUMMY_PRIVATE_CANARY")
                    println("DENIED: actual sibling open refused; evidence=INFERRED_PERMISSION_FAILURE")
                    println("RECOVERED: approved document read succeeded under the same fixed policy")
                }
            }
            worker.join(5000)
            check(!worker.isAlive && !worker.isVirtual)
            check(Files.readString(privateFile) == "DUMMY_PRIVATE_CANARY")
            println("RETIRED: actual owned platform worker stopped; process baseline stays permanent")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
