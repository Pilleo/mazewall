package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.NativeEngine
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Exact object acquisitions. Read allowances are distinct from explicit read-only recipes. */
internal class PreparedResourceSet private constructor(
    val grants: List<Grant>,
    private val strict: List<PreparedPath>,
    private val digests: Map<PreparedPath, String>,
    private val definition: PolicyDefinition<*>,
    private val native: NativeEngine,
) : AutoCloseable {
    enum class GrantPurpose { Explicit, AutomaticRuntime }

    class Grant(
        val path: PreparedPath,
        val read: Boolean,
        val write: Boolean,
        val execute: Boolean,
        val purpose: GrantPurpose,
    )

    fun requireSource(
        expected: PolicyDefinition<*>,
        engine: NativeEngine,
    ) {
        check(native === engine) { "Prepared helper resources belong to another native owner" }
        require(
            expected.copy(customViolationRegexes = definition.customViolationRegexes) == definition &&
            expected.customViolationRegexes.map { it.pattern to it.options } == definition.customViolationRegexes.map { it.pattern to it.options },
        ) {
            "Prepared helper resources do not match source policy"
        }
    }

    val generation: String = sha256(
        (
            grants.map { "${it.path.identity}\u0000${it.read}\u0000${it.write}\u0000${it.execute}" } +
        strict.map { "strict\u0000${it.identity}\u0000${digests[it]}" }
        ).sorted().joinToString("\n").toByteArray(),
    )

    @Synchronized
    fun validate() {
        grants.forEach { it.path.validate() }
        strict.forEach { path ->
            path.validate()
            if (grants.any { it.write }) requireNoWritableAliases(path)
            digests[path]?.let { check(contentDigest(path) == it) { "Read-only resource content changed: ${path.requested}" } }
        }
    }

    fun allowsRead(path: Path): Boolean = grants.any { it.read && (it.path.resolved == path || it.path.directory && path.startsWith(it.path.resolved)) }

    fun allowsExplicitDirectoryRead(path: Path): Boolean =
        grants.any {
        it.read &&
        it.purpose == GrantPurpose.Explicit &&
        it.path.directory &&
        (it.path.resolved == path || path.startsWith(it.path.resolved))
    }

    @Suppress("TooGenericExceptionCaught") // every acquisition has one close attempt; retain all cleanup failures
    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        (grants.map { it.path } + strict).asReversed().forEach {
            try {
                it.close()
            } catch (problem: Throwable) {
                failure = CleanupFailures.merge(failure, problem)
            }
        }
        failure?.let { throw it }
    }

    internal class ManagedCapture(
        private val runtime: io.mazewall.enforcer.api.execution.ManagedRuntime,
        private val key: Any,
        private val record: Any,
        private val policy: PolicyDefinition<*>,
        private val native: NativeEngine,
        private val afterStore: () -> Unit,
    ) {
        private enum class Phase { NEW, CAPTURING, CLOSING }

        private var slots: List<PreparedPath.ManagedSlot> = emptyList()
        private var phase = Phase.NEW

        fun permitsSlot(
            slot: Any,
            cleanup: Boolean,
        ): Boolean = slots.any { it === slot } && if (cleanup) phase == Phase.CLOSING else phase == Phase.CAPTURING

        private fun requireOwner(cleanup: Boolean) {
            check(
                io.mazewall.enforcer.api.execution
                .matchesManagedPath(runtime, key, record, this, null, native, cleanup),
            ) { "Foreign managed capture" }
        }

        fun capture(): PreparedResourceSet {
            requireOwner(false)
            check(phase == Phase.NEW)
            phase = Phase.CAPTURING
            val frozen = policy.frozenCopy()
            require(frozen.enforceLandlock) { "Prepared resource contracts require filesystem containment" }
            val reads = frozen.allowedFsReadPaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
            val writes = frozen.allowedFsWritePaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
            val explicitPaths = (reads + writes).toList()
            val runtimePaths = linkedSetOf(Path.of(checkNotNull(System.getProperty("java.home"))))
                .apply {
                System
                    .getProperty("java.class.path", "")
                    .split(File.pathSeparator)
                    .filter { it.isNotEmpty() }
                    .forEach { add(Path.of(it)) }
            }.toList()
            slots = (explicitPaths + runtimePaths).map {
                PreparedPath.ManagedSlot(runtime, key, record, this, native, it, afterStore)
            }
            val explicit = explicitPaths.mapIndexed { index, path ->
                Grant(slots[index].open(), path in reads, path in writes, false, GrantPurpose.Explicit)
            }
            val automatic = runtimePaths.mapIndexed { index, _ ->
                Grant(slots[explicitPaths.size + index].open(), true, false, true, GrantPurpose.AutomaticRuntime)
            }
            return PreparedResourceSet(java.util.List.copyOf(explicit + automatic), emptyList(), emptyMap(), frozen, native).also { it.validate() }
        }

        fun closeOwned() {
            requireOwner(true)
            check(phase == Phase.CAPTURING || phase == Phase.CLOSING)
            phase = Phase.CLOSING
            for (index in slots.indices.reversed()) slots[index].closeOwned()
        }
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 256L * 1024 * 1024

        internal fun buildDirect(
            policy: PolicyDefinition<*>,
            native: NativeEngine,
            explicit: List<Grant>,
            strict: List<PreparedPath>,
            automatic: List<Grant>,
        ): PreparedResourceSet {
            validateDirect(policy, explicit, strict)
            val digests = strict.filter { !it.directory }.associateWith { contentDigest(it) }
            return PreparedResourceSet(java.util.List.copyOf(explicit + automatic), strict, digests, policy, native).also { it.validate() }
        }

        internal fun validateDirect(
            policy: PolicyDefinition<*>,
            explicit: List<Grant>,
            strict: List<PreparedPath>,
        ) {
            strict.forEach { path ->
                require(explicit.any { it.read && contains(it.path, path) })
                require(explicit.none { it.write && (contains(it.path, path) || contains(path, it.path)) })
                if (policy.allowedFsWritePaths.isNotEmpty()) requireNoWritableAliases(path)
            }
        }

        @Suppress("TooGenericExceptionCaught") // unwind partial acquisition without erasing original failure
        fun capture(
            policy: PolicyDefinition<*>,
            native: NativeEngine,
            readOnlyPaths: Set<Path> = emptySet(),
        ): PreparedResourceSet {
            val frozen = policy.frozenCopy()
            require(frozen.enforceLandlock) { "Prepared resource contracts require filesystem containment" }
            val owned = mutableListOf<PreparedPath>()
            try {
                val reads = frozen.allowedFsReadPaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
                val writes = frozen.allowedFsWritePaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
                val explicit = (reads + writes).map { path ->
                    val pinned = PreparedPath.open(path, native).also { owned.add(it) }
                    Grant(pinned, path in reads, path in writes, false, GrantPurpose.Explicit)
                }
                val strict = readOnlyPaths.map { path -> PreparedPath.open(path, native).also { owned.add(it) } }
                strict.forEach { path ->
                    require(explicit.any { it.read && contains(it.path, path) }) { "Read-only recipe lacks an explicit read allowance: ${path.requested}" }
                    require(explicit.none { it.write && (contains(it.path, path) || contains(path, it.path)) }) {
                        "Writable resource contradicts read-only recipe: ${path.requested}"
                    }
                    if (writes.isNotEmpty()) requireNoWritableAliases(path)
                }
                val runtime = linkedSetOf(Path.of(checkNotNull(System.getProperty("java.home"))))
                System
                    .getProperty("java.class.path", "")
                    .split(File.pathSeparator)
                    .filter { it.isNotEmpty() }
                    .forEach { runtime.add(Path.of(it)) }
                val automatic = runtime.map { path -> Grant(PreparedPath.open(path, native).also { owned.add(it) }, true, false, true, GrantPurpose.AutomaticRuntime) }
                val digests = strict.filter { !it.directory }.associateWith { contentDigest(it) }
                return PreparedResourceSet(java.util.List.copyOf(explicit + automatic), strict, digests, frozen, native).also { it.validate() }
            } catch (failure: Throwable) {
                var primary = failure
                owned.asReversed().forEach { path ->
                    try {
                    path.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                }
                throw primary
            }
        }

        private fun contains(
            parent: PreparedPath,
            child: PreparedPath,
        ): Boolean =
            parent.identity == child.identity ||
                parent.resolved == child.resolved ||
                java.nio.file.Files
                    .isSameFile(parent.descriptorPath(), child.descriptorPath()) ||
                parent.directory &&
                child.resolved.startsWith(parent.resolved)

        private fun requireNoWritableAliases(path: PreparedPath) {
            require(!path.directory) { "Strict read-only directory with writable resources cannot exclude descendant aliases; select exact read-only files" }
            val links = java.nio.file.Files
                .getAttribute(path.descriptorPath(), "unix:nlink") as Number
            require(links.toLong() == 1L) { "Multiply-linked read-only file may be reachable through writable resources: ${path.requested}" }
        }

        internal fun contentDigest(path: PreparedPath): String =
            java.nio.file.Files.newInputStream(path.descriptorPath()).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                var bytes = 0L
                while (count >= 0) {
                    bytes += count
                    require(bytes <= MAX_RESOURCE_BYTES) { "Read-only resource exceeds bounded identity budget" }
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
                HexFormat.of().formatHex(digest.digest())
            }

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
