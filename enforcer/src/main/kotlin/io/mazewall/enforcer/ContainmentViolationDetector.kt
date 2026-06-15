package io.mazewall.enforcer

import java.io.IOException
import java.nio.file.AccessDeniedException

object ContainmentViolationDetector {
    private val ERRNO_VIOLATION_REGEX = Regex("""\b(error|errno)[=:]\s*(1|13)\b""")

    val DENIED_PHRASES = arrayOf(
        "Operation not permitted",
        "Permission denied",
        "refusé",
        "verweigert",
        "negado",
    )

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
        val msg = t.message ?: return false
        val isViolation = when {
            t is AccessDeniedException -> true
            ERRNO_VIOLATION_REGEX.containsMatchIn(msg) -> true
            t is IOException && (containsDeniedPhrase(msg) || msg.contains("Cannot run")) -> true
            else -> false
        }
        return isViolation
    }

    private fun containsDeniedPhrase(msg: String): Boolean = DENIED_PHRASES.any { msg.contains(it, ignoreCase = true) }
}
