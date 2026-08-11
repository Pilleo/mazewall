package io.mazewall.core

import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A type-safe, validated, and normalized path for use in sandboxing rules.
 *
 * This value class ensures that all paths added to a security policy are
 * absolute and normalized. Note that this class performs purely **syntactic**
 * normalization; it does not resolve symlinks. Physical symlink resolution
 * should be performed prior to creating a [SandboxedPath] (e.g., via
 * `io.mazewall.sbob.PathNormalizer`) if security guarantees against TOCTOU
 * are required.
 *
 * Symlink resolution at the kernel level is intentionally deferred (via
 * Landlock's O_NOFOLLOW) to prevent silent bypasses where a user provides
 * a symlink that resolves to a restricted target.
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
            if (!allowNonExistent && !java.nio.file.Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                throw java.nio.file.NoSuchFileException(p.toString())
            }
            return SandboxedPath(p.toString())
        }

        /**
         * Internal factory for cases where path existence was already verified
         * or for base presets.
         */
        internal fun unsafe(path: String): SandboxedPath = SandboxedPath(path)
    }

    override fun toString(): String = value
}
