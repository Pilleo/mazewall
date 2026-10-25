package io.mazewall.demo.koog

import ai.koog.agents.core.tools.ToolRegistry
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.Mazewall
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.koog.ThreadToolAdapter
import io.mazewall.platform.syscall.Syscall
import kotlinx.coroutines.runBlocking
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Callable

/** Run separately from Portal: this application's permanent baseline forbids child launch. */
internal object ThreadReadExample {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Usage: ThreadReadExample (uses private synthetic files only)" }
        val root = Files.createTempDirectory("mazewall-koog-thread-")
        try {
            val allowed = Files.createDirectory(root.resolve("allowed"))
            val note = Files.writeString(allowed.resolve("note.txt"), "public é🙂")
            val sibling = Files.writeString(root.resolve("sibling.txt"), "private sibling")
            val budget = Duration.ofSeconds(10)
            lateinit var worker: Thread
            lateinit var tool: ThreadToolAdapter<ThreadReadArguments, String>
            ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowJvmClasspath().allowFsRead(allowed).block(Syscall.SOCKET).build()
                val domain = runtime.prepare(policy, DomainLimits(1, 8))
                worker = Mazewall.callContained(runtime, domain, Callable { Thread.currentThread() })
                check(!worker.isVirtual && worker !== Thread.currentThread())
                val registry = ToolRegistry { tool(threadReadTool(runtime, domain)) }
                tool = registry.getTool<ThreadToolAdapter<ThreadReadArguments, String>>()
                runBlocking {
                    check(tool.execute(ThreadReadArguments(note.toString())) == "public é🙂")
                    println("RESULT: read_note = public é🙂 via the actual upstream Koog registry")
                    check(Files.readString(sibling) == "private sibling")
                    try {
                        tool.execute(ThreadReadArguments(sibling.toString()))
                        error("Worker read the private sibling")
                    } catch (denied: ContainmentViolationException) {
                        check(denied.cause is AccessDeniedException) { "Actual denied file open required: $denied" }
                    }
                    check(tool.execute(ThreadReadArguments(note.toString())) == "public é🙂")
                    println("BOUNDARY: actual sibling open denied; approved read still succeeds; host can read sibling")
                    println("EVIDENCE: INFERRED_PERMISSION_FAILURE; this is a synchronous first-party tool, not arbitrary-code isolation")
                }
            }
            worker.join(5000)
            check(!worker.isAlive && tool.diagnostics().state == ExecutionDiagnosticState.CLOSED && tool.diagnostics().retirementComplete)
            check(Files.readString(sibling) == "private sibling")
            println("CLEANUP: actual restricted platform worker retired; no fallback or replay")
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
