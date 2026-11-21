package io.mazewall.lockdown

import java.util.Locale

/**
 * Zero-code JVM agent entrypoint for attaching process-wide Mazewall lockdown at startup.
 *
 * Example:
 * ```
 * java --enable-native-access=ALL-UNNAMED \
 *   -javaagent:mazewall-lockdown.jar=preset=opensearch,block=memfd_create,unblock=ptrace \
 *   -jar service.jar
 * ```
 */
public object MazewallAgent {
    private val presetLookup: Map<String, LockdownPreset> =
        LockdownPreset.entries.associateBy { normalizeToken(it.name) }

    private val syscallLookup: Map<String, LockdownSyscall> =
        buildMap {
            for (syscall in LockdownSyscall.entries) {
                put(normalizeToken(syscall.name), syscall)
                put(normalizeToken(syscall.syscallName()), syscall)
            }
        }

    /**
     * Installs the process-wide seccomp policy parsed from [agentArgs] before application `main()` executes.
     */
    @JvmStatic
    public fun premain(agentArgs: String?) {
        Mazewall.installOnProcess(parsePolicy(agentArgs))
    }

    /**
     * Parses a `-javaagent` argument string into an immutable [ProcessPolicy].
     */
    @JvmStatic
    public fun parsePolicy(agentArgs: String?): ProcessPolicy {
        val raw = agentArgs?.trim().orEmpty()
        return when {
            raw.isEmpty() -> ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
            else -> parseNonEmptyPolicy(raw, agentArgs.orEmpty())
        }
    }

    private fun parseNonEmptyPolicy(
        trimmed: String,
        original: String,
    ): ProcessPolicy {
        val tokens = trimmed.split(',').map { it.trim() }
        if (tokens.any { it.isEmpty() }) {
            failArg("Empty directive in -javaagent arguments: '$original'")
        }
        return if (tokens.size == 1 && '=' !in tokens.single()) {
            ProcessPolicy.fromPreset(resolvePreset(tokens.single()), RuntimeProfile.HOTSPOT_JIT)
        } else {
            parseDirectivePolicy(tokens)
        }
    }

    private fun parseDirectivePolicy(tokens: List<String>): ProcessPolicy {
        var preset: LockdownPreset? = null
        var runtime: RuntimeProfile? = null
        val mutations = mutableListOf<Pair<Boolean, List<LockdownSyscall>>>()
        for (token in tokens) {
            val eqIndex = token.indexOf('=')
            if (eqIndex <= 0 || eqIndex == token.lastIndex) {
                failArg("Invalid -javaagent directive '$token'; expected key=value")
            }
            val key = normalizeToken(token.substring(0, eqIndex))
            val value = token.substring(eqIndex + 1).trim()
            when (key) {
                "PRESET" -> {
                    if (preset != null) failArg("Duplicate 'preset' directive in -javaagent arguments")
                    preset = resolvePreset(value)
                }
                "RUNTIME", "PROFILE" -> {
                    if (runtime != null) failArg("Duplicate 'runtime' directive in -javaagent arguments")
                    runtime = resolveRuntime(value)
                }
                "BLOCK" -> mutations += true to parseSyscallList(value, "block")
                "UNBLOCK" -> mutations += false to parseSyscallList(value, "unblock")
                else -> failArg("Unknown -javaagent directive '$key'; expected preset, runtime, block, or unblock")
            }
        }
        var policy = ProcessPolicy.fromPreset(preset ?: LockdownPreset.MAZEWALL, runtime ?: RuntimeProfile.HOTSPOT_JIT)
        for ((isBlock, syscalls) in mutations) {
            policy = if (isBlock) policy.block(syscalls) else policy.unblock(syscalls)
        }
        return policy
    }

    private fun resolvePreset(token: String): LockdownPreset =
        presetLookup[normalizeToken(token)]
            ?: failArg("Unknown LockdownPreset '$token'; expected one of ${LockdownPreset.entries.joinToString()}")

    private fun resolveRuntime(token: String): RuntimeProfile =
        when (normalizeToken(token)) {
            "HOTSPOT", "HOTSPOT_JIT" -> RuntimeProfile.HOTSPOT_JIT
            "NATIVE", "NATIVE_IMAGE" -> RuntimeProfile.NATIVE_IMAGE
            else -> failArg("Unknown RuntimeProfile '$token'; expected hotspot_jit or native_image")
        }

    private fun parseSyscallList(
        rawValue: String,
        directive: String,
    ): List<LockdownSyscall> {
        val parts = rawValue.split('+', ':').map { it.trim() }
        if (parts.any { it.isEmpty() }) {
            failArg("Empty syscall entry in '$directive=$rawValue'")
        }
        return parts.map { token ->
            syscallLookup[normalizeToken(token)]
                ?: failArg("Unknown LockdownSyscall '$token' in '$directive'")
        }
    }

    private fun normalizeToken(token: String): String = token.trim().replace('-', '_').uppercase(Locale.ROOT)

    private fun failArg(message: String): Nothing = throw MazewallInstallationException(message)
}
