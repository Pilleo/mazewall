package io.mazewall.profiler.tierE.stress

import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Non-root target entry point shared by split-topology baseline, Tier E, and USER_NOTIF arms. */
internal object TierESplitBenchmarkTarget {
    internal data class Arguments(
        val mode: String,
        val warmups: Int,
        val batches: Int,
        val iterations: Int,
        val gate: Path?,
        val pidFile: Path?,
        val socket: Path?,
        val marker: Path?,
        val markerOffset: String?,
        val session: String?,
        val oracle: Path?,
    ) {
        init {
            require(mode in setOf("baseline", "dictionary-full-stream", "dictionary-unique-edge", "inline-full-stream", "inline-unique-edge", "user-notif-no-stack", "user-notif-stack"))
            require(warmups >= 0 && batches > 0 && iterations > 0)
            if (mode.contains("dictionary") || mode.contains("inline")) {
                require(listOf(gate, pidFile, socket, marker, markerOffset, session, oracle).all { it != null })
            }
        }

        companion object {
            fun parse(args: Array<String>): Arguments {
                require(args.size == 4 || args.size == 11) { "expected mode warmups batches iterations [gate pid-file socket marker offset session oracle]" }
                return Arguments(
                    args[0],
                    args[1].toInt(),
                    args[2].toInt(),
                    args[3].toInt(),
                    args.getOrNull(4)?.let(Path::of),
                    args.getOrNull(5)?.let(Path::of),
                    args.getOrNull(6)?.let(Path::of),
                    args.getOrNull(7)?.let(Path::of),
                    args.getOrNull(8),
                    args.getOrNull(9),
                    args.getOrNull(10)?.let(Path::of),
                )
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val config = Arguments.parse(args)
        config.gate?.let { gate -> while (!Files.exists(gate)) Thread.sleep(1) }
        val cpuBefore = cpuNanos()
        val started = System.nanoTime()
        when (config.mode) {
            "baseline" -> TierESharedOpenatOracleWorkload.main(
                arrayOf("benchmark", "0", "platform", config.warmups.toString(), config.batches.toString(), config.iterations.toString()),
            )
            "user-notif-no-stack", "user-notif-stack" -> UserNotifOracleSmoke.main(
                arrayOf(
                    "benchmark",
                    config.warmups.toString(),
                    config.batches.toString(),
                    config.iterations.toString(),
                    if (config.mode == "user-notif-no-stack") "no-stack" else "stack",
                    "shared-openat-oracle",
                    "480",
                ),
            )
            else -> runTierE(config)
        }
        val elapsed = System.nanoTime() - started
        println("split-benchmark-target: mode=${config.mode} elapsedNs=$elapsed cpuNs=${cpuNanos() - cpuBefore} peakRssKb=${peakRssKb()}")
    }

    private fun runTierE(config: Arguments) {
        val batches = TierESharedOpenatOracleWorkload.runBatches(
            config.warmups,
            config.batches,
            config.iterations,
            onPrepared = { operationFds ->
                publishOracle(requireNotNull(config.oracle), operationFds)
                attach(config)
            },
        )
        batches.forEach { batch ->
            println(
                "tier-e-shared-openat-oracle-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} " +
                    "operations=${batch.result.completedOperations} checksum=${batch.result.checksum} " +
                    "profile=${TierESharedOpenatOracleWorkload.Profile.fromSystemProperty().wireName} " +
                    "verified=true workloadNs=${batch.result.workloadNanos}",
            )
        }
        println("tier-e-workflow: capture complete; terminal control deferred")
    }

    private fun attach(config: Arguments) {
        val pid = Files.readString(requireNotNull(config.pidFile)).trim().toLong()
        val emission = if (config.mode.endsWith("unique-edge")) "unique-stack-syscall" else "full-stream"
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(requireNotNull(config.socket)))
            val output = DataOutputStream(Channels.newOutputStream(channel))
            val input = DataInputStream(Channels.newInputStream(channel))
            output.writeUTF("ATTACH $pid uprobe ${requireNotNull(config.marker)} ${requireNotNull(config.markerOffset)} ${requireNotNull(config.session)} $emission")
            output.flush()
            val reply = input.readUTF()
            check(reply.startsWith("OK")) { "Tier E attach failed: $reply" }
            println("tier-e-workflow: $reply")
            output.writeUTF("STATUS")
            output.flush()
            println("tier-e-workflow: ${input.readUTF()}")
        }
    }

    private fun publishOracle(
        path: Path,
        fds: List<Int>,
    ) {
        val temporary = Files.createTempFile(path.parent, ".tier-e-split-oracle-", ".tmp")
        try {
            Files.writeString(temporary, "{\"schema_version\":1,\"expected_operations\":${fds.size},\"directory_fds\":[${fds.joinToString(",")}]}")
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun cpuNanos(): Long = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime

    private fun peakRssKb(): Long =
        Files
            .readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull() ?: -1L
}
