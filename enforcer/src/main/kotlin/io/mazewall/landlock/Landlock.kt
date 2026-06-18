package io.mazewall.landlock

import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyDefinition
import io.mazewall.PolicyPresets
import io.mazewall.UnsupportedKernelFeatureException
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.PrctlCommand
import io.mazewall.core.FdState
import io.mazewall.core.SandboxedPath
import io.mazewall.core.Syscall
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.LandlockPathBeneathAttrSegment
import io.mazewall.ffi.memory.LandlockRulesetAttrSegment
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.getFdOrThrow
import io.mazewall.onFailure
import io.mazewall.onSuccess
import io.mazewall.recover
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.logging.Logger

// SUPPRESSION JUSTIFICATION: This object serves as the single cohesive boundary wrapping the Landlock LSM.
// Splitting the path wrapping, layout allocation, ABI query, and rule-building functions into
// multiple files would severely fragment the safety-critical FFM logic and reduce readability.

/**
 * Unprivileged, path-aware filesystem sandbox using the Linux Landlock LSM.
 *
 * Landlock restricts filesystem access at the **thread** level (via `landlock_restrict_self`).
 * Unlike seccomp-bpf, which operates on syscall numbers, Landlock reasons about **paths and
 * inodes**, making it the correct tool for constraining *where* a thread can read/write
 * rather than *what syscalls* it can invoke.
 *
 * ### How Landlock works
 * 1. A **ruleset** is created declaring which access categories the caller intends to restrict
 *    (the `handled_access_fs` bitmask). Any access category **not** listed here is left
 *    globally unrestricted—this is a "default-deny for listed categories" model.
 * 2. **Rules** are added to the ruleset granting specific access rights to specific paths
 *    (identified by file descriptors opened with `O_PATH`).
 * 3. `landlock_restrict_self` applies the ruleset to the calling thread. Future children
 *    inherit the restrictions, and nested rulesets **intersect** (access must be allowed by
 *    *all* stacked rulesets).
 *
 * ### Stacking & thread-pool interaction
 * Landlock rulesets are permanent and additive-intersective for the lifetime of the OS thread.
 * In a recycled thread pool, every task execution that calls [applyRuleset] pushes a new
 * layer. After ~16 layers the kernel returns `E2BIG`. Callers (i.e. [io.mazewall.enforcer.ContainedExecutors])
 * must track whether a thread has already been restricted and skip re-application.
 *
 * ### ABI versioning
 * The kernel exposes the supported ABI version via `landlock_create_ruleset` with the
 * `LANDLOCK_CREATE_RULESET_VERSION` flag. Newer ABIs add access categories:
 * - **ABI v1** (Linux 5.13): base filesystem rights
 * - **ABI v2** (Linux 5.19): `FS_REFER` (cross-directory rename/link)
 * - **ABI v3** (Linux 6.2): `FS_TRUNCATE`
 * - **ABI v4** (Linux 6.7): network restrictions (`handled_access_net`)
 * - **ABI v5** (Linux 6.10): `FS_IOCTL_DEV`
 *
 * This implementation includes ABI-conditional flags so that newer capabilities are used
 * automatically when available, while remaining safe on older kernels.
 *
 * ### Process-wide Landlock & TSYNC
 * Historically (ABI v1 to v7), `landlock_restrict_self` only affected the calling thread and its
 * future children, and could **not** retroactively restrict existing sibling threads. To achieve
 * true process-wide Landlock on these older kernels, a launcher wrapper (e.g. native C/Rust binary)
 * must apply the ruleset *before* `execve`-ing the JVM, so that all JVM threads inherit it.
 *
 * Stacking with **ABI v8 (Linux 7.0)**, the kernel introduces `LANDLOCK_RESTRICT_SELF_TSYNC` which
 * permits retroactive process-wide synchronization from within an existing thread. However, because
 * retroactive process-wide restriction breaks sibling thread transparency in multi-threaded JVMs
 * (GC, JIT, and test runners), `mazewall` disables it by default in standard enforcement.
 *
 * @see io.mazewall.Policy.Builder.allowFsRead
 * @see io.mazewall.Policy.Builder.allowFsWrite
 * @see io.mazewall.Policy.Builder.allowJvmClasspath
 * @see io.mazewall.enforcer.ContainedExecutors.wrap
 */
