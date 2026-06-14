package io.mazewall.core

import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A type-safe, validated, and canonicalized path for use in sandboxing rules.
 *
 * This value class ensures that all paths added to a security policy have been
 * resolved to their real location on disk without following symlinks (to prevent
 * bypasses).
 */
@JvmInline
public value class SandboxedPath private constructor(public val value: String) {
    public companion object {
        /**
         * Resolves and validates a raw [path] string into a [SandboxedPath].
         *
         * @param allowNonExistent If true, the path is absolute-normalized but existence is not checked.
         *                         Useful for unit tests and base presets.
         * @throws java.io.IOException if the path does not exist and [allowNonExistent] is false.
         */
        @JvmStatic
        public fun of(path: String, allowNonExistent: Boolean = false): SandboxedPath {
            val p = Paths.get(path).toAbsolutePath().normalize()
            val resolved = if (allowNonExistent) {
                p.toString()
            } else {
                p.toRealPath(LinkOption.NOFOLLOW_LINKS).toString()
            }
            return SandboxedPath(resolved)
        }

        /**
         * Internal factory for cases where path existence was already verified
         * or for base presets.
         */
        internal fun unsafe(path: String): SandboxedPath = SandboxedPath(path)
    }

    override fun toString(): String = value
}
