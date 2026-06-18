package io.mazewall.enforcer

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Strategy interface for identifying containment violations from exceptions.
 */
fun interface ViolationMatcher {
    fun matches(t: Throwable): Boolean
}

object ContainmentViolationDetector {
    private val MATCHERS = CopyOnWriteArrayList<ViolationMatcher>()

    private val ERRNO_VIOLATION_REGEX = Regex("""\b(error|errno)[=:]\s*(1|13)\b""")

    val DENIED_PHRASES = arrayOf(
        "Operation not permitted",
        "Permission denied",
        "refusé",
        "verweigert",
        "negado",
    )

    // Using \b word boundaries as per security requirements to avoid false positives on substrings
    private val DENIED_PHRASES_REGEX = Regex(
        """\b(${DENIED_PHRASES.joinToString("|")})\b""",
        RegexOption.IGNORE_CASE
    )

    init {
        // Register default matchers
        registerMatcher { t -> t is AccessDeniedException }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            ERRNO_VIOLATION_REGEX.containsMatchIn(msg)
        }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            t is IOException && (DENIED_PHRASES_REGEX.containsMatchIn(msg) || msg.contains("Cannot run"))
        }
    }

    /**
     * Registers a custom violation matcher.
     */
    fun registerMatcher(matcher: ViolationMatcher) {
        MATCHERS.add(matcher)
    }

    /**
     * Resets matchers to default ones. Useful for testing.
     */
    fun resetToDefaults() {
        MATCHERS.clear()
        // Re-add defaults
        registerMatcher { t -> t is AccessDeniedException }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            ERRNO_VIOLATION_REGEX.containsMatchIn(msg)
        }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            t is IOException && (DENIED_PHRASES_REGEX.containsMatchIn(msg) || msg.contains("Cannot run"))
        }
    }

    fun isContainmentViolation(t: Throwable): Boolean {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return hasViolation(t, visited)
    }

    fun findViolationCause(t: Throwable): Throwable? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return findViolation(t, visited)
    }

    private fun hasViolation(
        t: Throwable?,
        visited: MutableSet<Throwable>,
    ): Boolean {
        if (t == null || !visited.add(t)) return false
        return isDirectContainmentViolation(t) ||
            hasViolation(t.cause, visited) ||
            t.suppressedExceptions.any { hasViolation(it, visited) }
    }

    private fun findViolation(
        t: Throwable?,
        visited: MutableSet<Throwable>,
    ): Throwable? {
        if (t == null || !visited.add(t)) return null
        return if (isDirectContainmentViolation(t)) {
            t
        } else {
            findViolation(t.cause, visited) ?: t.suppressedExceptions.firstNotNullOfOrNull { findViolation(it, visited) }
        }
    }

    private fun isDirectContainmentViolation(t: Throwable): Boolean {
        return MATCHERS.any { it.matches(t) }
    }
}
