package io.mazewall.profiler.internal.hotspot

import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_PROC_STAT_BYTES = 65536
private const val START_TICKS_FIELD = 19
private const val MAX_LAUNCH_PREFIX = 64

internal fun interface HotSpotTargetIdentity {
    fun startTicks(pid: Pid): ULong
}

internal object ProcHotSpotTargetIdentity : HotSpotTargetIdentity {
    override fun startTicks(pid: Pid): ULong {
        val bytes = Files.newInputStream(Path.of("/proc/${pid.value}/stat")).use { it.readNBytes(MAX_PROC_STAT_BYTES + 1) }
        require(bytes.size <= MAX_PROC_STAT_BYTES) { "Target proc stat exceeds limit" }
        val text = bytes.toString(Charsets.UTF_8)
        val end = text.lastIndexOf(')')
        require(end >= 0) { "Invalid target proc stat" }
        return text
            .substring(end + 1)
            .trim()
            .split(Regex("\\s+"))[START_TICKS_FIELD]
            .toULong()
            .also { require(it > 0uL) { "Target generation is missing" } }
    }
}

internal class HotSpotAttachOptions(
    val assetsDirectory: Path,
    launchPrefix: List<String> = emptyList(),
    val timing: HotSpotCollectorTiming = HotSpotCollectorTiming(),
    val retention: HotSpotReplayLimits = HotSpotReplayLimits(),
) {
    val launchPrefix: List<String> = java.util.List.copyOf(launchPrefix)

    init {
        require(launchPrefix.size <= MAX_LAUNCH_PREFIX && launchPrefix.all { it.isNotEmpty() })
    }
}

/** Asset/target admission precedes child creation. Actual runtime and collector authority are checked by the helper. */
internal class HotSpotBackend(
    private val launcher: ProcessLauncher = RealProcessLauncher,
    private val targetIdentity: HotSpotTargetIdentity = ProcHotSpotTargetIdentity,
) {
    fun attach(
        pid: Pid,
        options: HotSpotAttachOptions,
    ): HotSpotProfilingSession {
        require(pid.value > 0)
        val assets = HotSpotAssets.load(options.assetsDirectory)
        val generation = targetIdentity.startTicks(pid)
        val argv = options.launchPrefix +
            listOf(
            assets.collector.toString(),
                "--capture",
                "--protocol-version=2",
                "--control-stdin",
            "--pid",
                pid.value.toString(),
                "--image",
                assets.image,
                "--store",
                assets.store.toString(),
            "--hotspot-identity=safepoint-epoch",
                "--drain-full",
                "--duration",
                "${options.timing.sessionNanos}ns",
        )
        return HotSpotProfilingSession(HotSpotCollectorProcess.attach(argv, pid.value, generation, launcher, options.timing, options.retention))
    }
}

/** Internal ownership session; public result/strategy wiring is reviewed in milestone 5. */
internal class HotSpotProfilingSession(
    private val owner: HotSpotCollectorProcess,
) : AutoCloseable {
    fun snapshot(): HotSpotCollectorSnapshot = owner.snapshot()

    fun finish(): HotSpotCollectorSnapshot = owner.finish()

    override fun close() = owner.close()
}
