package io.mazewall.profiler.tierE.stress

import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic file workload shared by the no-profiler, Tier E, and USER_NOTIF benchmarks.
 *
 * `completedOperations` is a logical write/read pair count, not a claim about the number of
 * kernel syscalls. The latter varies by JDK and filesystem implementation.
 */
internal object TierECommonFileWorkload {
    const val APPLICATION_KIND_PROPERTY = "mazewall.tier-e.application-workload"

    enum class ApplicationKind(
        val wireName: String,
    ) {
        FILESYSTEM_METADATA("filesystem_metadata"),
        BUFFERED_FILE_IO("buffered_file_io"),
        LOOPBACK_SOCKET("loopback_socket"),
        ;

        companion object {
            fun fromSystemProperty(): ApplicationKind =
                entries.singleOrNull { it.wireName == System.getProperty(APPLICATION_KIND_PROPERTY, BUFFERED_FILE_IO.wireName) }
                    ?: error("unknown Tier E application workload: ${System.getProperty(APPLICATION_KIND_PROPERTY)}")
        }
    }

    enum class BatchPhase { WARMUP, MEASURED }

    data class Result(
        val completedOperations: Int,
        val checksum: Long,
        val workloadNanos: Long,
    )

    data class Batch(
        val phase: BatchPhase,
        val index: Int,
        val result: Result,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val benchmark = args.firstOrNull() == "benchmark"
        require(if (benchmark) args.size == 6 else args.size == 3) {
            "usage: [benchmark <gate:path|0> <platform|virtual> <warmups> <batches> <iterations>]"
        }
        val gateArgument = if (benchmark) args[1] else args[0]
        val warmups = if (benchmark) args[3].toInt() else 0
        val batches = if (benchmark) args[4].toInt() else 1
        val iterations = if (benchmark) args[5].toInt() else args[2].toInt()
        val gate = gateArgument.takeIf { it.startsWith("gate:") }?.removePrefix("gate:")?.let(Path::of)
        while (gate != null && !Files.exists(gate)) Thread.sleep(1)
        val applicationKind = ApplicationKind.fromSystemProperty()
        runBatches(warmups, batches, iterations, applicationKind).forEach { batch ->
            println(
                "tier-e-common-workload-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} " +
                    "operations=${batch.result.completedOperations} checksum=${batch.result.checksum} " +
                    "applicationKind=${applicationKind.wireName} verified=true workloadNs=${batch.result.workloadNanos}",
            )
        }
    }

    fun runBatches(
        warmups: Int,
        batches: Int,
        iterations: Int,
        applicationKind: ApplicationKind = ApplicationKind.fromSystemProperty(),
    ): List<Batch> {
        require(warmups >= 0) { "warmups must not be negative" }
        require(batches > 0) { "batches must be positive" }
        return buildList(warmups + batches) {
            repeat(warmups) { index -> add(Batch(BatchPhase.WARMUP, index + 1, run(iterations, applicationKind))) }
            repeat(batches) { index -> add(Batch(BatchPhase.MEASURED, index + 1, run(iterations, applicationKind))) }
        }
    }

    fun run(
        iterations: Int,
        applicationKind: ApplicationKind = ApplicationKind.fromSystemProperty(),
    ): Result {
        require(iterations > 0) { "iterations must be positive" }
        return when (applicationKind) {
            ApplicationKind.FILESYSTEM_METADATA -> runFilesystemMetadata(iterations)
            ApplicationKind.BUFFERED_FILE_IO -> runBufferedFileIo(iterations)
            ApplicationKind.LOOPBACK_SOCKET -> runLoopbackSocket(iterations)
        }
    }

    private fun runBufferedFileIo(iterations: Int): Result {
        val payloadPrefix = "mazewall-tier-e-common-workload:"
        val started = System.nanoTime()
        var checksum = 0L
        val file = Files.createTempFile("mazewall-tier-e-common", ".txt")
        try {
            repeat(iterations) { index ->
                val payload = "$payloadPrefix$index"
                Files.writeString(file, payload)
                check(Files.readString(file) == payload) { "shared workload readback mismatch at iteration $index" }
                checksum = checksum * 31 + payload.hashCode().toLong()
            }
        } finally {
            Files.deleteIfExists(file)
        }
        return Result(iterations, checksum, System.nanoTime() - started)
    }

    private fun runFilesystemMetadata(iterations: Int): Result {
        val started = System.nanoTime()
        var checksum = 0L
        val root = Files.createTempDirectory("mazewall-tier-e-metadata")
        try {
            repeat(iterations) { index ->
                val entry = root.resolve("entry-$index")
                Files.createFile(entry)
                val attributes = Files.readAttributes(entry, "basic:size,lastModifiedTime")
                check(attributes.containsKey("size")) { "metadata workload did not return file size" }
                checksum = checksum * 31 + entry.fileName.toString().hashCode()
                Files.delete(entry)
            }
        } finally {
            Files.deleteIfExists(root)
        }
        return Result(iterations, checksum, System.nanoTime() - started)
    }

    private fun runLoopbackSocket(iterations: Int): Result {
        val started = System.nanoTime()
        var checksum = 0L
        val terminalFile = Files.createTempFile("mazewall-tier-e-loopback", ".tmp")
        try {
            ServerSocket(0).use { server ->
                repeat(iterations) { index ->
                    val received = AtomicReference<Int>()
                    val accepted = Thread {
                        server.accept().use { peer ->
                            received.set(peer.getInputStream().read())
                            peer.getOutputStream().write(index and 0xff)
                        }
                    }
                    accepted.start()
                    Socket("127.0.0.1", server.localPort).use { client ->
                        client.getOutputStream().write((index xor 0x5a) and 0xff)
                        check(client.getInputStream().read() == (index and 0xff)) { "loopback response mismatch at iteration $index" }
                    }
                    accepted.join()
                    check(received.get() == ((index xor 0x5a) and 0xff)) { "loopback request mismatch at iteration $index" }
                    checksum = checksum * 31 + index
                }
            }
        } finally {
            Files.deleteIfExists(terminalFile)
        }
        return Result(iterations, checksum, System.nanoTime() - started)
    }
}
