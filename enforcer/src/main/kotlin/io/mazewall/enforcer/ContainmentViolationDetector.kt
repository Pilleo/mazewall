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

    private val ERRNO_VIOLATION_REGEX = Regex("""\b(error|errno)[=:]?\s*(1|13|22)\b""", RegexOption.IGNORE_CASE)

    val DENIED_PHRASES = arrayOf(
        "Operation not permitted",
        "Permission denied",
        "refusé",
        "verweigert",
        "negado",
    )

    private val REASON_PHRASES_REGEX: Regex = run {
        val list = mutableListOf<String>()
        DENIED_PHRASES.forEach { list.add(Regex.escape(it)) }

        val strerror1 = io.mazewall.ffi.memory.getSystemStrerror(1)
        val strerror13 = io.mazewall.ffi.memory.getSystemStrerror(13)
        if (strerror1 != null && strerror1.isNotEmpty()) list.add(Regex.escape(strerror1))
        if (strerror13 != null && strerror13.isNotEmpty()) list.add(Regex.escape(strerror13))

        Regex("(?U)\\b(${list.distinct().joinToString("|")})\\b", RegexOption.IGNORE_CASE)
    }

    private val VIOLATION_PHRASES_REGEX: Regex = run {
        // Extract the inner group from REASON_PHRASES_REGEX pattern
        val pattern = REASON_PHRASES_REGEX.pattern.substringAfter('(').substringBeforeLast(')')
        val extended = "$pattern|${Regex.escape("Cannot run")}"
        Regex("(?U)\\b($extended)\\b", RegexOption.IGNORE_CASE)
    }

    init {
        registerDefaultMatchers()
    }

    private fun registerDefaultMatchers() {
        registerMatcher { t -> t is AccessDeniedException }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            ERRNO_VIOLATION_REGEX.containsMatchIn(msg)
        }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            t is IOException && (VIOLATION_PHRASES_REGEX.containsMatchIn(msg) || ERRNO_VIOLATION_REGEX.containsMatchIn(msg))
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
        registerDefaultMatchers()
    }

    /**
     * Finds the ranges of all violation "reason" phrases in the message.
     * This intentionally excludes generic "Cannot run" prefixes to aid in path extraction.
     */
    fun findViolationRanges(msg: String): Sequence<IntRange> {
        return REASON_PHRASES_REGEX.findAll(msg).map { it.range }
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
