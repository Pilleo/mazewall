package demo

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

internal fun readWorkspaceFile(
    runtime: ManagedRuntime,
    domain: InProcessDomain,
    path: Path,
): String = OwnedDemoRuntime.await(runtime.submit(domain, Callable { Files.readString(path) }))

/** Run in its own JVM: the explicit process baseline is permanent. */
internal object WorkspaceExample {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Usage: WorkspaceExample (uses private synthetic files only)" }
        DemoFixture.create("workspace").use { fixture ->
            val workspace = Files.createDirectory(fixture.root.resolve("workspace"))
            val allowed = Files.writeString(workspace.resolve("input.txt"), "approved input")
            val sibling = Files.writeString(fixture.root.resolve("sibling.txt"), "private sibling")
            check(Files.readString(sibling) == "private sibling")
            lateinit var worker: Thread
            OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                    .allowJvmClasspath().allowFsRead(workspace).build()
                val domain = runtime.prepare(policy, DomainLimits(1, 1))
                worker = OwnedDemoRuntime.await(runtime.submit(domain, Callable { Thread.currentThread() }))
                check(!worker.isVirtual && worker !== Thread.currentThread())
                check(readWorkspaceFile(runtime, domain, allowed) == "approved input")
                println("RESULT: workspace/input.txt = approved input")
                try {
                    readWorkspaceFile(runtime, domain, sibling)
                    error("A sibling outside the workspace was readable on the worker")
                } catch (denied: ContainmentViolationException) {
                    check(denied.cause is AccessDeniedException) { "Actual denied file open required: $denied" }
                }
                check(Files.readString(sibling) == "private sibling")
                check(readWorkspaceFile(runtime, domain, allowed) == "approved input")
                println("BOUNDARY: worker sibling read denied; host sibling read and subsequent approved read succeed")
                println("EVIDENCE: INFERRED_PERMISSION_FAILURE; shared JVM heap and unrelated executors are not isolated")
            }
            worker.join(5000)
            check(!worker.isAlive && OwnedDemoRuntime.drainRetained().isEmpty())
        }
        println("CLEANUP: actual worker retired and private fixture removed; process restrictions are not uninstalled")
    }
}
