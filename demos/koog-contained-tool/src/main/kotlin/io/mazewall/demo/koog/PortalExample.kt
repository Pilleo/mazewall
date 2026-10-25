package io.mazewall.demo.koog

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource
import io.mazewall.enforcer.api.execution.ExecutionGeneration
import io.mazewall.koog.PortalToolAdapter
import io.mazewall.platform.syscall.Syscall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalWorkerLifecycleState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** This fresh JVM uses a launch-compatible baseline; the thread reader's NO_EXEC baseline cannot be reused. */
internal object PortalExample {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.isEmpty()) { "Usage: PortalExample (uses a private worker cache)" }
        val root = Files.createTempDirectory("mazewall-koog-portal-")
        try {
            runPortalExample(root.resolve("worker-cache"))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

internal suspend fun runPortalExample(cache: Path) {
    check(!Thread.currentThread().isVirtual) { "Explicit baseline installation requires a platform bootstrap" }
    try {
        Class.forName("io.mazewall.demo.koog.worker.AsciiTextTransformer")
        error("Guest implementation leaked onto the host classpath")
    } catch (_: ClassNotFoundException) {
        // Only the generated service API belongs on the host classpath.
    }
    val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .allowMmapExec().block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
    ContainedExecutors.installOnProcess(baseline)
    val configuration = TextTools.configuration(cache)
    lateinit var child: ProcessHandle
    lateinit var observed: ExecutionDiagnosticsSource
    PortalRuntime.open(configuration).use { runtime ->
        val tool = TextTools.registry(runtime).getTool<PortalToolAdapter<TextArguments, String>>()
        observed = tool
        val before = tool.diagnostics()
        check(before.state == ExecutionDiagnosticState.READY && before.generation is ExecutionGeneration.PortalInputs)
        check(before.invocations.admitted == 0L)
        check(tool.execute(TextArguments("aBc éß🙂")) == "ABC éß🙂")
        check(tool.diagnostics().invocations.admitted == 1L && before.invocations.admitted == 0L)
        check(!tool.diagnostics().retirementComplete) { "Result delivery is not physical retirement" }
        val group = configuration.manifests.single { it.serviceId.value == "bounded-text" }.isolationGroup
        val health = runtime.health().single { it.isolationGroup == group }
        check(health.state == PortalServiceHealthState.AVAILABLE && health.lastWorkerLifecycle.state == PortalWorkerLifecycleState.STARTED)
        val pid = health.workerPids.single()
        check(pid > 0 && pid != ProcessHandle.current().pid())
        child = ProcessHandle.of(pid).orElseThrow()
        check(child.isAlive)
        for (invalid in listOf("x".repeat(65537), "\uD800")) {
            try {
                TextBounds.requireBounded(invalid)
                error("Text bounds accepted oversized or malformed UTF-8")
            } catch (_: IllegalArgumentException) {
                // Explicit input rejection is required before submitting a call.
            }
        }
        println("RESULT: aBc éß🙂 → ABC éß🙂 via actual Koog registry/generated service")
        println("BOUNDARY: host PID=${ProcessHandle.current().pid()}, distinct ready worker PID=$pid; guest class not loadable on host")
        println("BOUNDS: oversized and malformed UTF8 rejected; tool arguments cannot choose a policy or path")
        println("DIAGNOSTICS: operation=${tool.operationId()} state=${tool.diagnostics().state} identity=${before.generation.kind}; delivery is separate from physical close")
    }
    check(!child.isAlive) { "The acquired protected child must physically retire" }
    val closed = observed.diagnostics()
    check(closed.state == ExecutionDiagnosticState.CLOSED && closed.retirementComplete && closed.liveWorkers == 0)
    println("CLEANUP: acquired Portal worker dead; owner reports complete retirement")
}
