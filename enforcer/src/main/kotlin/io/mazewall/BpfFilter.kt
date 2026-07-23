package io.mazewall

import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.seccomp.*
import java.util.logging.Logger

/**
 * Builds seccomp-bpf programs using a robust strictly-forward linear scan approach.
 * This avoids all jump offset overflow and backward-jump issues.
 *
 * ARCHITECTURAL INVARIANT: System call argument inspections are handled via a
 * [io.mazewall.seccomp.SyscallInspectionPipeline]. This enforces the Open/Closed Principle,
 * allowing new inspections (e.g., for `openat2`) to be added to the BPF build loop
 * without modifying the core [BpfFilter] logic.
 */
// @ref: docs/internals/designs/enforcer/containment-design.md — BPF linear scan architecture, instruction limits, and 8-bit relative jump constraint
// @ref: docs/internals/research/jvm-syscall-floor-research.md — JVM coordination syscalls that must never be blocked
object BpfFilter {
    private val logger = Logger.getLogger(BpfFilter::class.java.name)

    internal const val SECCOMP_DATA_NR_OFFSET = 0
    internal const val SECCOMP_DATA_ARCH_OFFSET = 4
    private val SECCOMP_ARGS2_OFFSET = Layouts.SECCOMP_ARGS2_OFFSET.toInt()
    private val SECCOMP_DATA_ARGS_OFFSET = Layouts.SECCOMP_DATA_ARGS_OFFSET.toInt()

    fun build(
        arch: Arch,
        policy: PolicyDefinition<*>,
        profilingMode: Boolean = false,
    ): List<BpfInstruction> =
        buildFromActions(
            arch,
            policy.syscallActionNumbers(arch),
            policy.defaultAction,
            getSyscallInspectionPipeline(),
            policy.allowMmapExec || profilingMode,
            policy.allowNonThreadClone,
            policy.allowUnsafePrctl,
            profilingMode,
        )

    internal fun getSyscallInspectionPipeline(): SyscallInspectionPipeline {
        return DefaultSyscallInspectionPipeline(
            listOf(
                MmapExecInspector(),
                ThreadCloneInspector(),
                UnsafePrctlInspector(),
                Clone3Inspector(),
            )
        )
    }

    private fun resolveNativeAction(
        action: SeccompAction,
        profilingMode: Boolean,
    ): Int {
        return when (action) {
            SeccompAction.ACT_KILL_PROCESS -> NativeConstants.SECCOMP_RET_KILL_PROCESS
            SeccompAction.ACT_KILL_THREAD -> NativeConstants.SECCOMP_RET_KILL_THREAD
            SeccompAction.ACT_TRAP -> NativeConstants.SECCOMP_RET_TRAP
            SeccompAction.ACT_ERRNO -> if (profilingMode) {
                NativeConstants.SECCOMP_RET_USER_NOTIF
            } else {
                (NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM)
            }

            SeccompAction.ACT_NOTIFY -> NativeConstants.SECCOMP_RET_USER_NOTIF
            SeccompAction.ACT_LOG -> NativeConstants.SECCOMP_RET_LOG
            SeccompAction.ACT_ALLOW -> NativeConstants.SECCOMP_RET_ALLOW
        }
    }

