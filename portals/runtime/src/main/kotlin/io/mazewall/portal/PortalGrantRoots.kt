package io.mazewall.portal

import java.nio.file.Files
import java.nio.file.Path

/** Stable identifier for a broker root authorized during portal startup. */
@JvmInline
public value class PortalGrantRootId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "portal grant root id must not be blank" }
        require(value.none { it.isWhitespace() }) { "portal grant root id must not contain whitespace" }
    }
}

/** One trusted directory from which a broker may create read-only capability grants. */
public data class PortalGrantRoot(
    public val id: PortalGrantRootId,
    public val directory: Path,
)

/** Immutable, validated grant authority supplied once at portal runtime startup. */
public class PortalGrantRoots private constructor(
    private val directories: Map<PortalGrantRootId, Path>,
) {
    internal fun directory(id: PortalGrantRootId): Path = directories[id] ?: throw IllegalArgumentException("unknown portal grant root: ${id.value}")

    public companion object {
        @JvmStatic
        public fun of(vararg roots: PortalGrantRoot): PortalGrantRoots {
            val validated = linkedMapOf<PortalGrantRootId, Path>()
            roots.forEach { root ->
                require(validated.put(root.id, validate(root)) == null) { "duplicate portal grant root: ${root.id.value}" }
            }
            return PortalGrantRoots(validated.toMap())
        }

        @JvmStatic
        public fun empty(): PortalGrantRoots = PortalGrantRoots(emptyMap())

        private fun validate(root: PortalGrantRoot): Path {
            require(Files.isDirectory(root.directory)) { "portal grant root is not a directory: ${root.directory}" }
            return root.directory.toRealPath()
        }
    }
}
