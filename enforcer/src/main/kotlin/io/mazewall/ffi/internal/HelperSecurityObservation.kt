package io.mazewall.ffi.internal

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection
import io.mazewall.enforcer.internal.supervisor.ConfinedManifest
import io.mazewall.enforcer.internal.supervisor.ConfinedRejectReason
import io.mazewall.enforcer.internal.supervisor.HelperAncestry
import io.mazewall.enforcer.internal.supervisor.HelperDescriptorIdentity
import io.mazewall.enforcer.internal.supervisor.HelperObjectKind
import io.mazewall.enforcer.internal.supervisor.HelperPurpose
import io.mazewall.enforcer.internal.supervisor.ProcProcessIdentityResolver
import io.mazewall.enforcer.internal.supervisor.ProcessIdentity
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.networking.helperValue
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.getFdOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

internal class HelperSecurityObservation(
    private val namespaceKey: (Path) -> String = { path ->
        val key = requireNotNull(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey())
        require(key.javaClass.name == "sun.nio.fs.UnixFileKey") { "Unsupported namespace identity provider" }
        key.toString()
    },
    private val readBounded: (Path, Int) -> ByteArray = { path, limit -> Files.newInputStream(path).use { it.readNBytes(limit + 1) } },
    private val childrenOf: (Pid) -> List<Pid> = { pid ->
        ProcessHandle.of(pid.value.toLong()).orElseThrow().children().use { children ->
            children.limit(2).map { Pid(Math.toIntExact(it.pid())) }.toList()
        }
    },
) {
    enum class Lineage(
        val wire: String,
    ) {
        Bootstrap("bootstrap-v1"),
        Tier1("tier1-v1"),
    }

    data class Observation(
        val identity: ProcessIdentity,
        val ancestry: HelperAncestry,
        val time: TimeDomainObservation,
    ) {
        fun requireLineage(
            bootstrap: Observation,
            lineage: Lineage,
        ) {
            bootstrap.time.requireCommon(time)
            check(bootstrap.ancestry.canonical.endsWith("lineage=bootstrap-v1\n")) { "Expected captured bootstrap ancestry" }
            val fields = bootstrap.ancestry.canonical
                .trimEnd()
                .lines()
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val expected = when (lineage) {
                Lineage.Bootstrap -> bootstrap.ancestry.canonical
                Lineage.Tier1 -> fields.entries.joinToString("\n", postfix = "\n") { (key, value) ->
                    val next = when (key) {
                        "noNewPrivs" -> "1"
                        "seccomp" -> "2"
                        "seccompFilters" -> Math.addExact(value.toLong(), 1L).toString()
                        "lineage" -> lineage.wire
                        else -> value
                    }
                    "$key=$next"
                }
            }
            check(ancestry.canonical == expected) { "Helper ancestry differs from the known ${lineage.wire} layer" }
        }
    }

    internal data class RetirementView(
        val owner: ProcessIdentity,
        val pidNamespace: String,
        val procKey: String,
        val mountInfo: String,
    )

    internal fun captureRetirementView(owner: Observation): RetirementView {
        val pidNamespace = owner.ancestry.canonical
            .lineSequence()
            .single { it.startsWith("pidNs=") }
            .substringAfter('=')
        val mountInfo = text(Path.of("/proc/self/mountinfo"), MOUNT_LIMIT)
        val proc = mountInfo.lineSequence().single { it.split(' ').getOrNull(4) == "/proc" }
        check(proc.substringAfter(" - ").startsWith("proc ") && !proc.contains("hidepid=")) { "Proc visibility is uncertain" }
        val view = RetirementView(owner.identity, pidNamespace, namespaceKey(Path.of("/proc")), mountInfo)
        requireRetirementView(view)
        return view
    }

    private fun requireRetirementView(view: RetirementView) {
        check(namespaceKey(Path.of("/proc")) == view.procKey) { "Proc view changed" }
        check(text(Path.of("/proc/self/mountinfo"), MOUNT_LIMIT) == view.mountInfo) { "Proc mount view changed" }
        check(namespaceKey(Path.of("/proc/self/ns/pid")) == view.pidNamespace) { "PID namespace changed" }
        val stat = text(Path.of("/proc/self/stat"), STATUS_LIMIT)
        check(parseProcessIdentity(view.owner.pid, stat) == view.owner) { "Proc owner identity changed" }
    }

    internal fun requireRetiredHelper(
        view: RetirementView,
        helper: Observation,
    ) {
        requireRetirementView(view)
        val current = try {
            processIdentity(helper.identity.pid)
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
        requireRetirementView(view)
        check(current != helper.identity) { "Original confined helper has not retired" }
    }

    fun requireSetupOwner(
        manifest: ConfinedManifest,
        bootstrap: Observation,
    ) {
        val lineage = Lineage.entries.single { manifest.ancestry.canonical.endsWith("lineage=${it.wire}\n") }
        val binding = manifest.binding.owner
        val owner = observeAncestry(binding.ownerPid, Tid(binding.ownerPid.value), lineage)
        check(owner.identity.startTimeTicks == binding.ownerStartTicks && owner.ancestry.canonical == manifest.ancestry.canonical) { "Helper manifest owner differs from observed process" }
        owner.requireLineage(bootstrap, lineage)
    }

    fun observeOnlyChild(parent: Observation): Observation {
        try {
            check(processIdentity(parent.identity.pid) == parent.identity) { "Helper parent incarnation changed" }
            val pid = childrenOf(parent.identity.pid).single()
            val child = observeAncestry(pid, Tid(pid.value), Lineage.Bootstrap)
            child.requireLineage(parent, Lineage.Bootstrap)
            check(processIdentity(parent.identity.pid) == parent.identity && childrenOf(parent.identity.pid).single() == pid && processIdentity(pid) == child.identity) {
                "Prestarted helper identity changed during observation"
            }
            return child
        } catch (observationFailure: java.io.IOException) {
            rejectChildObservation(observationFailure)
        } catch (observationFailure: IllegalArgumentException) {
            rejectChildObservation(observationFailure)
        } catch (observationFailure: IllegalStateException) {
            rejectChildObservation(observationFailure)
        } catch (observationFailure: NoSuchElementException) {
            rejectChildObservation(observationFailure)
        } catch (observationFailure: SecurityException) {
            rejectChildObservation(observationFailure)
        }
    }

    private fun rejectChildObservation(failure: Exception): Nothing = throw ConfinedHelperRejection.classify(failure, ConfinedRejectReason.Ancestry)

    fun requirePeer(
        peer: io.mazewall.ffi.networking.SupervisorHelperCredentials.PeerCredentials,
        observed: Observation,
    ) {
        val fields = observed.ancestry.canonical
            .lineSequence()
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        check(peer.pid == observed.identity.pid && peer.uid == fields.getValue("uid").split(',')[1].toLong() && peer.gid == fields.getValue("gid").split(',')[1].toLong()) {
            "Helper peer credentials differ from observed process"
        }
    }

    fun observeAncestry(
        tgid: Pid,
        tid: Tid,
        lineage: Lineage,
    ): Observation {
        require(tgid.value > 0 && tid.value > 0)
        val identity = processIdentity(tgid)
        val task = Path.of("/proc/${tgid.value}/task/${tid.value}")
        val status = text(task.resolve("status"), STATUS_LIMIT)
        require(field(status, "Tgid").toInt() == tgid.value && field(status, "Pid").toInt() == tid.value) { "Helper task identity changed" }
        val time = observeTimeDomain(tgid, tid)
        time.requireCommon(time)
        val namespaces = listOf("pid", "mnt", "user").map { namespace -> namespaceKey(task.resolve("ns/$namespace")).also(::requireNamespaceKey) }
        val uidMap = text(task.resolve("uid_map"), STATUS_LIMIT).also(::validateIdMap)
        val gidMap = text(task.resolve("gid_map"), STATUS_LIMIT).also(::validateIdMap)
        val mounts = text(task.resolve("mountinfo"), MOUNT_LIMIT).also(::validateMounts)
        val root = namespaceKey(task.resolve("root")).also(::requireNamespaceKey)
        val groups = field(status, "Groups").let { if (it.isEmpty()) emptyList() else it.split(WHITESPACE).map(::unsigned) }
        val canonical = buildString {
            append("uid=").append(ids(field(status, "Uid"))).append('\n')
            append("gid=").append(ids(field(status, "Gid"))).append('\n')
            append("groups=").append(groups.distinct().sorted().joinToString(",")).append('\n')
            listOf("Inh", "Prm", "Eff", "Bnd", "Amb").forEach { suffix -> append("cap$suffix=").append(field(status, "Cap$suffix")).append('\n') }
            append("pidNs=${namespaces[0]}\nmountNs=${namespaces[1]}\nuserNs=${namespaces[2]}\n")
            append("timeNs=${time.current}\ntimeChildNs=${time.forChildren}\nmonotonicOffsetSec=0\nmonotonicOffsetNsec=0\n")
            append("uidMap=${sha256(uidMap)}\ngidMap=${sha256(gidMap)}\nrootKey=$root\nmountViewDigest=${sha256(mounts)}\n")
            append("noNewPrivs=${field(status, "NoNewPrivs")}\nseccomp=${field(status, "Seccomp")}\nseccompFilters=${field(status, "Seccomp_filters")}\n")
            append("lineage=${lineage.wire}\n")
        }
        require(processIdentity(tgid) == identity) { "Helper process incarnation changed during observation" }
        return Observation(identity, HelperAncestry.parse(canonical), time)
    }

    fun observeRoot(descriptor: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): HelperDescriptorIdentity {
        check(descriptor.isLiveForIo()) { "Helper root owner retired" }
        val path = Path.of("/proc/self/fd/${descriptor.value}")
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        val key = requireNotNull(attributes.fileKey())
        require(key.javaClass.name == "sun.nio.fs.UnixFileKey") { "Unsupported helper descriptor identity provider" }
        val kind = when {
            attributes.isDirectory -> HelperObjectKind.Directory
            attributes.isRegularFile -> HelperObjectKind.Regular
            else -> throw IllegalArgumentException("Helper root must be a regular file or directory")
        }
        val info = text(Path.of("/proc/self/fdinfo/${descriptor.value}"), STATUS_LIMIT)
        val flags = field(info, "flags").toLong(8)
        require(flags and NativeConstants.O_PATH.toLong() != 0L && flags and NativeConstants.O_CLOEXEC.toLong() != 0L) { "Helper root must be O_PATH and CLOEXEC" }
        val mountId = field(info, "mnt_id").toInt().also { require(it > 0) }
        check(descriptor.isLiveForIo() && Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() == key) { "Helper root identity changed" }
        return HelperDescriptorIdentity(key.toString().also(HelperAncestry::requireObjectKey), mountId, kind)
    }

    fun observeReadable(
        descriptor: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        native: io.mazewall.platform.native.NativeEngine,
    ): String {
        check(descriptor.isLiveForIo()) { "Helper result owner retired" }
        val flags = native.raw.fcntl(descriptor, NativeConstants.F_GETFL, 0).helperValue("helper result F_GETFL")
        val fdFlags = native.raw.fcntl(descriptor, NativeConstants.F_GETFD, 0).helperValue("helper result F_GETFD")
        require(
            flags and ACCESS_MODE_MASK == 0L && flags and NativeConstants.O_PATH.toLong() == 0L && fdFlags and NativeConstants.FD_CLOEXEC.toLong() != 0L,
        ) { "Helper result must be readonly, readable and CLOEXEC" }
        val attributes = Files.readAttributes(Path.of("/proc/self/fd/${descriptor.value}"), BasicFileAttributes::class.java)
        require(attributes.isRegularFile && !attributes.isOther && !attributes.isDirectory && !attributes.isSymbolicLink) { "Helper result must be regular" }
        val key = requireNotNull(attributes.fileKey())
        require(key.javaClass.name == "sun.nio.fs.UnixFileKey") { "Unsupported helper result identity provider" }
        check(descriptor.isLiveForIo())
        return key.toString().also(HelperAncestry::requireObjectKey)
    }

    fun requireContent(
        descriptor: FileDescriptor<*, FdState.Open, FdOwnership.Owned>,
        expected: String,
    ) {
        check(descriptor.isLiveForIo())
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(Path.of("/proc/self/fd/${descriptor.value}")).use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(descriptor.isLiveForIo() && HexFormat.of().formatHex(digest.digest()) == expected) { "Helper runtime support bytes changed" }
    }

    fun requireRuntimeInventory(
        manifest: ConfinedManifest,
        owned: Set<Int>,
        native: NativeEngine,
    ) {
        val runtime = manifest.capabilities
            .filter { it.purpose == HelperPurpose.RuntimeSupport }
            .map { it.identity.key }
            .toSet()
        val descriptors = Files.newDirectoryStream(Path.of("/proc/self/fd")).use { entries ->
            entries
                .asSequence()
                .take(4097)
                .map { it.fileName.toString().toInt() }
                .toList()
                .also { require(it.size <= 4096) { "Helper descriptor inventory exceeds bound" } }
        }
        descriptors.filter { it !in owned }.forEach { fd ->
            val path = Path.of("/proc/self/fd/$fd")
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (absent: java.nio.file.NoSuchFileException) {
                try {
                    Files.readAttributes(path, BasicFileAttributes::class.java)
                    throw IllegalStateException("Helper descriptor reappeared during inventory", absent)
                } catch (_: java.nio.file.NoSuchFileException) {
                    return@forEach
                }
            }
            val flags = field(text(Path.of("/proc/self/fdinfo/$fd"), STATUS_LIMIT), "flags").toLong(8)
            when (fd) {
                0, 1 -> check(Files.isSameFile(path, Path.of("/dev/null")) && flags and ACCESS_MODE_MASK == fd.toLong()) { "Unexpected helper standard stream" }
                STDERR_FD -> check(flags and ACCESS_MODE_MASK == 1L) { "Helper stderr is not write-only" }
                else -> check(attributes.isRegularFile && attributes.fileKey().toString() in runtime && flags and ACCESS_MODE_MASK == 0L || isRuntimeEntropy(path, flags)) {
                    "Unaccounted helper descriptor fd=$fd target=${Files.readSymbolicLink(path)}"
                }
            }
        }
        requireRuntimeMappings(manifest, native)
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).forEach { entry ->
            val attributes = Files.readAttributes(Path.of(entry), BasicFileAttributes::class.java)
            check(attributes.isRegularFile && attributes.fileKey().toString() in runtime) { "Helper classpath is not an exact reviewed file" }
        }
    }

    fun isRuntimeEntropy(
        path: Path,
        flags: Long,
    ): Boolean {
        if (flags and ACCESS_MODE_MASK != 0L || flags and NativeConstants.O_PATH.toLong() != 0L) return false
        val attributes = Files.readAttributes(path, "unix:mode,rdev")
        val mode = (attributes.getValue("mode") as Number).toInt()
        val device = (attributes.getValue("rdev") as Number).toLong()
        // Linux character devices 1:8 and 1:9 are entropy sources, never filesystem grants.
        return mode and FILE_TYPE_MASK == CHARACTER_DEVICE && device in setOf(RANDOM_DEVICE, URANDOM_DEVICE)
    }

    fun requireRuntimeMappings(
        manifest: ConfinedManifest,
        native: NativeEngine,
    ) {
        val runtime = manifest.capabilities
            .filter { it.purpose == HelperPurpose.RuntimeSupport }
            .map { it.identity.key }
            .toSet()
        val maps = text(Path.of("/proc/self/maps"), MOUNT_LIMIT)
        maps.dropLast(1).split('\n').forEach { row ->
            val fields = mappingFields(row)
            val inode = fields[4].toLong().also { require(it >= 0) }
            if (inode != 0L) {
                val path = mappedFilePath(fields)
                val range = fields[0].split('-').map { it.toULong(16) }
                require(range[0] < range[1]) { "Invalid helper mapping range" }
                val mappedPath = "/proc/self/map_files/${range[0].toString(16)}-${range[1].toString(16)}"
                val acquired = NativeArena.ofConfined().use { arena ->
                    native.fileSystem.open(arena.allocateFrom(mappedPath), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC))
                }
                val descriptor = when (acquired) {
                    is LinuxNative.SyscallResult.Success -> acquired.getFdOrThrow("open helper mapped object")
                    is LinuxNative.SyscallResult.Error -> throw ConfinedHelperRejection(
                        if (acquired.errno == 1 || acquired.errno == 13) ConfinedRejectReason.Permission else ConfinedRejectReason.RuntimeClosure,
                        acquired.errno,
                        java.io.IOException("open $mappedPath failed: $acquired"),
                    )
                }
                SupervisorOwnedFd(descriptor, native.fileSystem).use {
                    // Btrfs maps devices identify the superblock; stat identifies the subvolume.
                    val attributes = Files.readAttributes(Path.of("/proc/self/fd/${descriptor.value}"), "unix:mode,ino,fileKey")
                    val key = attributes.getValue("fileKey")
                    check(
                        (attributes.getValue("mode") as Number).toInt() and FILE_TYPE_MASK == REGULAR_FILE &&
                        key.toString() in runtime &&
                        (attributes.getValue("ino") as Number).toLong() == inode &&
                        key == Files.readAttributes(Path.of(path), BasicFileAttributes::class.java).fileKey() &&
                        !(fields[1][1] == 'w' && fields[1][MAP_SHARING_INDEX] == 's'),
                    ) { "Unaccounted helper file mapping: $row" }
                }
            }
        }
    }

    private fun mappingFields(row: String): List<String> {
        val fields = row.split(Regex(" +"), limit = MAP_FIELDS_MAX)
        require(
            fields.size in MAP_FIELDS_MIN..MAP_FIELDS_MAX &&
                Regex("[0-9a-f]+-[0-9a-f]+").matches(fields[0]) &&
                Regex("[r-][w-][x-][ps]").matches(fields[1]) &&
                Regex("[0-9a-f]+:[0-9a-f]+").matches(fields[MAP_DEVICE_INDEX]),
        ) {
            "Malformed helper mapping"
        }
        return fields
    }

    private fun mappedFilePath(fields: List<String>): String {
        val path = fields.getOrNull(5)
        check(path != null && path.startsWith('/') && !path.endsWith(" (deleted)")) { "Unidentified helper file mapping" }
        return path
    }

    private fun processIdentity(pid: Pid): ProcessIdentity {
        // Read outside the legacy resolver so permission/I/O errors retain their exact cause.
        val stat = text(Path.of("/proc/${pid.value}/stat"), STATUS_LIMIT)
        return parseProcessIdentity(pid, stat)
    }

    private fun parseProcessIdentity(
        pid: Pid,
        stat: String,
    ): ProcessIdentity {
        val close = stat.lastIndexOf(')')
        require(close > 0) { "Malformed helper process stat" }
        val fields = stat.substring(close + 1).trim().split(WHITESPACE)
        require(fields.size >= STAT_FIELDS_THROUGH_START && fields[0] in setOf("R", "S", "D", "Z", "T", "t", "X", "x", "K", "W", "P", "I", "N")) {
            "Malformed helper process state"
        }
        require(fields.subList(1, STAT_FIELDS_THROUGH_START).all { it.toLongOrNull() != null }) { "Malformed helper process stat fields" }
        return requireNotNull(ProcProcessIdentityResolver { stat }.resolve(pid)) { "Malformed helper process identity" }
            .also { require(it.startTimeTicks > 0) }
    }

    private fun text(
        path: Path,
        limit: Int,
    ): String {
        val bytes = readBounded(path, limit)
        require(bytes.size in 1..limit) { "Missing or oversized helper observation: $path" }
        return bytes.decodeToString(throwOnInvalidSequence = true).also { require(it.endsWith('\n') && '\u0000' !in it) { "Truncated or malformed helper observation: $path" } }
    }

    data class TimeDomainObservation(
        val current: String,
        val forChildren: String,
        val monotonicOffsetSeconds: Long,
        val monotonicOffsetNanos: Int,
        val rawOffsets: String,
    ) {
        fun requireCommon(actual: TimeDomainObservation) {
            require(current == forChildren && actual.current == actual.forChildren && current == actual.current) {
                "Helper operation/spawning/child time namespace mismatch"
            }
            require(monotonicOffsetSeconds == 0L && monotonicOffsetNanos == 0 && actual.monotonicOffsetSeconds == 0L && actual.monotonicOffsetNanos == 0) {
                "Nonzero monotonic time namespace offset is unsupported"
            }
        }
    }

    fun observeTimeDomain(
        tgid: Pid,
        tid: Tid,
    ): TimeDomainObservation {
        require(tgid.value > 0 && tid.value > 0)
        val task = Path.of("/proc/${tgid.value}/task/${tid.value}/ns")
        val current = namespaceKey(task.resolve("time")).also(::requireNamespaceKey)
        val children = namespaceKey(task.resolve("time_for_children")).also(::requireNamespaceKey)
        val bytes = readBounded(Path.of("/proc/${tid.value}/timens_offsets"), OFFSET_LIMIT)
        require(bytes.size in 1..OFFSET_LIMIT) { "Missing or oversized time namespace offsets" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.endsWith('\n') && '\u0000' !in text) { "Truncated or malformed time namespace offsets" }
        val rows = text.dropLast(1).split('\n')
        require(rows.size == 2) { "Expected exactly monotonic and boottime offsets" }
        val offsets = rows.map { row ->
            val fields = requireNotNull(OFFSET_ROW.matchEntire(row)) { "Malformed time namespace offset row" }.groupValues
            val seconds = fields[2].toLong()
            val nanos = fields[3].toInt()
            require(nanos in 0..999_999_999) { "Invalid time namespace nanoseconds" }
            fields[1] to (seconds to nanos)
        }
        require(offsets.map { it.first }.toSet() == setOf("monotonic", "boottime")) { "Duplicate or unknown time namespace clocks" }
        val monotonic = offsets.single { it.first == "monotonic" }.second
        return TimeDomainObservation(current, children, monotonic.first, monotonic.second, text)
    }

    private companion object {
        const val OFFSET_LIMIT = 256
        const val STAT_FIELDS_THROUGH_START = 20
        const val STATUS_LIMIT = 65536
        const val MOUNT_LIMIT = 4 * 1024 * 1024
        const val ACCESS_MODE_MASK = 3L
        const val STDERR_FD = 2
        const val FILE_TYPE_MASK = 0xf000
        const val CHARACTER_DEVICE = 0x2000
        const val REGULAR_FILE = 0x8000
        const val RANDOM_DEVICE = 0x108L
        const val URANDOM_DEVICE = 0x109L
        const val MAP_FIELDS_MIN = 5
        const val MAP_FIELDS_MAX = 6
        const val MAP_DEVICE_INDEX = 3
        const val MAP_SHARING_INDEX = 3
        const val NAMESPACE_KEY_LIMIT = 512
        const val UINT_MAX = 0xffff_ffffL
        const val UINT_RANGE = 0x1_0000_0000L
        const val ID_COUNT = 4
        const val ID_MAP_FIELDS = 3
        const val MOUNT_OPTION_START = 6
        const val MOUNT_POST_SEPARATOR_FIELDS = 4
        const val MOUNT_POINT_INDEX = 4
        val WHITESPACE = Regex("[ \\t]+")
        val UNSIGNED = Regex("0|[1-9][0-9]*")
        val NAMESPACE_MOUNT_ROOT = Regex("[a-z_]+:\\[[1-9][0-9]*]")
        val OFFSET_ROW = Regex("[ \\t]*(monotonic|boottime)[ \\t]+(-?[0-9]+)[ \\t]+([0-9]+)[ \\t]*")
        val NAMESPACE_KEY = Regex("\\(dev=[0-9a-f]+,ino=[1-9][0-9]*\\)")

        fun requireNamespaceKey(key: String) {
            require(key.length <= NAMESPACE_KEY_LIMIT && NAMESPACE_KEY.matches(key)) { "Unsupported namespace object identity" }
        }

        fun field(
            text: String,
            name: String,
        ): String {
            val rows = text.lineSequence().filter { it.substringBefore(':') == name }.toList()
            require(rows.size == 1) { "Missing or duplicate helper observation field: $name" }
            return rows.single().substringAfter(':').trim()
        }

        fun unsigned(value: String): Long {
            require(UNSIGNED.matches(value)) { "Malformed unsigned helper identity" }
            return value.toLong().also { require(it in 0..UINT_MAX) }
        }

        fun ids(value: String): String = value.split(WHITESPACE).also { require(it.size == ID_COUNT) }.joinToString(",") { unsigned(it).toString() }

        fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

        fun validateIdMap(text: String) {
            text.dropLast(1).split('\n').forEach { row ->
                val values = row.trim().split(WHITESPACE)
                require(values.size == ID_MAP_FIELDS)
                val parsed = values.map(::unsigned)
                require(parsed[2] > 0 && parsed[0] + parsed[2] <= UINT_RANGE && parsed[1] + parsed[2] <= UINT_RANGE)
            }
        }

        fun validateMounts(text: String) {
            val ids = mutableSetOf<Long>()
            text.dropLast(1).split('\n').forEach { row ->
                val fields = row.split(' ')
                val separator = fields.indexOf("-")
                require(separator >= MOUNT_OPTION_START && fields.size == separator + MOUNT_POST_SEPARATOR_FIELDS)
                require(unsigned(fields[0]).let { it > 0 && ids.add(it) } && unsigned(fields[1]) > 0)
                val device = fields[2].split(':')
                require(device.size == 2)
                device.forEach(::unsigned)
                // Bind-mounted namespace roots use nsfs names, not filesystem paths.
                val root = fields[3].startsWith('/') || fields[separator + 1] == "nsfs" && NAMESPACE_MOUNT_ROOT.matches(fields[3])
                require(root && fields[MOUNT_POINT_INDEX].startsWith('/') && fields.none { it.isEmpty() })
            }
        }
    }
}
