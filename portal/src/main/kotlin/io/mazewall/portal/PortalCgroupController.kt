package io.mazewall.portal

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

/** Local ownership boundary for one worker cgroup. */
internal data class PortalCgroupHandle(
    val directory: Path,
)

/** cgroup v2 filesystem adapter; its effects are confined to the configured delegated root. */
internal interface PortalCgroupController {
    fun createAndAttach(
        root: Path,
        pid: Long,
        profile: PortalWorkerProfile,
    ): PortalCgroupHandle?

    fun release(handle: PortalCgroupHandle)

    fun sample(handle: PortalCgroupHandle): PortalCgroupUsage
}

internal object RealPortalCgroupController : PortalCgroupController {
    private val sequence = AtomicLong()

    override fun createAndAttach(
        root: Path,
        pid: Long,
        profile: PortalWorkerProfile,
    ): PortalCgroupHandle? {
        if (!profile.requiresCgroup) return null
        validateRoot(root, profile)
        val directory = root.resolve("portal-$pid-${sequence.incrementAndGet()}")
        try {
            Files.createDirectory(directory)
            profile.memoryMaxBytes?.let { write(directory.resolve("memory.max"), it.toString()) }
            profile.cpuLimit?.let { write(directory.resolve("cpu.max"), it.cpuMaxValue()) }
            write(directory.resolve("cgroup.procs"), pid.toString())
            return PortalCgroupHandle(directory)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(directory) }
            throw PortalCgroupUnavailableException("could not create or attach worker cgroup: ${failure.message}")
        }
    }

    override fun release(handle: PortalCgroupHandle) {
        try {
            Files.deleteIfExists(handle.directory)
        } catch (failure: Throwable) {
            throw PortalCgroupUnavailableException("could not remove worker cgroup ${handle.directory.fileName}: ${failure.message}")
        }
    }

    override fun sample(handle: PortalCgroupHandle): PortalCgroupUsage {
        fun value(name: String): String? = runCatching { Files.readString(handle.directory.resolve(name), StandardCharsets.UTF_8).trim() }.getOrNull()
        val memoryEvents =
            value("memory.events")
                ?.lineSequence()
                ?.mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).takeIf { it.size == 2 } }
                ?.associate { (key, raw) -> key to raw.toLongOrNull() }
                .orEmpty()
        val cpuUsageMicros =
            value("cpu.stat")
                ?.lineSequence()
                ?.map { it.split(Regex("\\s+"), limit = 2) }
                ?.firstOrNull { it.size == 2 && it[0] == "usage_usec" }
                ?.get(1)
                ?.toLongOrNull()
        return PortalCgroupUsage(
            directory = handle.directory.toString(),
            memoryCurrentBytes = value("memory.current")?.toLongOrNull(),
            memoryEvents = memoryEvents,
            cpuUsageMicros = cpuUsageMicros,
        )
    }

    private fun validateRoot(
        root: Path,
        profile: PortalWorkerProfile,
    ) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw PortalCgroupUnavailableException("delegated root must be a real directory")
        }
        val controllers =
            runCatching { Files.readString(root.resolve("cgroup.controllers"), StandardCharsets.UTF_8).trim().split(Regex("\\s+")) }
                .getOrElse { throw PortalCgroupUnavailableException("delegated root has no readable cgroup.controllers") }
                .toSet()
        if (profile.memoryMaxBytes != null && "memory" !in controllers) {
            throw PortalCgroupUnavailableException("delegated root does not provide the memory controller")
        }
        if (profile.cpuLimit != null && "cpu" !in controllers) {
            throw PortalCgroupUnavailableException("delegated root does not provide the cpu controller")
        }
    }

    private fun write(
        path: Path,
        value: String,
    ) {
        Files.writeString(path, "$value\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}

/** Read-only cgroup v2 observation collected by [PortalRuntime.health]. */
public data class PortalCgroupUsage(
    public val directory: String,
    public val memoryCurrentBytes: Long?,
    public val memoryEvents: Map<String, Long?>,
    public val cpuUsageMicros: Long?,
)
