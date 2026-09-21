package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.internal.attribution.TierELinuxMonotonicClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.LockSupport
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
    const val PROFILE_PROPERTY = "mazewall.tier-e.workload-profile"
    const val RATE_PER_SECOND_PROPERTY = "mazewall.tier-e.sustained-rate-per-second"
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

    /**
     * Deterministic application-shaped schedules around the same exact `openat` oracle.
     *
     * The CPU work deliberately happens after the observed native operation. It changes the
     * application mix without changing the syscall identity, operation manifest, or ordering.
     */
    enum class Profile(
        val wireName: String,
    ) {
        SYSCALL_DENSE("syscall_dense"),
        MIXED_CPU_IO("mixed_cpu_io"),
        BURSTY_IO("bursty_io"),
        CALIBRATION_CPU("calibration_cpu"),
        STACK_DIVERSE("stack_diverse"),
        CLASS_CHURN("class_churn"),
        ;

        internal fun cpuRounds(operationIndex: Int): Int =
            when (this) {
                SYSCALL_DENSE -> 0
                MIXED_CPU_IO -> 256
                BURSTY_IO -> if ((operationIndex + 1) % 32 == 0) 8_192 else 0
                CALIBRATION_CPU -> 1_500_000
                STACK_DIVERSE -> 0
                CLASS_CHURN -> 0
            }

        internal fun callDepth(operationIndex: Int): Int =
            when (this) {
                STACK_DIVERSE -> operationIndex % 16
                else -> 0
            }

        internal fun usesFreshProxy(operationIndex: Int): Boolean = this == CLASS_CHURN && operationIndex % 64 == 0

        companion object {
            fun fromSystemProperty(): Profile =
                entries.singleOrNull { it.wireName == System.getProperty(PROFILE_PROPERTY, SYSCALL_DENSE.wireName) }
                    ?: error("unknown Tier E workload profile: ${System.getProperty(PROFILE_PROPERTY)}")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val benchmark = args.firstOrNull() == "benchmark"
        require(if (benchmark) args.size == 6 else args.size == 3) { "usage: [benchmark <gate:path|0> <platform|virtual> <warmups> <batches> <iterations>]" }
        val gateArgument = if (benchmark) args[1] else args[0]
        val warmups = if (benchmark) args[3].toInt() else 0
        val batches = if (benchmark) args[4].toInt() else 1
        val iterations = if (benchmark) args[5].toInt() else args[2].toInt()
        val profile = Profile.fromSystemProperty()
        val coverage = java.lang.Boolean.getBoolean(TierECoverageOracle.ENABLED_PROPERTY)
        val seed = java.lang.Long.getLong(TierECoverageOracle.SEED_PROPERTY, 1L)
        val paths = if (coverage) List(warmups * iterations) { -1 } + TierECoverageOracle.schedule(batches * iterations, seed) else null
        val gate = gateArgument.takeIf { it.startsWith("gate:") }?.removePrefix("gate:")?.let(Path::of)
        val manifest = System.getProperty(MANIFEST_PROPERTY)?.let(Path::of)
        Prepared((warmups + batches) * iterations, retainRoot = manifest != null).use { prepared ->
            manifest?.let { path -> publishManifest(path, prepared.root, prepared.directoryFds, paths, seed) }
            if (manifest == null && paths != null) {
                System.getProperty("mazewall.tier-e.coverage-manifest-output")?.takeIf { it.isNotBlank() }?.let {
                    publishManifest(Path.of(it), prepared.root, prepared.directoryFds, paths, seed)
                }
            }
            while (gate != null && !Files.exists(gate)) Thread.sleep(1)
            prepared.runBatches(warmups, batches, iterations, profile = profile, coveragePaths = paths).forEach { batch ->
            println(
                "tier-e-shared-openat-oracle-batch: phase=${batch.phase.name.lowercase()} index=${batch.index} operations=${batch.result.completedOperations} checksum=${batch.result.checksum} profile=${profile.wireName} verified=true workloadNs=${batch.result.workloadNanos}",
            )
            }
        }
    }

    fun expectedOperationIds(
        warmups: Int,
        batches: Int,
        iterations: Int,
    ): Set<Int> = (0 until (warmups + batches) * iterations).mapTo(LinkedHashSet()) { FIRST_OPERATION_MODE + it }

    /** Test-only callback target invoked by the native agent while an outer proxy scope is live. */
    @JvmStatic
    fun callbackSuppressionProbe() {
        val fd = UnixDispatcher.openAt(AT_FDCWD, cString("/dev/null"), O_RDONLY, 0)
        check(fd >= 0) { "callback probe failed to open /dev/null" }
        UnixDispatcher.close(fd)
    }

    fun runBatches(
        warmups: Int,
        batches: Int,
        iterations: Int,
        onPrepared: (List<Int>) -> Unit = {},
        onOperationsComplete: () -> Unit = {},
        onOperationStarted: (Int) -> Unit = {},
        onOperationReturned: (Int) -> Unit = {},
        profile: Profile = Profile.fromSystemProperty(),
    ): List<Batch> =
        Prepared((warmups + batches) * iterations).use { prepared ->
            onPrepared(prepared.directoryFds.toList())
            prepared.runBatches(warmups, batches, iterations, onOperationStarted, onOperationReturned, profile).also { onOperationsComplete() }
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
            prepared.runRange(0, iterations, firstOperationOffset, profile = Profile.fromSystemProperty()).also { onOperationsComplete() }
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
            profile: Profile,
            coveragePaths: List<Int>? = null,
        ): List<Batch> {
            // External perf uses CLOCK_MONOTONIC. Keep clocks and output outside runRange,
            // and emit buffered windows only after all timed operations have finished.
            val clock = if (System.getenv("PERF_RECORD") == "true") TierELinuxMonotonicClock() else null
            val windows = if (clock != null) LongArray((warmups + batches) * 2) else null
            val rateController = RateController.fromSystemProperty()
            val result = clock.use {
                buildList(warmups + batches) {
                    repeat(warmups + batches) { ordinal ->
                        val phase = if (ordinal < warmups) BatchPhase.WARMUP else BatchPhase.MEASURED
                        val index = if (ordinal < warmups) ordinal + 1 else ordinal - warmups + 1
                        if (windows != null) windows[ordinal * 2] = requireNotNull(clock).nowNanos()
                        val measured = runRange(
                            ordinal * iterations,
                            iterations,
                            ordinal * iterations,
                            onOperationStarted,
                            onOperationReturned,
                            profile,
                            rateController,
                            coveragePaths,
                        )
                        if (windows != null) windows[ordinal * 2 + 1] = requireNotNull(clock).nowNanos()
                        add(Batch(phase, index, measured))
                    }
                }
            }
            if (windows != null) {
                val pid = ProcessHandle.current().pid()
                result.forEachIndexed { ordinal, batch ->
                    println(
                        "tier-e-perf-window: pid=$pid phase=${batch.phase.name.lowercase()} index=${batch.index} start_ns=${windows[ordinal * 2]} end_ns=${windows[ordinal * 2 + 1]} clock=monotonic",
                    )
                }
            }
            return result
        }

        fun runRange(
            start: Int,
            iterations: Int,
            firstOperationOffset: Int,
            onOperationStarted: (Int) -> Unit = {},
            onOperationReturned: (Int) -> Unit = {},
            profile: Profile,
            rateController: RateController? = null,
            coveragePaths: List<Int>? = null,
        ): Result {
            val started = System.nanoTime()
            var checksum = 0L
            directoryFds.subList(start, start + iterations).forEachIndexed { offset, directoryFd ->
                rateController?.awaitNextOperation()
                val operationId = FIRST_OPERATION_MODE + firstOperationOffset + offset
                onOperationStarted(directoryFd)
                val coveragePath = coveragePaths?.get(start + offset) ?: -1
                val fileFd = if (coveragePath >= 0) {
                    TierECoveragePaths.invoke(coveragePath) {
                        UnixDispatcher.openAt(directoryFd, cString(entryName(start + offset)), O_RDONLY, 0)
                    }
                } else {
                    openAtWithProfile(
                    profile = profile,
                    operationIndex = firstOperationOffset + offset,
                    directoryFd = directoryFd,
                    path = cString(entryName(start + offset)),
                )
                }
                onOperationReturned(directoryFd)
                UnixDispatcher.close(fileFd)
                consumeCpu(profile.cpuRounds(firstOperationOffset + offset), operationId)
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

    internal fun publishManifest(
        path: Path,
        root: Path,
        directoryFds: List<Int>,
        coveragePaths: List<Int>? = null,
        coverageSeed: Long = 1L,
    ) {
        val temporary = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                buildString {
                    append("root=").append(root).append('\n')
                    directoryFds.forEach { append("fd=").append(it).append('\n') }
                    if (coveragePaths != null) {
                        require(coveragePaths.size == directoryFds.size)
                        append("coverage_syscall=openat,257\n")
                        append("coverage_paths=72\n")
                        append("coverage_seed=").append(coverageSeed).append('\n')
                        directoryFds.forEachIndexed { index, fd ->
                            append("coverage=")
                                .append(fd)
                                .append(',')
                                .append(coveragePaths[index])
                                .append(',')
                                .append(index)
                                .append('\n')
                        }
                    }
                },
            )
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun cString(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8) + 0

    private fun entryName(offset: Int): String = "$ENTRY-$offset"

    private fun openAtWithProfile(
        profile: Profile,
        operationIndex: Int,
        directoryFd: Int,
        path: ByteArray,
    ): Int =
        if (profile.usesFreshProxy(operationIndex)) {
            openAtThroughFreshProxy(directoryFd, path)
        } else {
            openAtAtDepth(profile.callDepth(operationIndex), directoryFd, path)
        }

    private fun openAtAtDepth(
        depth: Int,
        directoryFd: Int,
        path: ByteArray,
    ): Int =
        if (depth == 0) {
            UnixDispatcher.openAt(directoryFd, path, O_RDONLY, 0)
        } else {
            openAtAtDepth(depth - 1, directoryFd, path)
        }

    private fun openAtThroughFreshProxy(
        directoryFd: Int,
        path: ByteArray,
    ): Int {
        var fileFd = -1
        URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val operation = Proxy.newProxyInstance(loader, arrayOf(Runnable::class.java)) { _, method, _ ->
                check(method.name == "run") { "unexpected proxy method: ${method.name}" }
                fileFd = UnixDispatcher.openAt(directoryFd, path, O_RDONLY, 0)
                null
            } as Runnable
            operation.run()
        }
        check(fileFd >= 0) { "fresh proxy did not invoke openat" }
        return fileFd
    }

    @Volatile
    private var cpuSink: Long = 0L

    private fun consumeCpu(
        rounds: Int,
        operationId: Int,
    ) {
        var value = cpuSink xor operationId.toLong()
        repeat(rounds) {
            value = value * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        }
        if (rounds > 0) cpuSink = value
    }

    /**
     * Monotonic open-loop pacing keeps a sustained test at its declared offered rate. When the
     * producer falls behind it never catches up in a burst, which keeps saturation observable.
     */
    private class RateController private constructor(
        private val intervalNanos: Long,
        private val startedNanos: Long = System.nanoTime(),
    ) {
        private var nextOperation = 0L

        fun awaitNextOperation() {
            val deadline = startedNanos + nextOperation * intervalNanos
            nextOperation += 1L
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return
                LockSupport.parkNanos(remaining)
            }
        }

        companion object {
            fun fromSystemProperty(): RateController? {
                val rate = System.getProperty(RATE_PER_SECOND_PROPERTY, "0").toLongOrNull()
                    ?: error("$RATE_PER_SECOND_PROPERTY must be a non-negative integer")
                require(rate >= 0L) { "$RATE_PER_SECOND_PROPERTY must be a non-negative integer" }
                return if (rate == 0L) null else RateController(1_000_000_000L / rate.coerceAtMost(1_000_000_000L))
            }
        }
    }

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
