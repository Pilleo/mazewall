package io.mazewall.portal

import io.mazewall.platform.process.JvmLaunchPreparation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/** Closed noncredential startup metadata surface; arbitrary environment names are unsupported. */
public enum class PortalLaunchMarker(
    public val environmentName: String,
) {
    APPROVED("MAZEWALL_APPROVED_MARKER"),
    HOST_ONLY("MAZEWALL_HOST_ONLY_MARKER"),
    DEPLOYMENT_REGION("MAZEWALL_DEPLOYMENT_REGION"),
}

/** Identity measured from an acquired resource, never an FD number or a closeable owner. */
public data class PortalDescriptorIdentity(
    val device: Long,
    val inode: Long,
) {
    public companion object {
        /** The operator keeps this acquired resource alive through startup; this does not acquire it. */
        @JvmStatic
        public fun measure(acquiredResource: Path): PortalDescriptorIdentity {
            val attributes = Files.readAttributes(acquiredResource, "unix:dev,ino")
            return PortalDescriptorIdentity(attributes.getValue("dev") as Long, attributes.getValue("ino") as Long)
        }
    }
}

/**
 * Immutable declared metadata/resource expectations and explicit trusted operator preparation.
 * No preparation preserves inherited deployment behavior; no environment values are copied or
 * selected by the library. Preparation is trusted authority, not an environment-secrecy guarantee.
 */
public class PortalLaunchProfile(
    markers: Map<PortalLaunchMarker, String> = emptyMap(),
    absentMarkers: Set<PortalLaunchMarker> = emptySet(),
    forbiddenDescriptors: Set<PortalDescriptorIdentity> = emptySet(),
    public val version: Int = 2,
    public val preparation: JvmLaunchPreparation? = null,
    jvmArgs: List<String> = emptyList(),
) {
    public val markers: Map<PortalLaunchMarker, String> = java.util.Map.copyOf(markers)
    public val absentMarkers: Set<PortalLaunchMarker> = java.util.Set.copyOf(absentMarkers)
    public val forbiddenDescriptors: Set<PortalDescriptorIdentity> = java.util.Set.copyOf(forbiddenDescriptors)
    public val jvmArgs: List<String> = java.util.List.copyOf(jvmArgs)

    internal fun contract(): PortalLaunchContract {
        require(version == PortalLaunchContract.VERSION) { "unsupported required Portal launch profile version" }
        return PortalLaunchContract(markers, absentMarkers, forbiddenDescriptors, preparation != null)
    }
}

