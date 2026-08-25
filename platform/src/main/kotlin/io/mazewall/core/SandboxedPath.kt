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
        @JvmName("of")
        @JvmStatic
        @JvmOverloads
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

/**
 * Syntactic, component-wise path containment: true when [path] equals [ancestor] or lies beneath it.
 *
 * This is the single canonical containment definition for policy decisions. Both operands must be
 * absolute and normalized (guaranteed for [SandboxedPath] values by [SandboxedPath.of]); a relative
 * or unnormalized operand is never contained.
 */
public fun isUnder(path: java.nio.file.Path, ancestor: java.nio.file.Path): Boolean =
    path.isAbsolute && ancestor.isAbsolute && path.startsWith(ancestor)

/**
 * True when this path equals [ancestor] or lies beneath it (component-wise; see [isUnder]).
 */
public infix fun SandboxedPath.isUnder(ancestor: SandboxedPath): Boolean =
    isUnder(Paths.get(value), Paths.get(ancestor.value))

/**
 * True when every child path lies beneath at least one of [parents].
 * An empty [children] set is trivially covered; no child can be covered by an empty parent set.
 */
public fun Set<SandboxedPath>.coveredBy(parents: Set<SandboxedPath>): Boolean =
    all { child -> parents.any { child isUnder it } }

/** Alias mirroring policy vocabulary: parents cover all children. */
public fun Set<SandboxedPath>.coversAll(children: Set<SandboxedPath>): Boolean =
    children.coveredBy(this)

/**
 * True when this path lies beneath at least one of [ancestors] (see [isUnder]).
 */
public fun SandboxedPath.isUnderAny(ancestors: Set<SandboxedPath>): Boolean =
    ancestors.any { this isUnder it }

/**
 * Best-effort symlink resolution for comparison purposes.
 *
 * Resolves the longest existing ancestor (following symlinks), then re-appends any non-existent
 * tail components syntactically. Falls back to the syntactic value when nothing can be resolved.
 */
public fun SandboxedPath.resolveReal(): SandboxedPath {
    var current = Paths.get(value)
    val unresolvedTail = ArrayDeque<String>()
    while (true) {
        try {
            var resolved = current.toRealPath()
            for (segment in unresolvedTail.asReversed()) {
                resolved = resolved.resolve(segment)
            }
            return SandboxedPath.unsafe(resolved.toString())
        } catch (e: java.io.IOException) {
            val name = current.fileName ?: return this
            unresolvedTail.addLast(name.toString())
            current = current.parent ?: return this
        }
    }
}
