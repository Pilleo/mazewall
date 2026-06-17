package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.core.Pid
import io.mazewall.PolicyScope
import io.mazewall.Uncompiled
import io.mazewall.BillOfBehaviorDto
import io.mazewall.StackProfileEntryDto
import io.mazewall.StackFrameDto
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.profiler.engine.SyscallEvent
import io.mazewall.profiler.engine.SyscallEventState
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json

private val jsonSerializer = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * An immutable record of what kernel-level operations were observed during a
 * profiling run. This is the raw output of the [Profiler] — completely decoupled
 * from any [io.mazewall.Policy].
 *
 * Field naming follows the [Software Bill of Behavior (SBoB) spec draft v0.0.1](https://billofbehavior.com/bob/docs/drafts/spec-v0.0.1/):
 * - [opens]   → SBoB §4.6 `opens`
 * - [execs]   → SBoB §4.5 `execs`
 * - [syscalls] → non-standard extension; all intercepted syscall names
 *
 * ## Absent vs Explicit-Empty
 * Per SBoB §5.4, absent (null) means "unconstrained / not observed" and
 * explicit empty (emptySet()) means "observed zero activity". This class
 * always returns empty sets (never null), because a completed profiling run
 * with zero observations is a valid, meaningful result: the operation touched
 * nothing in that category.
 *
 * ## Stack profiles
 * [stackProfile] captures per-syscall JVM stack traces on a best-effort basis.
 * The worker thread is frozen in the kernel during USER_NOTIF; its Java stack
 * is stable at that moment. Frames are Java-only — no kernel frames are available.
 * This is a precursor to the SBoB stack-profile extension (spec-stackprofile-v0.0.1,
 * not yet published). The encoding will align to OTel Profiles when that extension
 * stabilises.
 *
 * ## Composability
 * Use [plus] to merge two bills from separate profiling runs into a single policy.
 */
data class BillOfBehavior(
    /**
     * Filesystem paths opened for read (open/openat/openat2 with O_RDONLY, stat,
     * readlink, etc.). Maps to SBoB §4.6 `opens` with `flags` indicating read access.
     */
    val opens: Set<String> = emptySet(),
    /**
     * Filesystem paths opened for write, created, or mutated (O_WRONLY, O_RDWR,
     * O_CREAT, O_TRUNC, mkdir, rmdir, rename, chmod, chown, etc.).
     * Maps to SBoB §4.6 `opens` with write-mode flags.
     *
     * ## Design decision: conservative write-path discovery
     * When [io.mazewall.profiler.iterative.IterativeProfiler] catches an AccessDeniedException from Landlock, the
     * exception does not reliably carry the access mode that was denied. Rather than
     * guessing, the profiler conservatively adds the denied path to BOTH [opens] and
     * [fsWritePaths]. This guarantees convergence at the cost of slightly over-permissive
     * Landlock rules — correct for a profiling tool whose goal is discovery, not enforcement.
     */
    val fsWritePaths: Set<String> = emptySet(),
    /**
     * All syscall names intercepted during the run. A superset of what any
     * specific base [io.mazewall.Policy] would block — the caller decides which subset matters
     * via [toPolicy].
     */
    val syscalls: Set<Syscall> = emptySet(),
    /**
     * Child processes spawned (execve/execveat paths). Maps to SBoB §4.5 `execs`.
     */
    val execs: Set<String> = emptySet(),
    /**
     * Per-syscall JVM stack traces captured at the moment the kernel paused the
     * worker thread for USER_NOTIF. The worker thread is blocked in-kernel and its
     * Java stack is frozen at that point — traces are accurate for Java frames.
     *
     * May be empty if profiling was done via IterativeProfiler (Tier A),
     * which does not use USER_NOTIF.
     *
     * Keyed by [SyscallEvent] identity; multiple events for the same syscall name
     * may produce different stack entries if triggered from different call sites.
     */
    val stackProfile: Map<SyscallEvent<SyscallEventState.Resolved>, List<Array<StackTraceElement>>> = emptyMap(),
) {
    /**
     * Compiles this bill of behavior into a [io.mazewall.Policy] starting from [base].
     *
     * Only syscalls that are **actually blocked** by [base] are unblocked —
     * syscalls observed but absent from the base block-list are ignored.
     * This is the correct filter: if a syscall was observed but the base
     * policy never blocked it, calling unblock() on it would be a no-op
     * at best and a footgun if the base policy changes later.
     *
     * All [opens] paths are granted read access; all [fsWritePaths] paths
     * are granted write access.
     */
    fun toPolicy(base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> {
        @Suppress("UNCHECKED_CAST")
        val builder = Policy.threadLocalBuilder().base(base as Policy<PolicyScope.ThreadLocalOnly, *>)
        if (base.defaultAction == io.mazewall.core.SeccompAction.ACT_ALLOW) {
            val toUnblock = syscalls.filter { !base.isSyscallAllowed(it) }
            builder.unblock(*toUnblock.toTypedArray())
        } else {
            builder.allow(*syscalls.toTypedArray())
        }
        val pOpens = pruneSubpaths(opens)
        val pWrites = pruneSubpaths(fsWritePaths)
        for (path in pOpens) builder.allowFsRead(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        for (path in pWrites) builder.allowFsWrite(io.mazewall.core.SandboxedPath.of(path, allowNonExistent = true))
        return builder.build()
    }

    /**
     * Emits a copy-pasteable Kotlin DSL snippet that reproduces the compiled policy.
     * [basePolicyName] is used as the string label in the emitted code.
     */
    fun toDsl(
        basePolicyName: String = "Policy.PURE_COMPUTE_UNSAFE",
        base: Policy<*, Uncompiled> = Policy.PURE_COMPUTE_UNSAFE,
    ): String {
        val sb = StringBuilder()
        val pOpens = pruneSubpaths(opens)
        val pWrites = pruneSubpaths(fsWritePaths)
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

    private fun pruneSubpaths(paths: Set<String>): Set<String> {
        if (paths.size <= 1) return paths

        val sorted = paths.sorted()
        val result = mutableListOf<String>()

        for (path in sorted) {
            val hasChild = sorted.any { other ->
                other != path && other.startsWith(path)
            }
            if (!hasChild) {
                result.add(path)
            }
        }
        return result.toSet()
    }

    /**
     * Merges two bills of behavior (union of all observations).
     * Useful for compiling a single policy across multiple profiling runs
     * covering different code paths of the same application.
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
        val prunedOpens = pruneSubpaths(opens).sorted().toSet()
        val prunedWrites = pruneSubpaths(fsWritePaths).sorted().toSet()
        val sortedSyscalls = syscalls.sortedBy { it.name }.map { it.name }.toSet()
        val sortedExecs = execs.sorted().toSet()

        val stackEntries = stackProfile.entries.sortedBy { it.key.syscallName }
        val stackProfileDtos = stackEntries.flatMap { (event, tracesList) ->
            tracesList.map { frames ->
                StackProfileEntryDto(
                    syscall = event.syscallName,
                    paths = event.paths,
                    args = event.args.toList(),
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
                    args = event.args.toList(),
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
        /**
         * Parses a Bill of Behavior from a Path pointing to an SBoB JSON file.
         */
        fun fromFile(path: Path): BillOfBehavior {
            return fromJson(Files.readString(path))
        }

        /**
         * Parses a Bill of Behavior from a JSON input stream.
         */
        fun fromStream(stream: InputStream): BillOfBehavior {
            val content = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return fromJson(content)
        }

        /**
         * Parses a Bill of Behavior from an SBoB JSON string.
         */
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

            val stackProfile = mutableMapOf<SyscallEvent<SyscallEventState.Resolved>, MutableList<Array<StackTraceElement>>>()
            for (entry in dto.stackProfile) {
                val event = SyscallEvent<SyscallEventState.Resolved>(
                    pid = Pid(0),
                    syscallName = entry.syscall,
                    args = entry.args.map { it }.toLongArray(),
                    paths = entry.paths,
                )
                val frames = entry.stackTrace.map { frameDto ->
                    StackTraceElement(
                        frameDto.classLoader,
                        frameDto.module,
                        frameDto.moduleVersion,
                        frameDto.className,
                        frameDto.methodName,
                        frameDto.fileName,
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