object Landlock {
    /**
     * ARCHITECTURAL INVARIANT: Landlock ruleset mutability is enforced via the Type-State
     * pattern (using RulesetState.Building and RulesetState.Sealed). This ensures that
     * rules can only be added before the ruleset is restricted, preventing runtime errors
     * or silent ignore-failures when attempting post-enforcement mutations.
     */
    private val logger = Logger.getLogger(Landlock::class.java.name)

    /** Apply ruleset to all threads of the process. ABI v8+ (Linux 7.0). */
    private const val LANDLOCK_RESTRICT_SELF_TSYNC = (1L shl 3)

    private const val ERRNO_EINVAL = 22
    private const val ERRNO_ELOOP = 40
    private const val ABI_V3 = 3
    private const val ABI_V5 = 5

    // ── Filesystem access flags ─────────────────────────────────────────
    // Each flag corresponds to a bit in the kernel's `handled_access_fs` / `allowed_access`
    // bitmask. See `linux/landlock.h` for authoritative definitions.

    /** Allow executing a file (applies to regular files only). ABI v1+. */
    const val LANDLOCK_ACCESS_FS_EXECUTE = (1L shl 0)

    /** Allow opening a file with write access. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_WRITE_FILE = (1L shl 1)

    /** Allow opening a file with read access. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_READ_FILE = (1L shl 2)

    /** Allow listing directory contents (readdir). ABI v1+. */
    const val LANDLOCK_ACCESS_FS_READ_DIR = (1L shl 3)

    /** Allow removing an empty directory. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_REMOVE_DIR = (1L shl 4)

    /** Allow unlinking / removing a file. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_REMOVE_FILE = (1L shl 5)

    /** Allow creating a character device. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_CHAR = (1L shl 6)

    /** Allow creating a directory. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_DIR = (1L shl 7)

    /** Allow creating a regular file. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_REG = (1L shl 8)

    /** Allow creating a Unix domain socket. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_SOCK = (1L shl 9)

    /** Allow creating a named pipe (FIFO). ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_FIFO = (1L shl 10)

    /** Allow creating a block device. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_BLOCK = (1L shl 11)

    /** Allow creating a symbolic link. ABI v1+. */
    const val LANDLOCK_ACCESS_FS_MAKE_SYM = (1L shl 12)

    /** Allow linking or renaming a file across directories. ABI v2+ (Linux 5.19). */
    const val LANDLOCK_ACCESS_FS_REFER = (1L shl 13)

    /** Allow truncating a file. ABI v3+ (Linux 6.2). */
    const val LANDLOCK_ACCESS_FS_TRUNCATE = (1L shl 14)

    /** Allow `ioctl` on device files. ABI v5+ (Linux 6.10). */
    const val LANDLOCK_ACCESS_FS_IOCTL_DEV = (1L shl 15)

    /**
     * Applies a restrictive Landlock ruleset that handles all categories but only
     * allows JVM classpath reads. This forces synchronous denials on the calling thread,
     * which can be caught and resolved by the IterativeProfiler without needing audit logs.
     */
    fun applyRestrictiveBarrier() {
        val session = LandlockSession(policy = null)
        session.applyRuleset()
    }

    /**
     * Returns `true` if the running kernel supports Landlock (ABI v1+) and the OS is Linux.
     */
    fun isSupported(): Boolean {
        if (!System.getProperty("os.name").equals("Linux", ignoreCase = true)) return false
        return getAbiVersion() >= 1
    }

    /**
     * Queries the kernel for the highest Landlock ABI version it supports.
     */
    fun getAbiVersion(): Int {
        return LinuxNative.withTransaction {
            LinuxNative.syscall(
                NativeConstants.LANDLOCK_CREATE_RULESET_NR,
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(0L),
                io.mazewall.core.NativeArg.LongArg(NativeConstants.LANDLOCK_CREATE_RULESET_VERSION),
            )
        }.recover { _, _ -> 0L }.toInt()
    }

