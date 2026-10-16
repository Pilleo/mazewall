package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.runContainerCommand
import java.security.MessageDigest
import java.time.Duration

/** Kernel-global libc probe lease; failed creation never grants deletion authority. */
public class TierEAllocationProbe(
    public val event: String,
    private val execute: (List<String>) -> ContainerCommandResult = { runContainerCommand(it, Duration.ofSeconds(20)) },
) : AutoCloseable {
    init {
        require(event.matches(Regex("probe_libc:mazewall_malloc_[a-f0-9]{16}"))) { "Invalid owned allocation probe identity" }
    }

    private var created = false
    private var closed = false

    public fun open() {
        check(!created && !closed) { "Allocation probe lease already used" }
        val listed = execute(listOf("perf", "probe", "--list"))
        check(listed.exitCode == 0 && listed.output.length < 4096) { "Allocation probe inventory incomplete" }
        require(listed.output.lineSequence().none { it.trimStart().startsWith("$event ") || it.trim() == event }) {
            "Cannot adopt a preexisting kernel allocation probe"
        }
        val result = execute(
            listOf(
                "perf",
                "probe",
                "-x",
                "/usr/lib/x86_64-linux-gnu/libc.so.6",
            "$event=malloc size=%di:s64",
            ),
        )
        check(result.exitCode == 0) { "Allocation probe creation failed: ${result.output}" }
        created = true
    }

    override fun close() {
        if (!created) return
        val result = execute(listOf("perf", "probe", "--del", event))
        check(result.exitCode == 0) { "Owned allocation probe removal failed: ${result.output}" }
        created = false
        closed = true
    }

    public companion object {
        public fun nameFor(runId: String): String {
            require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid allocation run identity" }
            val prefix = MessageDigest
                .getInstance("SHA-256")
                .digest(runId.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
            return "probe_libc:mazewall_malloc_$prefix"
        }
    }
}
