package io.mazewall.enforcer

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Strategy interface for identifying containment violations from exceptions.
 */
fun interface ViolationMatcher {
    fun matches(t: Throwable): Boolean
}

class ContainmentViolationDetector @JvmOverloads constructor(
    private val customMatchers: List<ViolationMatcher> = emptyList(),
    private val useDefaults: Boolean = true,
    private val classLoader: ClassLoader? = Thread.currentThread().contextClassLoader ?: ContainmentViolationDetector::class.java.classLoader,
    private val loadServices: Boolean = true,
    private val initialCustomPhrases: List<String> = emptyList(),
    private val initialCustomRegexes: List<Regex> = emptyList()
) {
    private val MATCHERS = CopyOnWriteArrayList<ViolationMatcher>()
    private val customPhrases = CopyOnWriteArrayList<String>()
    private val customRegexes = CopyOnWriteArrayList<Regex>()

    init {
        resetToDefaults()
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

    private fun loadServiceMatchers(classLoader: ClassLoader?) {
        if (!loadServices) return
        try {
            val loader = if (classLoader != null) {
                ServiceLoader.load(ViolationMatcher::class.java, classLoader)
            } else {
                ServiceLoader.load(ViolationMatcher::class.java)
            }
            for (matcher in loader) {
                registerMatcher(matcher)
            }
        } catch (e: Throwable) {
            // Safe fallback, do not crash initialization/loading of detector
        }
    }

    /**
     * Registers a custom violation matcher.
     */
    fun registerMatcher(matcher: ViolationMatcher) {
        MATCHERS.add(matcher)
    }

    /**
     * Registers a custom violation phrase (matched with word boundaries, case-insensitive).
     */
    fun registerPhrase(phrase: String) {
        customPhrases.add(phrase)
    }

    /**
     * Registers a custom violation regex.
     */
    fun registerRegex(regex: Regex) {
        customRegexes.add(regex)
    }

    fun getRegisteredPhrases(): List<String> = customPhrases.toList()

    fun getRegisteredRegexes(): List<Regex> = customRegexes.toList()

    private fun getCustomPhrasesRegex(): Regex? {
        val phrases = customPhrases.toList()
        if (phrases.isEmpty()) return null
        val escaped = phrases.map { Regex.escape(it) }
        return Regex("(?U)\\b(${escaped.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
    }

    /**
     * Resets matchers to default ones. Useful for testing.
     */
    fun resetToDefaults() {
        customPhrases.clear()
        customPhrases.addAll(initialCustomPhrases)
        customRegexes.clear()
        customRegexes.addAll(initialCustomRegexes)

        MATCHERS.clear()
        if (useDefaults) {
            registerDefaultMatchers()
        }
        for (matcher in customMatchers) {
            registerMatcher(matcher)
        }
        if (loadServices) {
            loadServiceMatchers(classLoader)
        }
        registerMatcher { t ->
            val msg = t.message ?: return@registerMatcher false
            val customPhrasesRegex = getCustomPhrasesRegex()
            if (customPhrasesRegex != null && customPhrasesRegex.containsMatchIn(msg)) {
                return@registerMatcher true
            }
            customRegexes.any { it.containsMatchIn(msg) }
        }
    }

    /**
     * Finds the ranges of all violation "reason" phrases in the message.
     * This intentionally excludes generic "Cannot run" prefixes to aid in path extraction.
     */
    fun findViolationRanges(msg: String): Sequence<IntRange> {
        val defaultRanges = REASON_PHRASES_REGEX.findAll(msg).map { it.range }
        val customPhrasesRegex = getCustomPhrasesRegex()
        val customPhrasesRanges = if (customPhrasesRegex != null) {
            customPhrasesRegex.findAll(msg).map { it.range }
        } else {
            emptySequence()
        }
        val customRegexRanges = customRegexes.asSequence().flatMap { regex ->
            regex.findAll(msg).map { it.range }
        }
        return (defaultRanges + customPhrasesRanges + customRegexRanges)
            .sortedWith(compareBy({ it.first }, { it.last }))
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

    companion object {
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

        private val defaultInstance = ContainmentViolationDetector()

        fun isContainmentViolation(t: Throwable): Boolean = defaultInstance.isContainmentViolation(t)

        fun findViolationCause(t: Throwable): Throwable? = defaultInstance.findViolationCause(t)

        fun findViolationRanges(msg: String): Sequence<IntRange> = defaultInstance.findViolationRanges(msg)

        fun registerMatcher(matcher: ViolationMatcher) = defaultInstance.registerMatcher(matcher)

        fun registerPhrase(phrase: String) = defaultInstance.registerPhrase(phrase)

        fun registerRegex(regex: Regex) = defaultInstance.registerRegex(regex)

        fun getRegisteredPhrases(): List<String> = defaultInstance.getRegisteredPhrases()

        fun getRegisteredRegexes(): List<Regex> = defaultInstance.getRegisteredRegexes()

        fun resetToDefaults() = defaultInstance.resetToDefaults()
    }
}