    /**
     * Builds and applies a Landlock ruleset to the calling thread based on the given [policy].
     *
     * @param processWide If true, attempts to synchronize the ruleset across all threads (Linux 7.0+).
     */
    fun applyRuleset(policy: PolicyDefinition<*>, processWide: Boolean = false) {
        if (!shouldApplyLandlock(policy)) return

        val session = LandlockSession(policy, processWide)
        session.applyRuleset()
    }

    internal fun getFullAccessMask(abi: Int): Long {
        var mask = LANDLOCK_ACCESS_FS_READ_FILE or LANDLOCK_ACCESS_FS_READ_DIR or
            LANDLOCK_ACCESS_FS_EXECUTE or LANDLOCK_ACCESS_FS_WRITE_FILE or
            LANDLOCK_ACCESS_FS_REMOVE_DIR or LANDLOCK_ACCESS_FS_REMOVE_FILE or
            LANDLOCK_ACCESS_FS_MAKE_CHAR or LANDLOCK_ACCESS_FS_MAKE_DIR or
            LANDLOCK_ACCESS_FS_MAKE_REG or LANDLOCK_ACCESS_FS_MAKE_SOCK or
            LANDLOCK_ACCESS_FS_MAKE_FIFO or LANDLOCK_ACCESS_FS_MAKE_BLOCK or
            LANDLOCK_ACCESS_FS_MAKE_SYM
        if (abi >= 2) mask = mask or LANDLOCK_ACCESS_FS_REFER
        if (abi >= ABI_V3) mask = mask or LANDLOCK_ACCESS_FS_TRUNCATE
        if (abi >= ABI_V5) mask = mask or LANDLOCK_ACCESS_FS_IOCTL_DEV
        return mask
    }

    private fun shouldApplyLandlock(policy: PolicyDefinition<*>) =
        policy.enforceLandlock ||
            policy.allowedFsReadPaths.isNotEmpty() ||
            policy.allowedFsWritePaths.isNotEmpty()