    /**
     * Constructs the BPF bytecode using a linear scan approach.
     */
    internal fun buildFromActions(
        arch: Arch,
        syscallActions: Map<Int, SeccompAction>,
        defaultAction: SeccompAction,
        pipeline: io.mazewall.seccomp.SyscallInspectionPipeline,
        allowMmapExec: Boolean = false,
        allowNonThreadClone: Boolean = false,
        allowUnsafePrctl: Boolean = false,
        profilingMode: Boolean = false,
    ): List<BpfInstruction> {
        val effectiveSyscallActions = syscallActions.toMutableMap()

        // --- STACKTRACE PROPAGATION FOR PROCESS SPAWNING ---
        // To enforce stacktrace scoping on execve/execveat, we must first capture the
        // parent thread's stack trace during the spawn entry (fork/vfork/clone).
        // If execve is supervised, we automatically upgrade spawn syscalls to ACT_NOTIFY.
        val execveNr = arch.execve
        val execveatNr = arch.execveat
        val isExecSupervised = effectiveSyscallActions[execveNr] == SeccompAction.ACT_NOTIFY ||
            effectiveSyscallActions[execveatNr] == SeccompAction.ACT_NOTIFY

        if (isExecSupervised) {
            val spawnSyscalls = listOf(arch.fork, arch.vfork, arch.clone).filter { it >= 0 }
            for (nr in spawnSyscalls) {
                // Only upgrade if not already explicitly set to something else by the user
                if (effectiveSyscallActions[nr] == null || effectiveSyscallActions[nr] == SeccompAction.ACT_ALLOW) {
                    effectiveSyscallActions[nr] = SeccompAction.ACT_NOTIFY
                }
            }
        }

        val defaultNativeAction = resolveNativeAction(defaultAction, profilingMode)

        // Syscalls absolutely required for safepoints, GC, and thread stability.
        val jvmCriticalNrs = getJvmCriticalNrs(arch)

        // 1. Initialize Builder and enforce sequence: Arch Check -> Load NR
        val builder = BpfProgram.builder()
            .checkArch(arch)
            .loadSyscallNr()

        // 2. Specialized Syscall Argument Inspections (Plugin-based)
        val handledNrs = mutableSetOf<Int>()
        val context = InspectionContext(
            effectiveSyscallActions,
            defaultAction,
            jvmCriticalNrs,
            allowMmapExec,
            allowNonThreadClone,
            allowUnsafePrctl
        )

        // Collect and emit all argument-based inspections
        val inspections = pipeline.getInspections(arch, context)
        emitInspections(builder, inspections, profilingMode, handledNrs)

        // Emit any special non-arg-based logic (e.g. clone3)
        pipeline.emitSpecial(builder, arch, context, handledNrs)

        // 3. Block-based checks (Linear Scan)
        emitLinearScan(builder, effectiveSyscallActions, jvmCriticalNrs, profilingMode, defaultNativeAction, handledNrs)

        // 4. Default Action & Build
        val instructions = builder.ret(defaultNativeAction).build().instructions
        require(instructions.size <= NativeConstants.BPF_MAXINSNS) {
            "BPF program exceeds kernel maximum instruction limit"
        }
        return instructions
    }

    private fun getJvmCriticalNrs(arch: Arch): Set<Int> =
        setOf(
            Syscall.FUTEX.numberFor(arch),
            Syscall.SCHED_YIELD.numberFor(arch),
            Syscall.RT_SIGRETURN.numberFor(arch),
            Syscall.RT_SIGACTION.numberFor(arch),
            Syscall.MADVISE.numberFor(arch),
            Syscall.GETTID.numberFor(arch),
            Syscall.CLOSE.numberFor(arch),
            Syscall.RT_SIGPROCMASK.numberFor(arch),
            Syscall.MMAP.numberFor(arch),
            Syscall.MPROTECT.numberFor(arch),
            Syscall.PKEY_MPROTECT.numberFor(arch),
        ).filter { it >= 0 }.toSet()

