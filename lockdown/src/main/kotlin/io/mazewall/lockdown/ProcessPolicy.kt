package io.mazewall.lockdown

import io.mazewall.lockdown.internal.enforcer.api.PolicyDefinition
import io.mazewall.lockdown.internal.enforcer.api.PolicyScope
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import java.util.Collections
import java.util.EnumSet
import java.util.Objects

/**
 * Immutable process-wide seccomp policy for [Mazewall.installOnProcess].
 *
 * Start from one of the curated presets ([fromPreset], [elasticsearch], [openSearch], or
 * [denyProcessCreation]) and refine the blocked syscall set immutably with [block] or [unblock].
 */
public class ProcessPolicy private constructor(
    @get:JvmSynthetic
    internal val runtime: RuntimeProfile,
    private val blocked: Set<LockdownSyscall>,
    @get:JvmSynthetic
    internal val definition: PolicyDefinition<PolicyScope.ProcessWideSafe>,
) {
    /** Returns the configured runtime profile (`HOTSPOT_JIT` or `NATIVE_IMAGE`). */
    public fun runtimeProfile(): RuntimeProfile = runtime

    /** Returns an unmodifiable set of Linux syscalls currently blocked by this policy. */
    public fun blockedSyscalls(): Set<LockdownSyscall> = blocked

    /** Returns a copy of this policy with the specified [syscalls][first] additionally blocked with `EPERM`. */
    public fun block(
        first: LockdownSyscall,
        vararg more: LockdownSyscall,
    ): ProcessPolicy {
        Objects.requireNonNull(first, "LockdownSyscall first argument must not be null")
        Objects.requireNonNull(more, "LockdownSyscall vararg array must not be null")
        val updated = toMutableEnumSet(blocked)
        updated.add(first)
        more.forEachIndexed { index, syscall ->
            updated.add(Objects.requireNonNull(syscall, "LockdownSyscall vararg argument at index $index must not be null"))
        }
        return create(runtime, updated)
    }

    /** Returns a copy of this policy with all [syscalls] additionally blocked with `EPERM`. */
    public fun block(syscalls: Collection<LockdownSyscall>): ProcessPolicy {
        Objects.requireNonNull(syscalls, "syscalls collection must not be null")
        val updated = toMutableEnumSet(blocked)
        syscalls.forEachIndexed { index, syscall ->
            updated.add(Objects.requireNonNull(syscall, "LockdownSyscall element at index $index must not be null"))
        }
        return create(runtime, updated)
    }

    /** Returns a copy of this policy with the specified [syscalls][first] removed from the deny set. */
    public fun unblock(
        first: LockdownSyscall,
        vararg more: LockdownSyscall,
    ): ProcessPolicy {
        Objects.requireNonNull(first, "LockdownSyscall first argument must not be null")
        Objects.requireNonNull(more, "LockdownSyscall vararg array must not be null")
        val updated = toMutableEnumSet(blocked)
        updated.remove(first)
        more.forEachIndexed { index, syscall ->
            updated.remove(Objects.requireNonNull(syscall, "LockdownSyscall vararg argument at index $index must not be null"))
        }
        return create(runtime, updated)
    }

    /** Returns a copy of this policy with all [syscalls] removed from the deny set. */
    public fun unblock(syscalls: Collection<LockdownSyscall>): ProcessPolicy {
        Objects.requireNonNull(syscalls, "syscalls collection must not be null")
        val updated = toMutableEnumSet(blocked)
        syscalls.forEachIndexed { index, syscall ->
            updated.remove(Objects.requireNonNull(syscall, "LockdownSyscall element at index $index must not be null"))
        }
        return create(runtime, updated)
    }

    override fun equals(other: Any?): Boolean = this === other || (other is ProcessPolicy && runtime == other.runtime && definition == other.definition)

    override fun hashCode(): Int = Objects.hash(runtime, definition)

    override fun toString(): String = "ProcessPolicy(runtime=$runtime)"

    public companion object {
        /**
         * Builds a process-wide policy from the selected [preset] and [runtime] profile
         * (defaults to [RuntimeProfile.HOTSPOT_JIT]).
         */
        @JvmStatic
        @JvmOverloads
        public fun fromPreset(
            preset: LockdownPreset,
            runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT,
        ): ProcessPolicy {
            Objects.requireNonNull(preset, "LockdownPreset must not be null")
            Objects.requireNonNull(runtime, "RuntimeProfile must not be null")
            return create(runtime, toMutableEnumSet(preset.defaultBlockedSyscalls()))
        }

        /**
         * Creates an Elasticsearch-compatible process-execution baseline blocking `execve`,
         * `execveat`, `fork`, and `vfork` with `EPERM`.
         */
        @JvmStatic
        @JvmOverloads
        public fun elasticsearch(runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT): ProcessPolicy = fromPreset(LockdownPreset.ELASTICSEARCH, runtime)

        /**
         * Creates an OpenSearch-compatible process and cross-process inspection baseline blocking
         * `execve`, `execveat`, `fork`, `vfork`, `ptrace`, `process_vm_readv`,
         * `process_vm_writev`, and `pidfd_getfd` with `EPERM`.
         */
        @JvmStatic
        @JvmOverloads
        public fun openSearch(runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT): ProcessPolicy = fromPreset(LockdownPreset.OPENSEARCH, runtime)

        /**
         * Creates a process-wide policy blocking external program execution and memory/async
         * execution vectors (`execve`, `execveat`, `memfd_create`, `io_uring_setup`, `io_uring_enter`)
         * with `EPERM`.
         *
         * Equivalent to [fromPreset] with [LockdownPreset.MAZEWALL].
         */
        @JvmStatic
        @JvmOverloads
        public fun denyProcessCreation(runtime: RuntimeProfile = RuntimeProfile.HOTSPOT_JIT): ProcessPolicy = fromPreset(LockdownPreset.MAZEWALL, runtime)

        private fun create(
            runtime: RuntimeProfile,
            syscalls: EnumSet<LockdownSyscall>,
        ): ProcessPolicy {
            val frozenSet = Collections.unmodifiableSet(syscalls)
            val internalSyscalls =
                frozenSet.mapTo(LinkedHashSet(frozenSet.size)) { syscall ->
                    syscall.internalSyscall
                }
            return ProcessPolicy(
                runtime = runtime,
                blocked = frozenSet,
                definition =
                    ProcessFilterInstaller.policyDefinition(
                        blockedSyscalls = internalSyscalls,
                        allowExecutableMappings = runtime == RuntimeProfile.HOTSPOT_JIT,
                    ),
            )
        }

        private fun toMutableEnumSet(source: Collection<LockdownSyscall>): EnumSet<LockdownSyscall> =
            EnumSet.noneOf(LockdownSyscall::class.java).apply {
                addAll(source)
            }
    }
}
