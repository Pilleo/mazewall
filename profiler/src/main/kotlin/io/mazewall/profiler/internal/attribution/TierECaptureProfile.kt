package io.mazewall.profiler.internal.attribution

import java.security.MessageDigest

/** Stable names for the finite native binding manifest. */
internal enum class TierECaptureBinding(
    val wireName: String,
    private val ownerClassName: String,
    private val methodName: String,
    private val descriptor: String,
) {
    UNIX_OPEN0("unix-open0", "sun.nio.fs.UnixNativeDispatcher", "open0", "(JII)I"),
    UNIX_OPENAT0("unix-openat0", "sun.nio.fs.UnixNativeDispatcher", "openat0", "(IJII)I"),
    UNIX_CLOSE0("unix-close0", "sun.nio.fs.UnixNativeDispatcher", "close0", "(I)V"),
    UNIX_UNLINK0("unix-unlink0", "sun.nio.fs.UnixNativeDispatcher", "unlink0", "(J)V"),
    FILE_READ0("file-read0", "sun.nio.ch.UnixFileDispatcherImpl", "read0", "(Ljava/io/FileDescriptor;JI)I"),
    FILE_WRITE0("file-write0", "sun.nio.ch.UnixFileDispatcherImpl", "write0", "(Ljava/io/FileDescriptor;JI)I"),
    FILE_CLOSE_INT_FD("file-close-int-fd", "sun.nio.ch.UnixFileDispatcherImpl", "closeIntFD", "(I)V"),
    SOCKET_READ0("socket-read0", "sun.nio.ch.SocketDispatcher", "read0", "(Ljava/io/FileDescriptor;JI)I"),
    SOCKET_WRITE0("socket-write0", "sun.nio.ch.SocketDispatcher", "write0", "(Ljava/io/FileDescriptor;JI)I"),
    NET_SOCKET0("net-socket0", "sun.nio.ch.Net", "socket0", "(ZZZZ)I"),
    NET_CONNECT0("net-connect0", "sun.nio.ch.Net", "connect0", "(ZLjava/io/FileDescriptor;Ljava/net/InetAddress;I)I"),
    NET_ACCEPT("net-accept", "sun.nio.ch.Net", "accept", "(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;[Ljava/net/InetSocketAddress;)I"),
    ;

    /** Exact JVM-level frame identity for a binding admitted by the native manifest. */
    internal fun matchesFrame(
        className: String,
        methodName: String,
        descriptor: String,
    ): Boolean = this.ownerClassName == className && this.methodName == methodName && this.descriptor == descriptor

    internal companion object {
        fun fromWireName(value: String): TierECaptureBinding? = entries.singleOrNull { it.wireName == value }
    }
}

/** Immutable capture-domain declaration shared by session configuration and native-agent launch. */
internal sealed class TierECaptureProfile {
    abstract val bindings: List<TierECaptureBinding>
    abstract val wireName: String
    abstract val captureDomain: String

    /** Stable evidence label; admission configuration never alters attribution identity. */
    abstract val profileId: String

    /** SHA-256 over the normalized selector manifest for evidence correlation. */
    abstract val selectorDigest: String

    /** The full, compatibility-preserving default has no native-agent option. */
    abstract val agentOption: String?

    data object Full : TierECaptureProfile() {
        override val bindings: List<TierECaptureBinding> = emptyList()
        override val wireName: String = "full"
        override val captureDomain: String = "all admitted native bindings"
        override val profileId: String = "full-boundaries-v1"
        override val selectorDigest: String = digest("full")
        override val agentOption: String? = null
    }

    private data class Selected(
        override val bindings: List<TierECaptureBinding>,
        private val compatibilityAlias: Boolean,
    ) : TierECaptureProfile() {
        override val wireName: String = if (compatibilityAlias) LEGACY_OPENAT0 else bindings.joinToString("+") { it.wireName }
        override val captureDomain: String =
            if (compatibilityAlias) {
                "JDK 25 UnixNativeDispatcher.openat0"
            } else {
                "selected native bindings: ${bindings.joinToString(",") { it.wireName }}"
            }
        override val profileId: String = if (compatibilityAlias) "jdk25-unix-openat0-v1" else "selected-boundaries-v1"
        override val selectorDigest: String = digest(bindings.joinToString("+") { it.wireName })
        override val agentOption: String =
            if (compatibilityAlias) {
                "capture-profile=$LEGACY_OPENAT0"
            } else {
                "capture-bindings=${bindings.joinToString("+") { it.wireName }}"
            }
    }

    internal companion object {
        private const val LEGACY_OPENAT0 = "jdk25-unix-openat0"
        private const val BINDINGS_PREFIX = "capture-bindings="

        private fun digest(normalizedSelectors: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest("tier-e-capture-profile-v1\u0000$normalizedSelectors".encodeToByteArray())
                .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

        fun selected(vararg values: TierECaptureBinding): TierECaptureProfile = selected(values.asList())

        fun selected(values: Iterable<TierECaptureBinding>): TierECaptureProfile {
            val received = values.toList()
            require(received.isNotEmpty()) { "Tier E capture selection must not be empty" }
            require(received.distinct().size == received.size) { "Tier E capture selection contains duplicate bindings" }
            return Selected(received.sortedBy(TierECaptureBinding::ordinal), compatibilityAlias = false)
        }

        /** This is the sole external-string decoder; other callers pass a typed profile. */
        fun fromProperty(value: String): TierECaptureProfile =
            when (value.trim()) {
                "", "full" -> Full
                LEGACY_OPENAT0 -> Selected(listOf(TierECaptureBinding.UNIX_OPENAT0), compatibilityAlias = true)
                else -> {
                    require(value.startsWith(BINDINGS_PREFIX)) { "unknown Tier E capture profile: $value" }
                    val names = value.removePrefix(BINDINGS_PREFIX).split('+')
                    require(names.none(String::isBlank)) { "Tier E capture selection contains an empty binding" }
                    selected(
                        names.map { name ->
                        requireNotNull(TierECaptureBinding.fromWireName(name)) { "unknown Tier E capture binding: $name" }
                    },
                    )
                }
            }
    }
}

/** Named benchmark conveniences; production still receives only the canonical selected bindings. */
internal enum class TierECaptureProfilePreset(
    val profile: TierECaptureProfile,
) {
    FILESYSTEM_METADATA(
        TierECaptureProfile.selected(
            TierECaptureBinding.UNIX_OPEN0,
            TierECaptureBinding.UNIX_OPENAT0,
            TierECaptureBinding.UNIX_CLOSE0,
            TierECaptureBinding.UNIX_UNLINK0,
        ),
    ),
    FILE_IO(
        TierECaptureProfile.selected(
            TierECaptureBinding.FILE_READ0,
            TierECaptureBinding.FILE_WRITE0,
            TierECaptureBinding.FILE_CLOSE_INT_FD,
        ),
    ),
    NETWORK_IO(
        TierECaptureProfile.selected(
            TierECaptureBinding.SOCKET_READ0,
            TierECaptureBinding.SOCKET_WRITE0,
            TierECaptureBinding.NET_SOCKET0,
            TierECaptureBinding.NET_CONNECT0,
            TierECaptureBinding.NET_ACCEPT,
        ),
    ),
}