    internal fun emitInspections(
        builder: BpfBuilder<BpfState.NrLoaded>,
        inspections: List<SyscallInspection>,
        profilingMode: Boolean,
        handledNrs: MutableSet<Int>,
    ) {
        for ((idx, inspection) in inspections.withIndex()) {
            val nr = inspection.syscallNumber
            handledNrs.add(nr)

            val ifMatchedNative = resolveNativeAction(inspection.ifMatched, profilingMode)
            val ifNotMatchedNative = resolveNativeAction(inspection.ifNotMatched, profilingMode)

            val labelPrefix = "inspect_${nr}_$idx"
            builder.expect(nr) {
                val argOffsetLo = SECCOMP_DATA_ARGS_OFFSET + inspection.argIndex * 8
                val argOffsetHi = argOffsetLo + 4

                when (val check = inspection.check) {
                    is ArgCheck.EqualsAny -> {
                        val allowLabel = nextLabel("${labelPrefix}_allow")
                        check.allowedValues.forEachIndexed { valIdx, value ->
                            val hi = (value ushr 32).toInt()
                            val lo = value.toInt()

                            val nextValLabel = nextLabel("${labelPrefix}_next_$valIdx")
                            val checkLoLabel = nextLabel("${labelPrefix}_check_lo_$valIdx")

                            loadAbsolute(argOffsetHi)
                            jumpIfEqual(hi, jt = checkLoLabel, jf = nextValLabel)
                            mark(checkLoLabel)
                            loadAbsolute(argOffsetLo)
                            jumpIfEqual(lo, jt = allowLabel, jf = nextValLabel)
                            mark(nextValLabel)
                        }
                        ret(ifNotMatchedNative)
                        mark(allowLabel)
                        ret(ifMatchedNative)
                    }

                    is ArgCheck.MaskEquals -> {
                        val maskHi = (check.mask ushr 32).toInt()
                        val expectedHi = (check.expected ushr 32).toInt()
                        val maskLo = check.mask.toInt()
                        val expectedLo = check.expected.toInt()

                        val checkLoLabel = nextLabel("${labelPrefix}_check_lo")
                        val denyLabel = nextLabel("${labelPrefix}_deny")
                        val allowLabel = nextLabel("${labelPrefix}_allow")

                        loadAbsolute(argOffsetHi)
                        and(maskHi)
                        jumpIfEqual(expectedHi, jt = checkLoLabel, jf = denyLabel)
                        mark(checkLoLabel)
                        loadAbsolute(argOffsetLo)
                        and(maskLo)
                        jumpIfEqual(expectedLo, jt = allowLabel, jf = denyLabel)
                        mark(denyLabel)
                        ret(ifNotMatchedNative)
                        mark(allowLabel)
                        ret(ifMatchedNative)
                    }
                }
            }
        }
    }

    private fun emitLinearScan(
        builder: BpfBuilder<BpfState.NrLoaded>,
        syscallActions: Map<Int, SeccompAction>,
        jvmCriticalNrs: Set<Int>,
        profilingMode: Boolean,
        defaultNativeAction: Int,
        handledNrs: MutableSet<Int>,
    ) {
        val actionsToEmit = mutableListOf<Pair<Int, Int>>()

        for ((nr, action) in syscallActions.entries.sortedBy { it.key }) {
            if (nr !in handledNrs) {
                handledNrs.add(nr)

                val effectiveAction = if (nr in jvmCriticalNrs) SeccompAction.ACT_ALLOW else action
                val nativeAction = resolveNativeAction(effectiveAction, profilingMode)

                if (nativeAction != defaultNativeAction) {
                    actionsToEmit.add(nr to nativeAction)
                }
            }
        }

        // Inject the JVM Immutable Base for restrictive default actions (Whitelists)
        if (defaultNativeAction != NativeConstants.SECCOMP_RET_ALLOW) {
            for (nr in jvmCriticalNrs.sorted()) {
                if (nr in handledNrs) continue
                handledNrs.add(nr)

                actionsToEmit.add(nr to NativeConstants.SECCOMP_RET_ALLOW)
            }
        }

        // Group by nativeAction and ensure deterministic order by sorting keys and values
        val groups = actionsToEmit.groupBy { it.second }
            .mapValues { (_, pairs) -> pairs.map { it.first }.sorted() }
            .toSortedMap()

        for ((nativeAction, nrs) in groups) {
            // To prevent jump offset overflows (limit is 255), we chunk syscalls.
            // Chunk size of 100 ensures max offset is well within boundaries.
            for (chunk in nrs.chunked(100)) {
                val actionLabel = builder.nextLabel("action_ret")
                val skipLabel = builder.nextLabel("skip_ret")

                for (nr in chunk) {
                    builder.jumpIfEqual(nr, jt = actionLabel)
                }

                // Unconditional jump to skip the RET instruction when no syscall in the chunk matched
                builder.jumpIfEqual(0, jt = skipLabel, jf = skipLabel)

                builder.mark(actionLabel)
                builder.ret(nativeAction)

                builder.mark(skipLabel)
            }
        }
    }
}