    internal fun handleUnsupportedLandlock() {
        val fallback = Platform.configuredFallback()
        if (fallback == Platform.FallbackBehavior.FAIL) {
            throw UnsupportedOperationException("Landlock is not supported on this kernel but FS rules were requested.")
        } else if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
            logger.warning("Landlock not supported, FS rules will be ignored.")
        }
    }

    private fun createRulesetOrThrow(
        arena: Arena,
        mask: Long,
        abi: Int,
    ): FileDescriptor<FileDescriptorRole.Ruleset, FdState.Open> {
        return with(arena) { createRuleset(mask, abi) }.getFdOrThrow("landlock_create_ruleset").let { FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(it.value) }
    }

    context(arena: Arena)
    internal fun addJvmClasspathRules(
        ruleset: LandlockRuleset<RulesetState.Building>,
        accessFlags: Long,
    ) {
        System.getProperty("java.home")?.let { addRuleFollowSymlinks(ruleset, it, accessFlags) }

        val classPath = System.getProperty("java.class.path") ?: return
        val seen = mutableSetOf<String>()
        classPath.split(File.pathSeparator).forEach {
            val file = File(it)
            if (file.exists()) {
                val dir = if (file.isDirectory) file.absolutePath else file.parent
                if (seen.add(dir)) {
                    addRuleFollowSymlinks(ruleset, dir, accessFlags)
                }
            }
        }
    }

    context(arena: Arena)
    private fun addRuleFollowSymlinks(
        ruleset: LandlockRuleset<RulesetState.Building>,
        path: String,
        allowedAccess: Long,
    ) {
        val pathSegment = arena.allocateFrom(path)
        val fdResult = LinuxNative.withTransaction {
            LinuxNative.fileSystem.open(pathSegment, NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)
        }

        fdResult.onSuccess { value ->
            val pathFd = FileDescriptor.unsafe<FileDescriptorRole.OPath>(value.toInt())
            try {
                val addResult = addRuleToRuleset(ruleset, pathFd, allowedAccess)
                addResult.onFailure { errno, _ ->
                    logger.warning("landlock_add_rule failed for JVM classpath $path with errno $errno")
                }
            } finally {
                LinuxNative.fileSystem.close(pathFd)
            }
        }.onFailure { errno, _ ->
            logger.warning("Could not open JVM classpath $path for landlock rule: errno $errno")
        }
    }

    context(arena: Arena)
    private fun addRule(
        ruleset: LandlockRuleset<RulesetState.Building>,
        path: SandboxedPath,
        allowedAccess: Long,
    ) {
        val resolvedPath = path.value
        val openFlags = NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or NativeConstants.O_NOFOLLOW
        val initialResult = LinuxNative.withTransaction {
            LinuxNative.fileSystem.open(arena.allocateFrom(resolvedPath), openFlags)
        }

        val (fdResult, isFallback) = handleInitialOpenFailure(initialResult, resolvedPath, openFlags)

        fdResult.onSuccess { value ->
            val pathFd = FileDescriptor.unsafe<FileDescriptorRole.OPath>(value.toInt())
            try {
                val finalAccess = calculateFinalAccess(allowedAccess, isFallback, resolvedPath)
                addRuleToRulesetAndVerify(ruleset, pathFd, finalAccess, resolvedPath)
            } finally {
                LinuxNative.fileSystem.close(pathFd)
            }
        }.onFailure { errno, _ ->
            logOpenFailure(resolvedPath, errno)
        }
    }

    context(arena: Arena)
    private fun handleInitialOpenFailure(
        res: LinuxNative.SyscallResult<Long, *>,
        resolvedPath: String,
        flags: Int,
    ): Pair<LinuxNative.SyscallResult<Long, *>, Boolean> {
        if (res is LinuxNative.SyscallResult.Error<*> && res.errno == 2) { // ENOENT
            if (resolvedPath.endsWith(" (deleted)")) {
                return res to false
            }
            val parentPath = File(resolvedPath).parent ?: "/"
            logger.info("Path $resolvedPath does not exist, falling back to parent directory: $parentPath")
            val openResult = LinuxNative.withTransaction {
                LinuxNative.fileSystem.open(arena.allocateFrom(parentPath), flags)
            }
            return openResult to true
        }
        return res to false
    }

    private fun logOpenFailure(
        path: String,
        errno: Int,
    ) {
        if (errno == ERRNO_ELOOP) {
            logger.warning("Path $path is a symlink and was rejected (O_NOFOLLOW). Use the resolved real path instead.")
        } else {
            logger.warning("Could not open path $path for landlock rule: errno $errno")
        }
    }

    private fun calculateFinalAccess(
        allowedAccess: Long,
        isFallback: Boolean,
        resolvedPath: String,
    ): Long {
        if (isFallback || File(resolvedPath).isFile) {
            val dirOnlyFlags = LANDLOCK_ACCESS_FS_READ_DIR or LANDLOCK_ACCESS_FS_MAKE_DIR or LANDLOCK_ACCESS_FS_REMOVE_DIR
            return allowedAccess and dirOnlyFlags.inv()
        }
        return allowedAccess
    }

    context(arena: Arena)
    private fun addRuleToRulesetAndVerify(
        ruleset: LandlockRuleset<RulesetState.Building>,
        pathFd: FileDescriptor<FileDescriptorRole.OPath, FdState.Open>,
        access: Long,
        path: String,
    ) {
        val res = addRuleToRuleset(ruleset, pathFd, access)
        if (res is LinuxNative.SyscallResult.Error<*>) {
            if (res.errno == ERRNO_EINVAL) {
                logger.warning("landlock_add_rule rejected $path (EINVAL) — path may be a symlink or unsupported inode type.")
            } else {
                throw IllegalStateException("landlock_add_rule failed for $path with errno ${res.errno}")
            }
        }
    }

    internal fun enforceRuleset(
        ruleset: LandlockRuleset<RulesetState.Building>,
        processWide: Boolean = false
    ): LandlockRuleset<RulesetState.Sealed> {
        val (prctlResult, restrictResult) = LinuxNative.withTransaction {
            val p = LinuxNative.process.prctl(PrctlCommand.SetNoNewPrivs(true))

            val flags = if (processWide) LANDLOCK_RESTRICT_SELF_TSYNC else 0L
            val r = LinuxNative.syscall(
                NativeConstants.LANDLOCK_RESTRICT_SELF_NR,
                io.mazewall.core.NativeArg.FdArg(ruleset.fd),
                io.mazewall.core.NativeArg.LongArg(flags),
                io.mazewall.core.NativeArg.MemoryArg(MemorySegment.NULL),
                io.mazewall.core.NativeArg.IntArg(0)
            )
            Pair(p, r)
        }
        prctlResult.getOrThrow("prctl(PR_SET_NO_NEW_PRIVS)")
        restrictResult.getOrThrow("landlock_restrict_self")
        return LandlockRuleset(ruleset.fd)
    }

    context(arena: Arena)
    internal fun applyUserRules(
        ruleset: LandlockRuleset<RulesetState.Building>,
        policy: PolicyDefinition<*>,
        abi: Int,
        allFsRead: Long,
    ) {
        policy.allowedFsReadPaths.forEach { addRule(ruleset, it, allFsRead) }
        val writeFlags = LANDLOCK_ACCESS_FS_WRITE_FILE or LANDLOCK_ACCESS_FS_MAKE_REG or
            LANDLOCK_ACCESS_FS_MAKE_DIR or LANDLOCK_ACCESS_FS_REMOVE_FILE or
            LANDLOCK_ACCESS_FS_REMOVE_DIR or (if (abi >= ABI_V3) LANDLOCK_ACCESS_FS_TRUNCATE else 0)
        policy.allowedFsWritePaths.forEach { addRule(ruleset, it, writeFlags) }
    }

    internal fun getAccessMask(
        abi: Int,
        policy: PolicyDefinition<*>,
    ): Long {
        var accessMaskFs = getFullAccessMask(1) // Base mask
        if (abi >= 2) accessMaskFs = accessMaskFs or LANDLOCK_ACCESS_FS_REFER
        if (abi >= ABI_V3) accessMaskFs = accessMaskFs or LANDLOCK_ACCESS_FS_TRUNCATE
        if (abi >= ABI_V5) accessMaskFs = accessMaskFs or LANDLOCK_ACCESS_FS_IOCTL_DEV

        validateAbiSupport(abi, policy)
        return accessMaskFs
    }

    private fun validateAbiSupport(
        abi: Int,
        policy: PolicyDefinition<*>,
    ) {
        val unsupportedErrors = mutableListOf<String>()
        if (abi < 2 && (policy.isSyscallAllowed(Syscall.RENAME) || policy.isSyscallAllowed(Syscall.LINK))) {
            unsupportedErrors.add("Policy allows rename/link syscalls, but this kernel is too old (ABI v$abi)")
        }
        if (abi < ABI_V3 && (policy.isSyscallAllowed(Syscall.TRUNCATE) || policy.isSyscallAllowed(Syscall.FTRUNCATE))) {
            unsupportedErrors.add("Policy allows truncate syscalls, but this kernel is too old (ABI v$abi)")
        }
        if (abi < ABI_V5 && policy.isSyscallAllowed(Syscall.IOCTL)) {
            unsupportedErrors.add("Policy allows ioctl, but this kernel is too old (ABI v$abi)")
        }
        if (unsupportedErrors.isNotEmpty()) {
            val msg = "Fatal Security Error: ${unsupportedErrors.joinToString("; ")}"
            val fallback = Platform.configuredFallback()
            if (fallback == Platform.FallbackBehavior.FAIL) {
                throw UnsupportedOperationException(msg)
            } else {
                logger.warning(msg)
            }
        }
    }

    context(arena: Arena)
    internal fun createRuleset(
        accessMaskFs: Long,
        abi: Int,
    ): LinuxNative.SyscallResult<Long, *> {
        val rulesetAttr = LandlockRulesetAttrSegment.allocate()
        rulesetAttr.setHandledAccessFs(accessMaskFs)
        rulesetAttr.setHandledAccessNet(0L)
        val size = if (abi >= 4) Layouts.LANDLOCK_RULESET_ATTR.byteSize() else Layouts.LANDLOCK_RULESET_ATTR_V1_SIZE
        return LinuxNative.withTransaction {
            LinuxNative.syscall(
                NativeConstants.LANDLOCK_CREATE_RULESET_NR,
                io.mazewall.core.NativeArg.MemoryArg(rulesetAttr.segment),
                io.mazewall.core.NativeArg.LongArg(size),
                io.mazewall.core.NativeArg.MemoryArg(MemorySegment.NULL)
            )
        }
    }

    context(arena: Arena)
    private fun addRuleToRuleset(
        ruleset: LandlockRuleset<RulesetState.Building>,
        pathFd: FileDescriptor<FileDescriptorRole.OPath, FdState.Open>,
        accessMask: Long,
    ): LinuxNative.SyscallResult<Long, *> {
        val pathAttr = LandlockPathBeneathAttrSegment.allocate()
        pathAttr.setAllowedAccess(accessMask)
        pathAttr.setParentFd(pathFd.value)
        return LinuxNative.withTransaction {
            LinuxNative.syscall(
                NativeConstants.LANDLOCK_ADD_RULE_NR,
                io.mazewall.core.NativeArg.FdArg(ruleset.fd),
                io.mazewall.core.NativeArg.LongArg(NativeConstants.LANDLOCK_RULE_PATH_BENEATH.toLong()),
                io.mazewall.core.NativeArg.MemoryArg(pathAttr.segment),
                io.mazewall.core.NativeArg.IntArg(0)
            )
        }
    }
}

