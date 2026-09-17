package io.mazewall.profiler.tierE.stress

import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

/**
 * Shared operation oracle for Tier E and USER_NOTIF.
 *
 * It exercises the JDK's proxied `UnixNativeDispatcher.openat0`. Before timing, it prepares a
 * directory and keeps one valid, distinct directory FD per operation for the entire child run.
 * The child publishes those FD values before the workload gate opens. When a parent owns the
 * manifest it also owns deletion of the temporary root after capture has stopped, so cleanup
 * cannot reuse a workload directory FD inside the observed process.
 */
internal object TierESharedOpenatOracleWorkload {
    const val FIRST_OPERATION_MODE = 0x100
    private const val AT_FDCWD = -100
    private const val O_RDONLY = 0
    private const val O_DIRECTORY = 0x10000
    private const val ENTRY = "payload"

    data class Result(
        val completedOperations: Int,
        val checksum: Long,
        val workloadNanos: Long,
    )

    enum class BatchPhase { WARMUP, MEASURED }

    data class Batch(
        val phase: BatchPhase,
        val index: Int,
        val result: Result,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val benchmark = args.firstOrNull() == "benchmark"
        require(if (benchmark) args.size == 6 else args.size == 3) { "usage: [benchmark <gate:path|0> <platform|virtual> <warmups> <batches> <iterations>]" }
        val gateArgument = if (benchmark) args[1] else args[0]
        val warmups = if (benchmark) args[3].toInt() else 0
        val batches = if (benchmark) args[4].toInt() else 1
        val iterations = if (benchmark) args[5].toInt() else args[2].toInt()
        val gate = gateArgument.takeIf { it.startsWith("gate:") }?.removePrefix("gate:")?.let(Path::of)
        val manifest = System.getProperty(MANIFEST_PROPERTY)?.let(Path::of)
        Prepared((warmups + batches) * iterations, retainRoot = manifest != null).use { prepared ->
            manifest?.let { path ->
                Files.writeString(
                    path,
                    buildString {
                        append("root=").append(prepared.root).append('\n')
                        prepared.directoryFds.forEach { append("fd=").append(it).append('\n') }
                    },
                )
            }
            while (gate != null && !Files.exists(gate)) Thread.sleep(1)
            prepared.runBatches(warmups, batches, iterations).forEach { batch ->
            println(
                "tier-e-shared-openat-oracle-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} operations=${batch.result.completedOperations} checksum=${batch.result.checksum} verified=true workloadNs=${batch.result.workloadNanos}",
            )
            }
        }
    }

    fun expectedOperationIds(
        warmups: Int,
        batches: Int,
        iterations: Int,
    ): Set<Int> = (0 until (warmups + batches) * iterations).mapTo(LinkedHashSet()) { FIRST_OPERATION_MODE + it }

    fun runBatches(
        warmups: Int,
        batches: Int,
        iterations: Int,
        onPrepared: (List<Int>) -> Unit = {},
        onOperationsComplete: () -> Unit = {},
        onOperationStarted: (Int) -> Unit = {},
        onOperationReturned: (Int) -> Unit = {},
    ): List<Batch> =
        Prepared((warmups + batches) * iterations).use { prepared ->
            onPrepared(prepared.directoryFds.toList())
            prepared.runBatches(warmups, batches, iterations, onOperationStarted, onOperationReturned).also { onOperationsComplete() }
        }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun run(
        iterations: Int,
        firstOperationOffset: Int = 0,
        onPrepared: (List<Int>) -> Unit = {},
        onOperationsComplete: () -> Unit = {},
    ): Result {
        require(iterations > 0) { "iterations must be positive" }
        require(FIRST_OPERATION_MODE + firstOperationOffset + iterations <= 0x1000) { "operation modes exceed the portable permission range" }
        return Prepared(iterations).use { prepared ->
            onPrepared(prepared.directoryFds.toList())
            prepared.runRange(0, iterations, firstOperationOffset).also { onOperationsComplete() }
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private class Prepared(
        private val total: Int,
        private val retainRoot: Boolean = false,
    ) : AutoCloseable {
        val root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "mazewall-tier-e-openat-")
        val relativeRoot = Path
            .of("")
            .toAbsolutePath()
            .normalize()
            .relativize(root.toAbsolutePath().normalize())
            .also { require(!it.isAbsolute) { "oracle root must be relative to the child working directory" } }
        val directoryFds = ArrayList<Int>(total)

        init {
            repeat(total) { offset ->
                val directory = root.resolve("d-$offset")
                Files.createDirectory(directory)
                Files.writeString(directory.resolve(entryName(offset)), "prepared-$offset")
                directoryFds += UnixDispatcher.openAt(AT_FDCWD, cString(relativeRoot.resolve("d-$offset").toString()), O_RDONLY or O_DIRECTORY, 0)
            }
        }

        fun runBatches(
            warmups: Int,
            batches: Int,
            iterations: Int,
            onOperationStarted: (Int) -> Unit = {},
            onOperationReturned: (Int) -> Unit = {},
        ): List<Batch> =
            buildList(warmups + batches) {
            repeat(warmups + batches) { ordinal ->
                val phase = if (ordinal < warmups) BatchPhase.WARMUP else BatchPhase.MEASURED
                val index = if (ordinal < warmups) ordinal + 1 else ordinal - warmups + 1
                add(Batch(phase, index, runRange(ordinal * iterations, iterations, ordinal * iterations, onOperationStarted, onOperationReturned)))
            }
        }

        fun runRange(
            start: Int,
            iterations: Int,
            firstOperationOffset: Int,
            onOperationStarted: (Int) -> Unit = {},
            onOperationReturned: (Int) -> Unit = {},
        ): Result {
            val started = System.nanoTime()
            var checksum = 0L
            directoryFds.subList(start, start + iterations).forEachIndexed { offset, directoryFd ->
                val operationId = FIRST_OPERATION_MODE + firstOperationOffset + offset
                onOperationStarted(directoryFd)
                val fileFd = UnixDispatcher.openAt(directoryFd, cString(entryName(start + offset)), O_RDONLY, 0)
                onOperationReturned(directoryFd)
                UnixDispatcher.close(fileFd)
                checksum = checksum * 31 + operationId
            }
            return Result(iterations, checksum, System.nanoTime() - started)
        }

        override fun close() {
            directoryFds.forEach { fd -> runCatching { UnixDispatcher.close(fd) } }
            if (!retainRoot) root.deleteRecursively()
        }
    }

    const val MANIFEST_PROPERTY = "mazewall.tier-e.shared-openat.manifest"

    private fun cString(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8) + 0

    private fun entryName(offset: Int): String = "$ENTRY-$offset"

    private object UnixDispatcher {
        private val owner = Class.forName("sun.nio.fs.UnixNativeDispatcher")
        private val openAt = owner.getDeclaredMethod("openat", Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val close = owner.getDeclaredMethod("close", Int::class.javaPrimitiveType)

        init {
            check(openAt.trySetAccessible() && close.trySetAccessible()) {
                "shared OPENAT oracle requires --add-opens=java.base/sun.nio.fs=ALL-UNNAMED"
            }
        }

        fun openAt(
            dirFd: Int,
            path: ByteArray,
            flags: Int,
            mode: Int,
        ): Int = invoke(openAt, dirFd, path, flags, mode) as Int

        fun close(fd: Int) {
            invoke(close, fd)
        }

        private fun invoke(
            method: java.lang.reflect.Method,
            vararg arguments: Any?,
        ): Any? =
            try {
                method.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
    }
}
