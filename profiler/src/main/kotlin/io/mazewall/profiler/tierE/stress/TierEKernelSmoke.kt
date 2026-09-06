package io.mazewall.profiler.tierE.stress

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.attribution.ExecutionKind
import io.mazewall.profiler.attribution.ResolutionStatus
import io.mazewall.profiler.attribution.TierEEmissionMode
import io.mazewall.profiler.attribution.TierEOptions
import io.mazewall.profiler.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Privileged end-to-end diagnostic used by the Docker kernel gate. */
public object TierEKernelSmoke {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(
            args.size in 4..8,
        ) { "usage: <agent.so> <marker-offset-hex|auto> <workload-classpath> <definitions-file> [virtual|platform] [iterations] [force-cpu] [full-stream|unique-stack-syscall]" }
        val agent = Path.of(args[0]).toAbsolutePath()
        val markerOffset = if (args[1] == "auto") {
            ElfSymbolOffset.resolve(agent, "mazewall_stack_marker")
        } else {
            args[1].removePrefix("0x").toLong(16)
        }
        val definitions = Path.of(args[3]).toAbsolutePath()
        val sessionTag = 0x4d5a
        val emissionMode = when (args.getOrElse(7) { "full-stream" }) {
            "full-stream" -> TierEEmissionMode.FULL_STREAM
            "unique-stack-syscall" -> TierEEmissionMode.UNIQUE_STACK_SYSCALL
            else -> error("unknown Tier E emission mode: ${args[7]}")
        }
        val gate = Path.of("$definitions.gate")
        Files.deleteIfExists(gate)
        Files.deleteIfExists(definitions)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = mutableListOf(
            java,
            "-agentpath:$agent=definitions=$definitions,session=${sessionTag.toString(16)},mode=${args.getOrElse(7) { "full-stream" }}",
            "-cp",
            args[2],
            "AgentWorkload",
            "gate:$gate",
            args.getOrElse(4) { "platform" },
            args.getOrElse(5) { "1" },
        )
        val process = ProcessBuilder(command).inheritIO().start()
        val processEpoch = processEpoch(process.pid())
        val session = TierEProfilerSession(TierEbpfEngine(RealNativeEngine), processEpoch, TierEOptions(emissionMode))
        try {
            session.installAndAttach(process.pid().toInt(), sessionTag, agent, markerOffset)
            args.getOrNull(6)?.toInt()?.let { cpu ->
                val affinity = ProcessBuilder("taskset", "-pc", cpu.toString(), process.pid().toString())
                    .redirectErrorStream(true)
                    .start()
                val output = affinity.inputStream.bufferedReader().readText()
                check(affinity.waitFor() == 0) { "failed to pin target to CPU $cpu: $output" }
            }
            val workloadStarted = System.nanoTime()
            Files.writeString(gate, "go")
            while (process.isAlive) {
                session.poll()
                Thread.sleep(5)
            }
            check(process.waitFor() == 0) { "agent workload failed" }
            val profile = session.finish(definitions)
            val workloadNanos = System.nanoTime() - workloadStarted
            val resolved = profile.syscalls.count { it.resolutionStatus == ResolutionStatus.RESOLVED }
            check(profile.syscalls.all { it.observation.task.tid > 0 }) { "kernel emitted an invalid task identity" }
            val wrong = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.stackDefinition?.frames?.none { frame -> frame.className == "AgentWorkload" } != false
            }
            val wronglyAttributedUnlinks = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.observation.syscallNumber == SYS_UNLINK &&
                    syscall.stackDefinition?.frames?.none { frame ->
                        frame.className == "AgentWorkload" && frame.methodName == "deleteWorkload"
                    } != false
            }
            val unknown = profile.syscalls.count { it.resolutionStatus == ResolutionStatus.NO_ACTIVE_INVOCATION }
            println(
                "tier-e-kernel-smoke: markerTransitions=${session.markerTransitionCount()} " +
                "lastMarkerPidTgid=${session.lastMarkerPidTgid().toULong().toString(16)} " +
                "observations=${profile.syscalls.size} resolved=$resolved unknown=$unknown wrong=$wrong " +
                "lost=${profile.integrity.observedLosses} failures=${profile.integrity.attributionFailures} workloadNs=$workloadNanos",
            )
            check(profile.integrity.observedLosses == 0L) { "Tier E lost observations: ${profile.integrity}" }
            check(resolved > 0) { "no syscall received a logical Java stack" }
            check(wrong == 0) { "$wrong syscalls were attributed outside the controlled workload stack" }
            check(wronglyAttributedUnlinks == 0) {
                "$wronglyAttributedUnlinks unlink syscalls were assigned a non-deletion stack"
            }
            if (args.getOrNull(4) == "virtual") {
                val virtualResolved = profile.syscalls.filter { it.resolutionStatus == ResolutionStatus.RESOLVED }
                check(
                    virtualResolved.all { it.observation.captureFlags and 1 != 0 },
                ) {
                    "virtual-thread observations were not marked as virtual"
                }
                check(virtualResolved.all { it.executionContext?.kind == ExecutionKind.VIRTUAL }) {
                    "virtual-thread observations lost their logical execution identity"
                }
                check(profile.integrity.virtualPinnedScopes > 0) {
                    "virtual-thread proxy intervals did not report their pinning cost"
                }
            }
        } catch (failure: Throwable) {
            process.destroyForcibly()
            failure.printStackTrace()
            exitProcess(1)
        } finally {
            session.close()
            Files.deleteIfExists(gate)
        }
    }

    private fun processEpoch(pid: Long): Long {
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        return stat.substringAfterLast(") ").split(' ')[19].toLong()
    }

    private const val SYS_UNLINK: Int = 87
}
