package io.mazewall.profiler.iterative

import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.Uncompiled
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.landlock.Landlock
import java.nio.file.AccessDeniedException

/**
 * Tier A Profiler: Unprivileged "Deny-and-Retry" loop.
 *
 * Intercepts AccessDeniedExceptions from io_uring or standard VFS operations,
 * extracts the failed path, whitelists it, and retries the operation until it succeeds.
 * This provides 100% unprivileged visibility into io_uring ring operations.
 */
object IterativeProfiler {
    public var taskExecutor: IterativeTaskExecutor = RealIterativeTaskExecutor

    private val customPhrases = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val customRegexes = java.util.concurrent.CopyOnWriteArrayList<Regex>()

    fun registerPhrase(phrase: String) {
        customPhrases.add(phrase)
        ContainmentViolationDetector.registerPhrase(phrase)
    }

    fun registerRegex(regex: Regex) {
        customRegexes.add(regex)
        ContainmentViolationDetector.registerRegex(regex)
    }

    fun getRegisteredPhrases(): List<String> = customPhrases.toList()

    fun getRegisteredRegexes(): List<Regex> = customRegexes.toList()

    fun resetToDefaults() {
        customPhrases.clear()
        customRegexes.clear()
        ContainmentViolationDetector.resetToDefaults()
    }

    fun profile(
        basePolicy: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        task: Runnable,
    ): Policy<*, Uncompiled> {
        val maxRetries = 20
        var state: IterativeProfilerState = IterativeProfilerState.Running(basePolicy, 0)

        while (true) {
            state = when (val currentState = state) {
                is IterativeProfilerState.Running -> {
                    if (currentState.iteration >= maxRetries) {
                        IterativeProfilerState.Exceeded(currentState.policy)
                    } else {
                        val error = taskExecutor.executeTask(currentState.policy, task)
                        if (error == null) {
                            IterativeProfilerState.Converged(currentState.policy)
                        } else {
                            IterativeProfilerState.Analyzing(currentState.policy, error, currentState.iteration)
                        }
                    }
                }
                is IterativeProfilerState.Analyzing -> {
                    val path = extractViolationPath(currentState.error, currentState.policy)
                    if (path != null) {
                        IterativeProfilerState.Updating(currentState.policy, path, currentState.iteration)
                    } else {
                        IterativeProfilerState.Failed(currentState.policy, currentState.error)
                    }
                }
                is IterativeProfilerState.Updating -> {
                    val nextPolicy = updatePolicyForViolation(currentState.policy, currentState.path)
                    IterativeProfilerState.Running(nextPolicy, currentState.iteration + 1)
                }
                is IterativeProfilerState.Converged -> {
                    return currentState.policy
                }
                is IterativeProfilerState.Exceeded -> {
                    return currentState.policy
                }
                is IterativeProfilerState.Failed -> {
                    throw currentState.error
                }
            }
        }
    }

    private fun updatePolicyForViolation(
        currentPolicy: Policy<*, Uncompiled>,
        path: String,
    ): Policy<*, Uncompiled> {
        @Suppress("UNCHECKED_CAST")
        val builder = Policy.threadLocalBuilder().base(currentPolicy as Policy<PolicyScope.ThreadLocalOnly, *>)
        val p = java.nio.file.Paths.get(path)
        val isCurrentlyReadAllowed = currentPolicy.allowedFsReadPaths.any {
            val allowedPath = java.nio.file.Paths.get(it.value)
            p.startsWith(allowedPath)
        }

        if (isCurrentlyReadAllowed) {
            // If read is already allowed but we still got denied, it's a write attempt.
            builder.allowFsWrite(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        } else {
            // First attempt: grant read access only.
            // If it was a write attempt, the next run will hit the `isCurrentlyReadAllowed` branch and add write.
            builder.allowFsRead(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        }
        return builder.build()
    }

    private fun getDetectorForPolicy(policy: Policy<*, Uncompiled>): ContainmentViolationDetector {
        val phrases = customPhrases.toList() + policy.customViolationPhrases
        val regexes = customRegexes.toList() + policy.customViolationRegexes
        if (phrases.isEmpty() && regexes.isEmpty()) {
            return ContainmentViolationDetector()
        }
        return ContainmentViolationDetector(
            initialCustomPhrases = phrases,
            initialCustomRegexes = regexes
        )
    }

    private fun extractViolationPath(t: Throwable): String? {
        return extractViolationPath(t, Policy.PURE_COMPUTE_UNSAFE)
    }

    private fun extractViolationPath(t: Throwable, policy: Policy<*, Uncompiled>): String? {
        val detector = getDetectorForPolicy(policy)
        val violation = detector.findViolationCause(t) ?: return null
        val path = when {
            violation is AccessDeniedException -> violation.file
            else -> {
                val msg = violation.message
                if (msg == null) {
                    null
                } else {
                    val phrases = detector.findViolationRanges(msg).toList()
                    val phraseIdx = phrases.firstOrNull()?.first ?: -1
                    val pathEnd = if (phraseIdx != -1) findPathEnd(msg, phraseIdx) else -1
                    if (pathEnd >= 0) resolveAbsolutePath(msg, pathEnd) else null
                }
            }
        }
        return path?.let {
            try {
                java.nio.file.Paths.get(it).toAbsolutePath().normalize().toString()
            } catch (@Suppress("SwallowedException") e: java.nio.file.InvalidPathException) {
                null
            }
        }
    }

    private fun findPathEnd(
        msg: String,
        phraseIdx: Int,
    ): Int {
        var end = phraseIdx - 1
        while (end >= 0 && (msg[end].isWhitespace() || msg[end] == '(' || msg[end] == '\'' || msg[end] == '"')) end--
        return end
    }

    private fun isRestrictedSeparator(c: Char): Boolean {
        return c == ':' || c == '\'' || c == '"' || c == '(' || c == ')' ||
                c == '[' || c == ']' || c == '{' || c == '}' || c == ',' || c == ';'
    }

    private fun resolveAbsolutePath(
        msg: String,
        pathEnd: Int,
    ): String? {
        var start = pathEnd
        while (start > 0) {
            val prevChar = msg[start - 1]
            if (isRestrictedSeparator(prevChar)) {
                break
            }
            if (prevChar.isWhitespace()) {
                val lastSlash = msg.lastIndexOf('/', start - 1)
                if (lastSlash != -1) {
                    var hasSeparator = false
                    for (i in lastSlash until start) {
                        if (isRestrictedSeparator(msg[i])) {
                            hasSeparator = true
                            break
                        }
                    }
                    if (!hasSeparator) {
                        start--
                        continue
                    }
                }
                break
            }
            start--
        }
        return msg.substring(start, pathEnd + 1)
    }
}
