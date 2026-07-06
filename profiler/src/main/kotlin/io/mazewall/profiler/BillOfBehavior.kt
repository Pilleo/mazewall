package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.core.Tid
import io.mazewall.PolicyScope
import io.mazewall.Uncompiled
import io.mazewall.BillOfBehaviorDto
import io.mazewall.StackProfileEntryDto
import io.mazewall.StackFrameDto
import io.mazewall.core.Syscall
import io.mazewall.sbob.PathNormalizer
import io.mazewall.profiler.engine.TraceEvent
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

private val jsonSerializer = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * An immutable record of what kernel-level operations were observed during a
 * profiling run. This is the raw output of the [Profiler] — completely decoupled
 * from any [io.mazewall.Policy].
 */
data class BillOfBehavior(
    val opens: Set<String> = emptySet(),
    val fsWritePaths: Set<String> = emptySet(),
    val syscalls: Set<Syscall> = emptySet(),
    val execs: Set<String> = emptySet(),
    /**
     * Keyed by [TraceEvent] identity; multiple events for the same syscall name
     * may produce different stack entries if triggered from different call sites.
     */
    val stackProfile: Map<TraceEvent, List<Array<StackTraceElement>>> = emptyMap(),
) {
    /**
     * Compiles this bill of behavior into a [io.mazewall.Policy] starting from [base].
     * Relative paths in the BoB are resolved against [baseCwd].
     */
    fun toPolicy(
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        @Suppress("UNCHECKED_CAST")
        val builder = Policy.threadLocalBuilder().base(base as Policy<PolicyScope.ThreadLocalOnly, *>)
        if (base.defaultAction == io.mazewall.core.SeccompAction.ACT_ALLOW) {
            val toUnblock = syscalls.filter { !base.isSyscallAllowed(it) }
            builder.unblock(*toUnblock.toTypedArray())
        } else {
            builder.allow(*syscalls.toTypedArray())
        }
        val pOpens = PathNormalizer.normalizeAndPrune(opens, baseCwd)
        val pWrites = PathNormalizer.normalizeAndPrune(fsWritePaths, baseCwd)
        for (path in pOpens) builder.allowFsRead(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        for (path in pWrites) builder.allowFsWrite(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        return builder.build()
    }

    /**
     * Emits a copy-pasteable Kotlin DSL snippet that reproduces the compiled policy.
     * Relative paths in the BoB are resolved against [baseCwd].
     */
    fun toDsl(
        basePolicyName: String = "Policy.PURE_COMPUTE_UNSAFE",
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
        baseCwd: Path? = null,
    ): String {
        val sb = StringBuilder()
        val pOpens = PathNormalizer.normalizeAndPrune(opens, baseCwd)
        val pWrites = PathNormalizer.normalizeAndPrune(fsWritePaths, baseCwd)
        val isThreadLocal = pOpens.isNotEmpty() || pWrites.isNotEmpty()

        val builderCall = if (isThreadLocal) "Policy.threadLocalBuilder()" else "Policy.builder()"
        sb.append("val policy = $builderCall\n")
        sb.append("    .base($basePolicyName)\n")

        val (methodName, list) =
            if (base.defaultAction == io.mazewall.core.SeccompAction.ACT_ALLOW) {
                ".unblock" to syscalls.filter { !base.isSyscallAllowed(it) }.sortedBy { it.name }
            } else {
                ".allow" to syscalls.sortedBy { it.name }
            }

        if (list.isNotEmpty()) {
            sb.append("    $methodName(\n")
            sb.append(list.joinToString(",\n") { "        Syscall.${it.name}" })
            sb.append("\n    )\n")
        }
        for (path in pOpens.sorted()) sb.append("    .allowFsRead(\"$path\")\n")
        for (path in pWrites.sorted()) sb.append("    .allowFsWrite(\"$path\")\n")
        sb.append("    .build()")
        return sb.toString()
    }


    /**
     * Merges two bills of behavior (union of all observations).
     */
    operator fun plus(other: BillOfBehavior): BillOfBehavior {
        val mergedStackProfile = stackProfile.toMutableMap()
        for ((event, traces) in other.stackProfile) {
            val existing = mergedStackProfile[event] ?: emptyList()
            val mergedTraces = (existing + traces).distinctBy { it.toList() }
            mergedStackProfile[event] = mergedTraces
        }

        return BillOfBehavior(
            opens = opens + other.opens,
            fsWritePaths = fsWritePaths + other.fsWritePaths,
            syscalls = syscalls + other.syscalls,
            execs = execs + other.execs,
            stackProfile = mergedStackProfile,
        )
    }

    /**
     * Returns a new [BillOfBehavior] where paths matching the given [BaselinePathProfile]
     * are excluded from [opens], [fsWritePaths], and [execs].
     */
    fun filterPaths(profile: BaselinePathProfile): BillOfBehavior {
        return BillOfBehavior(
            opens = opens.filterNot { profile.matches(it) }.toSet(),
            fsWritePaths = fsWritePaths.filterNot { profile.matches(it) }.toSet(),
            syscalls = syscalls,
            execs = execs.filterNot { profile.matches(it) }.toSet(),
            stackProfile = stackProfile.filterKeys { event ->
                event.paths.none { profile.matches(it) }
            },
        )
    }

    /**
     * Serializes this Bill of Behavior into a clean SBoB JSON string.
     */
    fun toJson(): String {
        val prunedOpens = PathNormalizer.normalizeAndPrune(opens, null).sorted().toSet()
        val prunedWrites = PathNormalizer.normalizeAndPrune(fsWritePaths, null).sorted().toSet()
        val sortedSyscalls = syscalls.sortedBy { it.name }.map { it.name }.toSet()
        val sortedExecs = execs.sorted().toSet()

        val stackEntries = stackProfile.entries.sortedBy { it.key.syscallName }
        val stackProfileDtos = stackEntries.flatMap { (event, tracesList) ->
            tracesList.map { frames ->
                StackProfileEntryDto(
                    syscall = event.syscallName,
                    paths = event.paths,
                    args = if (event is TraceEvent.Generic) event.args else emptyList(),
                    stackTrace = frames.map { frame ->
                        StackFrameDto(
                            classLoader = frame.classLoaderName,
                            module = frame.moduleName,
                            moduleVersion = frame.moduleVersion,
                            className = frame.className,
                            methodName = frame.methodName,
                            fileName = frame.fileName,
                            lineNumber = frame.lineNumber,
                        )
                    },
                )
            }
        }

        val dto = BillOfBehaviorDto(
            opens = prunedOpens,
            fsWritePaths = prunedWrites,
            syscalls = sortedSyscalls,
            execs = sortedExecs,
            stackProfile = stackProfileDtos,
        )

        return jsonSerializer.encodeToString(BillOfBehaviorDto.serializer(), dto)
    }

    /**
     * Serializes only the stackTrace data into a beautifully formatted JSON array.
     */
    fun toStackTracesJson(): String {
        val stackEntries = stackProfile.entries.sortedBy { it.key.syscallName }
        val stackProfileDtos = stackEntries.flatMap { (event, tracesList) ->
            tracesList.map { frames ->
                StackProfileEntryDto(
                    syscall = event.syscallName,
                    paths = event.paths,
                    args = if (event is TraceEvent.Generic) event.args else emptyList(),
                    stackTrace = frames.map { frame ->
                        StackFrameDto(
                            classLoader = frame.classLoaderName,
                            module = frame.moduleName,
                            moduleVersion = frame.moduleVersion,
                            className = frame.className,
                            methodName = frame.methodName,
                            fileName = frame.fileName,
                            lineNumber = frame.lineNumber,
                        )
                    },
                )
            }
        }
        return jsonSerializer.encodeToString(kotlinx.serialization.builtins.ListSerializer(StackProfileEntryDto.serializer()), stackProfileDtos)
    }

    companion object {
        fun fromFile(path: Path): BillOfBehavior {
            return fromJson(Files.readString(path))
        }

        fun fromStream(stream: InputStream): BillOfBehavior {
            val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return fromJson(content)
        }

        fun fromJson(json: String): BillOfBehavior {
            val dto = jsonSerializer.decodeFromString(BillOfBehaviorDto.serializer(), json)

            val mappedSyscalls = dto.syscalls
                .mapNotNull { name ->
                    try {
                        Syscall.valueOf(name.uppercase())
                    } catch (ignored: Exception) {
                        null
                    }
                }.toSet()

            val stackProfile = mutableMapOf<TraceEvent, MutableList<Array<StackTraceElement>>>()
            for (entry in dto.stackProfile) {
                val event = TraceEvent(
                    tidValue = 0,
                    syscallName = entry.syscall,
                    args = entry.args.toLongArray(),
                    paths = entry.paths,
                )
                val frames = entry.stackTrace.map { frameDto ->
                    StackTraceElement(
                        frameDto.classLoader ?: "<unknown>",
                        frameDto.module ?: "<unknown>",
                        frameDto.moduleVersion ?: "<unknown>",
                        frameDto.className,
                        frameDto.methodName,
                        frameDto.fileName ?: "<unknown>",
                        frameDto.lineNumber,
                    )
                }.toTypedArray()
                stackProfile.getOrPut(event) { mutableListOf() }.add(frames)
            }

            return BillOfBehavior(
                opens = dto.opens,
                fsWritePaths = dto.fsWritePaths,
                syscalls = mappedSyscalls,
                execs = dto.execs,
                stackProfile = stackProfile,
            )
        }
    }
}
