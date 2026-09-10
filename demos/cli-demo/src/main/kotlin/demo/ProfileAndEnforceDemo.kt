package demo

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.OpenFlags
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.supervisor.ScopingHandler
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.SupervisorDaemonManager
import io.mazewall.ffi.memory.ConfinedSegment
import io.mazewall.getFdOrThrow
import io.mazewall.profiler.Profiler
import io.mazewall.profiler.ProfilingResult
import java.io.File
import java.lang.foreign.Arena
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The same named, non-inline file operation runs during profiling and enforcement. */
internal class ProfiledFileWorkload(private val configuration: File) {
    fun readConfiguration(): String = configuration.readText()

    companion object {
        fun authorizes(stack: List<StackTraceElement>): Boolean = stack.any {
            it.className == ProfiledFileWorkload::class.java.name && it.methodName == "readConfiguration"
        }
    }
}

/** No incomplete-evidence opt-in is available in this demonstration. */
internal fun compileProfileForDemo(result: ProfilingResult<String>) = result.toPolicy(Policy.PURE_COMPUTE_UNSAFE)

internal fun runProfileAndEnforce(): DemoReport = DemoFixture.create("profile").use { fixture ->
    val configuration = fixture.root.resolve("app_config.json").toFile().apply { writeText("{\"version\":1}") }
    val sensitive = fixture.root.resolve("unprofiled.txt").toFile().apply { writeText("private fixture") }
    val workload = ProfiledFileWorkload(configuration)
    val results = mutableListOf<ScenarioResult>()
    // Start the supervisor before the profiler installs its irreversible USER_NOTIF filter.
    SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()
    val profiled = Profiler.profile { workload.readConfiguration() }
    results += ScenarioResult("profiling workload", ExpectedResult.ALLOWED, ScenarioOutcome.Completed(profiled.value))
    println("Profile diagnostics: ${profiled.behavior.toJson()}")
    println("Coverage complete: ${profiled.coverage.complete}; warnings: ${profiled.coverage.warnings}")

    val compiled = try {
        compileProfileForDemo(profiled)
    } catch (failure: io.mazewall.profiler.IncompleteProfileException) {
        results += ScenarioResult("policy generation", ExpectedResult.ALLOWED, ScenarioOutcome.InfrastructureFailure(failure))
        println("Incomplete evidence is diagnostic-only. Enforcement was not installed.")
        return@use DemoReport(results)
    }
    println(profiled.toDsl())
    results += ScenarioResult("policy generation", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("complete evidence compiled"))

    val scoping = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.OPEN to { _, _, stack -> ProfiledFileWorkload.authorizes(stack) },
            Syscall.OPENAT to { _, _, stack -> ProfiledFileWorkload.authorizes(stack) },
        )
    }
    // A content change proves a new read. The profiled path and operation remain identical.
    configuration.writeText("{\"version\":2}")
    Executors.newSingleThreadExecutor().use { executor ->
        val contained = ContainedExecutors.wrap(executor, compiled, scoping)
        fun scenario(name: String, expected: ExpectedResult, action: () -> String) {
            results += observeScenario(name, expected) { contained.submit(Callable(action)).get() }
        }
        scenario("contained legitimate workload", ExpectedResult.ALLOWED) {
            workload.readConfiguration().also { check(it == "{\"version\":2}") { "positive control did not reread configuration" } }
        }
        scenario("synchronous path attack", ExpectedResult.DENIED) { sensitive.readText() }
        scenario("stack-scoped config attack", ExpectedResult.DENIED) { configuration.readText() }
        scenario("native synchronous open attack", ExpectedResult.DENIED) {
            Arena.ofConfined().use { arena ->
                val descriptor = LinuxNative.fileSystem.open(
                    ConfinedSegment(arena.allocateFrom(sensitive.absolutePath)), OpenFlags(0),
                ).getFdOrThrow("native synchronous open")
                LinuxNative.fileSystem.close(descriptor).getOrThrow("close native probe descriptor")
                "native open succeeded"
            }
        }
    }
    println("Native probe: synchronous open(2). Asynchronous io_uring containment is not demonstrated.")
    println("Thread-local containment does not confine work delegated to pre-existing uncontained threads.")
    DemoReport(results)
}