internal class LandlockSession(
    private val policy: PolicyDefinition<*>? = null,
    private val processWide: Boolean = false,
) {
    var state: LandlockState = LandlockState.Uninitialized
        private set

    @Suppress("TooGenericExceptionCaught")
    fun applyRuleset() {
        val features = Platform.featureMatrix
        val abi = features.landlockAbiVersion
        state = LandlockState.QueryingAbi(abi)

        if (processWide && !features.landlockTsyncSupported) {
            handleProcessWideUnsupported()
            // If we are warning and bypassing, we only continue if there are no rules.
            // But if there are rules, we continue and apply them to the current thread only.
            // Documentation states this is the accepted risk.
        }

        if (abi < 1) {
            if (policy != null) {
                Landlock.handleUnsupportedLandlock()
            }
            state = LandlockState.Applied
            return
        }

        val accessMaskFs = if (policy != null) {
            Landlock.getAccessMask(abi, policy)
        } else {
            Landlock.getFullAccessMask(abi)
        }

        state = LandlockState.CreatingRuleset(abi)
        nativeScope {
            val rulesetFdResult = Landlock.createRuleset(accessMaskFs, abi)
            val rulesetFd = rulesetFdResult.recover { errno, _ ->
                val err = IllegalStateException("landlock_create_ruleset failed with errno $errno")
                state = LandlockState.Failed(err)
                throw err
            }.let { FileDescriptor.unsafe<FileDescriptorRole.Ruleset>(it.toInt()) }

            val ruleset = LandlockRuleset<RulesetState.Building>(rulesetFd)
            val created = LandlockLifecycle.RulesetCreated(ruleset, abi, policy)
            state = LandlockState.ConfiguringRuleset(rulesetFd, abi)
            try {
                val added = created.addRules(this)
                state = LandlockState.Enforcing(rulesetFd)
                added.restrictSelf(processWide)
                state = LandlockState.Applied
            } catch (e: Exception) {
                state = LandlockState.Failed(e)
                throw e
            } finally {
                LinuxNative.fileSystem.close(rulesetFd)
            }
        }
    }

    private fun handleProcessWideUnsupported() {
        val fallback = Platform.configuredFallback()
        val msg = "Process-wide Landlock (TSYNC) requires Linux 7.0+ (ABI v8). This kernel supports ABI v${Platform.featureMatrix.landlockAbiVersion}."
        if (fallback == Platform.FallbackBehavior.FAIL) {
            throw UnsupportedKernelFeatureException(msg)
        } else if (fallback == Platform.FallbackBehavior.WARN_AND_BYPASS) {
            java.util.logging.Logger.getLogger(Landlock::class.java.name).warning("$msg Rules will only be applied to the current thread and its descendants.")
        }
    }
}