/** Only declared noncredential marker metadata and resource identities are attested. */
internal class PortalLaunchContract(
    private val present: Map<PortalLaunchMarker, String>,
    private val absent: Set<PortalLaunchMarker>,
    private val forbidden: Set<PortalDescriptorIdentity>,
    private val operatorPrepared: Boolean,
) {
    init {
        require(present.keys.intersect(absent).isEmpty()) { "conflicting marker expectations" }
        require(present.values.all { it.length <= MAX_MARKER_LENGTH && it.all { character -> character in ' '..'~' && character != ':' } }) { "invalid noncredential marker metadata" }
        require(forbidden.size <= MAX_RESOURCES) { "too many forbidden descriptor identities" }
    }

    private fun record(): String =
        buildString {
        append("format=$VERSION\npreparation=${if (operatorPrepared) "operator" else "inherited"}\nstdio=jdk-pipes\nfds=no-startup-transfer\n")
        present.entries.sortedBy { it.key.name }.forEach { (marker, value) -> append("e=${marker.name}:$value\n") }
        absent.sortedBy { it.name }.forEach { append("a=${it.name}\n") }
        forbidden.sortedWith(compareBy({ it.device }, { it.inode })).forEach { append("f=${it.device}:${it.inode}\n") }
    }

    val identity: String get() = launchDigest(record())

    fun argument(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(record().toByteArray(Charsets.US_ASCII))

    fun checkMarkers() {
        present.forEach { (marker, expected) -> require(System.getenv(marker.environmentName) == expected) { "declared child marker does not match launch contract" } }
        absent.forEach { require(System.getenv(it.environmentName) == null) { "declared absent child marker is present" } }
    }

    fun checkDescriptors(pid: Long) {
        if (forbidden.isEmpty()) return
        Files.newDirectoryStream(Path.of("/proc/$pid/fd")).use { descriptors ->
            for (path in descriptors) {
                val identity = try {
                    PortalDescriptorIdentity.measure(path)
                } catch (_: java.nio.file.NoSuchFileException) {
                    // A concurrently closed bootstrap descriptor has no live resource to reject.
                    continue
                }
                require(identity !in forbidden) { "forbidden resource is observable in child descriptor table" }
            }
        }
    }

    fun checkStdio(pid: Long) {
        val targets = (0..2).map { Files.readSymbolicLink(Path.of("/proc/$pid/fd/$it")).toString() }
        require(targets.all { it.matches(Regex("pipe:\\[[0-9]+]")) } && targets[1] == targets[2]) { "actual child stdio does not match required merged JDK pipes" }
    }

    companion object {
        const val VERSION = 2
        const val MAX_RESOURCES = 32
        const val MAX_ARGUMENT_BYTES = 16_384
        const val MAX_MARKER_LENGTH = 128
        private const val HEADER_FIELDS = 4
        private const val PREPARATION_FIELD = 1
        private const val STDIO_FIELD = 2
        private const val DESCRIPTORS_FIELD = 3

        fun fromArgument(argument: String): PortalLaunchContract {
            require(argument.length <= MAX_ARGUMENT_BYTES) { "oversized required launch contract" }
            val lines = Base64
                .getUrlDecoder()
                .decode(argument)
                .toString(Charsets.US_ASCII)
                .split('\n')
            require(
                lines.size >= HEADER_FIELDS && lines[0] == "format=$VERSION" && lines[STDIO_FIELD] == "stdio=jdk-pipes" && lines[DESCRIPTORS_FIELD] == "fds=no-startup-transfer" && lines.last() == "",
            ) {
                "unsupported required launch contract"
            }
            val operatorPrepared = when (lines[PREPARATION_FIELD]) {
                "preparation=operator" -> true
                "preparation=inherited" -> false
                else -> throw IllegalArgumentException("unsupported launch preparation")
            }
            val present = mutableMapOf<PortalLaunchMarker, String>()
            val absent = mutableSetOf<PortalLaunchMarker>()
            val forbidden = mutableSetOf<PortalDescriptorIdentity>()
            for (line in lines.drop(HEADER_FIELDS).dropLast(1)) {
                when {
                    line.startsWith("e=") -> {
                        val fields = line.substring(2).split(':')
                        require(fields.size == 2 && present.put(PortalLaunchMarker.valueOf(fields[0]), fields[1]) == null) { "invalid or duplicate marker expectation" }
                    }
                    line.startsWith("a=") -> require(absent.add(PortalLaunchMarker.valueOf(line.substring(2)))) { "duplicate absent marker expectation" }
                    line.startsWith("f=") -> {
                        val fields = line.substring(2).split(':')
                        require(fields.size == 2 && forbidden.add(PortalDescriptorIdentity(fields[0].toLong(), fields[1].toLong()))) { "invalid or duplicate descriptor identity" }
                    }
                    else -> throw IllegalArgumentException("unknown required launch expectation")
                }
            }
            val contract = PortalLaunchContract(present, absent, forbidden, operatorPrepared)
            require(contract.argument() == argument) { "noncanonical launch contract" }
            return contract
        }
    }
}

internal fun launchDigest(value: String): String =
    java.util.HexFormat
    .of()
    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

public class PortalLaunchProfileUnavailableException(
    profile: String,
) : PortalIsolationException("required Portal launch profile is not configured: $profile")
