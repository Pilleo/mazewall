package io.mazewall.profiler.internal.attribution

/** Stable names for the finite native binding manifest. */
internal enum class TierECaptureBinding(
    val wireName: String,
) {
    UNIX_OPEN0("unix-open0"),
    UNIX_OPENAT0("unix-openat0"),
    UNIX_CLOSE0("unix-close0"),
    UNIX_UNLINK0("unix-unlink0"),
    FILE_READ0("file-read0"),
    FILE_WRITE0("file-write0"),
    FILE_CLOSE_INT_FD("file-close-int-fd"),
    SOCKET_READ0("socket-read0"),
    SOCKET_WRITE0("socket-write0"),
    NET_SOCKET0("net-socket0"),
    NET_CONNECT0("net-connect0"),
    NET_ACCEPT("net-accept"),
    ;

    internal companion object {
        fun fromWireName(value: String): TierECaptureBinding? = entries.singleOrNull { it.wireName == value }
    }
}

/** Immutable capture-domain declaration shared by session configuration and native-agent launch. */
internal sealed class TierECaptureProfile {
    abstract val bindings: List<TierECaptureBinding>
    abstract val wireName: String
    abstract val captureDomain: String

    /** The full, compatibility-preserving default has no native-agent option. */
    abstract val agentOption: String?

    data object Full : TierECaptureProfile() {
        override val bindings: List<TierECaptureBinding> = emptyList()
        override val wireName: String = "full"
        override val captureDomain: String = "all admitted native bindings"
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
