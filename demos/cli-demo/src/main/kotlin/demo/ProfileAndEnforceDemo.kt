package demo

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.profiler.api.Profiler
import io.mazewall.profiler.api.ProfilingResult
import java.io.File
import java.util.concurrent.Callable

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
    val profiled = Profiler.profile { workload.readConfiguration() }
    results += ScenarioResult("profiling workload", ExpectedResult.ALLOWED, ScenarioOutcome.Completed(profiled.value))
    println("Profile diagnostics: ${profiled.behavior.toJson()}")
    println("Coverage complete: ${profiled.coverage.complete}; warnings: ${profiled.coverage.warnings}")

    val compiled = try {
        compileProfileForDemo(profiled)
    } catch (failure: io.mazewall.profiler.api.IncompleteProfileException) {
        results += ScenarioResult("policy generation", ExpectedResult.ALLOWED, ScenarioOutcome.InfrastructureFailure(failure))
        println("Incomplete evidence is diagnostic-only. Enforcement was not installed.")
        return@use DemoReport(results)
    }
    println(profiled.toDsl())
    results += ScenarioResult("policy generation", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("complete evidence compiled"))

    // A content change proves a new read. The profiled path and operation remain identical.
    configuration.writeText("{\"version\":2}")
    // Profile daemon startup precedes this explicit, launch-compatible process baseline.
    val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowNonThreadClone().build()
    OwnedDemoRuntime.run(baseline) { runtime ->
        val domain = runtime.prepare(compiled, DomainLimits(1, 1))
        fun scenario(name: String, expected: ExpectedResult, action: () -> String) {
            results += observeScenario(name, expected) { OwnedDemoRuntime.await(runtime.submit(domain, Callable(action))) }
        }
        scenario("contained legitimate workload", ExpectedResult.ALLOWED) {
            workload.readConfiguration().also { check(it == "{\"version\":2}") { "positive control did not reread configuration" } }
        }
        scenario("synchronous path attack", ExpectedResult.DENIED) { sensitive.readText() }
    }
    println("The demo uses application-owned synchronous workers; cancellation is not physical completion.")
    println("Thread-local containment does not confine work delegated to pre-existing uncontained threads.")
    DemoReport(results)
}
