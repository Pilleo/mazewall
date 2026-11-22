package io.mazewall.enforcer.api

/**
 * Marker interfaces for policy scopes.
 *
 * Hierarchy: [ProcessWideSafe] ⊂ [ThreadLocalOnly].
 * Anything safe for the whole process is safe for a single thread, but not vice versa.
 */
public sealed interface PolicyScope {
    /** Enforces thread-local restrictions (e.g. Landlock FS rules). */
    public interface ThreadLocalOnly : PolicyScope

    /** Safe for process-wide or thread-scoped containment. */
    public interface ProcessWideSafe : ThreadLocalOnly
}
