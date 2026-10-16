package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Exact-name ownership for an uninstrumented matched benchmark child. */
public object TierEDirectContainerArm {
    public fun run(
        endpoint: ContainerEndpoint,
        image: String,
        root: Path,
        artifacts: Path,
        uid: Int,
        gid: Int,
        cpu: Int,
        mode: String,
        iterations: Int,
        workloadProfile: String,
        armSeconds: Int,
        execute: (List<String>, Duration, Path?) -> ContainerCommandResult = { command, timeout, output ->
            runContainerCommand(command, timeout, outputFile = output)
        },
    ): Int {
        val name = "tier-e-matched-${UUID.randomUUID()}"

        fun reconcile() {
            val removed = execute(endpoint.command("rm", "-f", name), Duration.ofSeconds(15), null)
            val inspection = execute(endpoint.command("inspect", name), Duration.ofSeconds(5), null)
            check(inspection.exitCode != 0 && inspection.output.contains("No such", ignoreCase = true)) {
                "Tier E direct arm not reconciled: $name (remove: ${removed.output}; inspect: ${inspection.output})"
            }
        }
        val result = try {
            execute(
                endpoint.command(
                    "run",
                    "--rm",
                    "--name",
                    name,
                    "--user",
                    "$uid:$gid",
                    "--cap-drop",
                    "ALL",
                    "--cpuset-cpus",
                    cpu.toString(),
                    "-e",
                    "JAVA_TOOL_OPTIONS=-Dmazewall.tier-e.workload-profile=$workloadProfile",
                    "-v",
                    "$root:/workspace:ro",
                    "-w",
                    "/tmp",
                    image,
                    "java",
                    "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    "/workspace/profiler/build/install/tier-e-daemon/lib/*",
                    "io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget",
                    mode,
                    "3",
                    "10",
                    iterations.toString(),
                ),
                Duration.ofSeconds(armSeconds.toLong()),
                artifacts.resolve("target.log"),
            )
        } catch (failure: Throwable) {
            try {
                reconcile()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        if (result.exitCode != 0) reconcile()
        return if (result.exitCode == -1 && result.output.contains("timed out", ignoreCase = true)) 124 else result.exitCode
    }
}
