package io.mazewall.seccomp

import io.mazewall.enforcer.api.PolicyCompilationCache
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.enforcer.api.hasWorkerFilesystemIntent
import io.mazewall.enforcer.engine.FilterInstallationPlanner
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentRegistryEffect
import io.mazewall.enforcer.state.ContainmentRegistryEffectInterpreter
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.RuntimeSnapshot
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Self-contained process-wide seccomp installer without Landlock, supervisor, or Platform dependencies.
 */
internal object ProcessFilterInstaller {
    private const val SELF_VERIFY_PROPERTY = "io.mazewall.selfVerify"
    private val PROC_CMDLINE_PATH = Path.of("/proc/self/cmdline")
    private val APPLICATION_TARGET_FLAGS = setOf("--", "-jar", "-m", "--module")
    private val TWO_TOKEN_JVM_OPTIONS =
        setOf(
            "-cp",
            "-classpath",
            "--class-path",
            "-p",
            "--module-path",
            "--upgrade-module-path",
            "--add-modules",
            "--limit-modules",
            "--add-reads",
            "--add-exports",
            "--add-opens",
            "--patch-module",
            "--describe-module",
            "-d",
            "--source",
        )
    private val processLock = Any()

    private class IncompatiblePolicyException(
        message: String,
    ) : IllegalStateException(message)

    internal val ELASTICSEARCH_BLOCKED_SYSCALLS: Set<Syscall> =
        setOf(
            Syscall.EXECVE,
            Syscall.EXECVEAT,
            Syscall.FORK,
            Syscall.VFORK,
        )

    internal val OPENSEARCH_BLOCKED_SYSCALLS: Set<Syscall> =
        ELASTICSEARCH_BLOCKED_SYSCALLS +
            setOf(
                Syscall.PTRACE,
                Syscall.PROCESS_VM_READV,
                Syscall.PROCESS_VM_WRITEV,
                Syscall.PIDFD_GETFD,
            )

    internal val DENY_PROCESS_CREATION_BLOCKED_SYSCALLS: Set<Syscall> =
        setOf(
            Syscall.EXECVE,
            Syscall.EXECVEAT,
            Syscall.MEMFD_CREATE,
            Syscall.IO_URING_SETUP,
            Syscall.IO_URING_ENTER,
        )

    internal val MAZEWALL_STRICT_BLOCKED_SYSCALLS: Set<Syscall> =
        OPENSEARCH_BLOCKED_SYSCALLS + DENY_PROCESS_CREATION_BLOCKED_SYSCALLS

    internal data class InstalledProcessFilter(
        val filterDepth: Int,
        val arch: Arch,
    )

    internal fun policyDefinition(
        blockedSyscalls: Set<Syscall>,
        allowExecutableMappings: Boolean,
    ): PolicyDefinition<PolicyScope.ProcessWideSafe> =
        PolicyDefinition<PolicyScope.ProcessWideSafe>(
            defaultAction = SeccompAction.ACT_ALLOW,
            syscallActions = blockedSyscalls.associateWith { SeccompAction.ACT_ERRNO() },
            allowMmapExec = allowExecutableMappings,
        ).frozenCopy()

    internal fun denyProcessCreationDefinition(allowExecutableMappings: Boolean): PolicyDefinition<PolicyScope.ProcessWideSafe> =
        policyDefinition(DENY_PROCESS_CREATION_BLOCKED_SYSCALLS, allowExecutableMappings)

    @Suppress("TooGenericExceptionCaught") // Preserves fatal identity and quarantines failed process installations.
    internal fun install(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        native: NativeEngine? = null,
        cmdlineTokens: List<String>? = null,
    ): InstalledProcessFilter {
        validateInstallPreconditions(policy, cmdlineTokens)
        return try {
            executeProcessInstall(policy, native)
        } catch (expectedIncompatible: IncompatiblePolicyException) {
            throw expectedIncompatible
        } catch (expectedFatal: Error) {
            quarantineAndRethrow(expectedFatal)
        } catch (expectedInstallFailure: Throwable) {
            quarantineAndRethrow(expectedInstallFailure)
        }
    }

    private fun quarantineAndRethrow(failure: Throwable): Nothing {
        ContainmentRegistryEffectInterpreter.apply(
            ContainmentRegistryEffect.InstallationFailed(processWide = true, failure = failure),
        )
        throw failure
    }

    private fun validateInstallPreconditions(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        cmdlineTokens: List<String>? = null,
    ) {
        check(!Thread.currentThread().isVirtual) {
            "Cannot install process-wide seccomp lockdown from a virtual thread: " +
                "call Mazewall.installOnProcess from a platform thread (for example, main())."
        }
        ContainerState.resolveCurrentState().installationFailure?.let { throw it }
        require(!policy.hasWorkerFilesystemIntent()) {
            "ProcessFilterInstaller does not support Landlock filesystem rules"
        }
        require(!policy.hasSupervisedSyscalls) {
            "ProcessFilterInstaller does not support supervised ACT_NOTIFY syscalls"
        }
        check(!policy.lockIntelCet) {
            "Process-wide shadow-stack locking is unsupported; CET controls apply to the installing platform thread"
        }
        validateCompatibleJvmOptions(policy, cmdlineTokens)
    }

    private fun validateCompatibleJvmOptions(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        cmdlineTokens: List<String>?,
    ) {
        val blocksProcessCreation =
            !policy.isSyscallAllowed(Syscall.EXECVE) ||
                !policy.isSyscallAllowed(Syscall.EXECVEAT) ||
                !policy.isSyscallAllowed(Syscall.FORK) ||
                !policy.isSyscallAllowed(Syscall.VFORK)
        if (!blocksProcessCreation) return

        val incompatibleFlags = findIncompatibleJvmFatalFlags(cmdlineTokens ?: readCurrentProcessCmdlineTokens())
        check(incompatibleFlags.isEmpty()) {
            "Incompatible JVM fatal-error handler flag(s) detected for no-exec seccomp lockdown: " +
                "${incompatibleFlags.joinToString(", ")}. " +
                "The JVM spawns an external shell via fork/exec on crash or OOM, which lockdown blocks with EPERM. " +
                "Remove -XX:OnError / -XX:OnOutOfMemoryError or replace them with -XX:+ExitOnOutOfMemoryError or -XX:+CrashOnOutOfMemoryError."
        }
    }

    internal fun findIncompatibleJvmFatalFlags(cmdlineTokens: List<String>): List<String> {
        val activeFlags = LinkedHashMap<String, String>()
        for (option in extractJvmOptionsFromCmdline(cmdlineTokens)) {
            when {
                option.startsWith("-XX:OnError=") -> {
                    val value = option.removePrefix("-XX:OnError=")
                    if (value.isBlank()) activeFlags.remove("OnError") else activeFlags["OnError"] = option
                }
                option.startsWith("-XX:OnOutOfMemoryError=") -> {
                    val value = option.removePrefix("-XX:OnOutOfMemoryError=")
                    if (value.isBlank()) activeFlags.remove("OnOutOfMemoryError") else activeFlags["OnOutOfMemoryError"] = option
                }
            }
        }
        return activeFlags.values.toList()
    }

    internal fun extractJvmOptionsFromCmdline(cmdlineTokens: List<String>): List<String> {
        if (cmdlineTokens.size <= 1) return emptyList()
        val options = mutableListOf<String>()
        var expectOptionValue = false
        for (index in 1 until cmdlineTokens.size) {
            val token = cmdlineTokens[index]
            if (expectOptionValue) {
                expectOptionValue = false
                continue
            }
            if (token in APPLICATION_TARGET_FLAGS || token.startsWith("--module=") || !token.startsWith("-")) {
                break
            }
            options.add(token)
            if (token in TWO_TOKEN_JVM_OPTIONS) {
                expectOptionValue = true
            }
        }
        return options
    }

    internal fun parseNulSeparatedTokens(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == 0.toByte()) {
                tokens.add(String(bytes, start, i - start, StandardCharsets.UTF_8))
                start = i + 1
            }
        }
        if (start < bytes.size) {
            tokens.add(String(bytes, start, bytes.size - start, StandardCharsets.UTF_8))
        }
        return tokens
    }

    private fun readCurrentProcessCmdlineTokens(): List<String> =
        if (Files.isReadable(PROC_CMDLINE_PATH)) {
            parseNulSeparatedTokens(Files.readAllBytes(PROC_CMDLINE_PATH))
        } else {
            emptyList()
        }

    private fun executeProcessInstall(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        native: NativeEngine?,
    ): InstalledProcessFilter {
        SeccompPlatformProbe.validateLinux()
        val arch = Arch.current()
        val resolvedNative = native ?: LinuxNative.snapshot()
        if (!SeccompPlatformProbe.isSeccompTsyncSupported(resolvedNative)) {
            throw UnsupportedKernelFeatureException("Process-wide Seccomp synchronization (TSYNC) requires Linux 3.17+.")
        }
        val activeSelfVerify = isExplicitActiveSelfVerifyRequested()
        if (activeSelfVerify && resolvedNative is RuntimeSnapshot) {
            InstallSelfVerifier.warmup()
        }
        return installUnderProcessLock(policy, activeSelfVerify, arch, resolvedNative)
    }

    private fun installUnderProcessLock(
        policy: PolicyDefinition<PolicyScope.ProcessWideSafe>,
        activeSelfVerify: Boolean,
        arch: Arch,
        resolvedNative: NativeEngine,
    ): InstalledProcessFilter =
        synchronized(processLock) {
            ContainerState.resolveCurrentState().installationFailure?.let { throw it }
            val processState = ContainmentStateRegistry.processState
            if (processState.filterDepth > 0 && policy.allowMmapExec && !processState.allowsMmapExec) {
                throw IncompatiblePolicyException(
                    "Cannot apply a policy allowing executable mappings (RuntimeProfile.HOTSPOT_JIT) after " +
                        "executable mappings were already denied (RuntimeProfile.NATIVE_IMAGE); seccomp filters are irreversible.",
                )
            }
            when (val decision = FilterInstallationPlanner.plan(policy, processState)) {
                is FilterInstallationPlanner.PlanDecision.AlreadySatisfied -> Unit
                is FilterInstallationPlanner.PlanDecision.Install -> {
                    val currentState = ContainerState.resolveCurrentState()
                    val priorFilterDepth = currentState.filterDepth
                    FilterInstallationPlanner.verifyFilterDepth(priorFilterDepth)
                    val compiledSandbox =
                        PolicyCompilationCache.getOrCompile(
                            decision.fragment,
                            arch,
                            warnOnUnsafePrctl = policy.allowUnsafePrctl,
                        )
                    val mergedState = currentState.withAppliedFilter(decision.fragment)
                    val publish =
                        ContainmentRegistryEffectInterpreter.prepare(
                            ContainmentRegistryEffect.SeccompInstalled(
                                processWide = true,
                                policy = decision.fragment,
                            ),
                        )
                    PureJavaBpfEngine.installWithPublication(
                        compiledSandbox,
                        processWide = true,
                        native = resolvedNative,
                        isLinux = true,
                        tsyncSupported = true,
                    ) {
                        publish()
                    }
                    verifyPostInstall(
                        activeSelfVerify = activeSelfVerify,
                        program = compiledSandbox.program,
                        arch = arch,
                        priorFilterDepth = priorFilterDepth,
                        mergedState = mergedState,
                        resolvedNative = resolvedNative,
                    )
                }
            }
            InstalledProcessFilter(filterDepth = ContainerState.resolveCurrentState().filterDepth, arch = arch)
        }

    private fun isExplicitActiveSelfVerifyRequested(): Boolean {
        return System.getProperty(SELF_VERIFY_PROPERTY)?.equals("true", ignoreCase = true) == true
    }

    private fun verifyPostInstall(
        activeSelfVerify: Boolean,
        program: BpfProgram<BpfStatus.Verified>,
        arch: Arch,
        priorFilterDepth: Int,
        mergedState: ContainerState,
        resolvedNative: NativeEngine,
    ) {
        if (activeSelfVerify) {
            val selfVerifySnapshot = (resolvedNative as? RuntimeSnapshot)?.takeIf { InstallSelfVerifier.isEnabled(it) }
            if (selfVerifySnapshot != null) {
                InstallSelfVerifier.verify(
                    program,
                    arch,
                    priorFilterDepth,
                    mergedState,
                    selfVerifySnapshot,
                    enabled = true,
                )
            }
            return
        }
        if (resolvedNative is RuntimeSnapshot && resolvedNative.isRealEngine) {
            val pid =
                resolvedNative.raw.syscall(
                    arch.getpid.toLong(),
                    NativeArg.LongArg(0),
                    NativeArg.LongArg(0),
                    NativeArg.LongArg(0),
                    NativeArg.LongArg(0),
                    NativeArg.LongArg(0),
                    NativeArg.LongArg(0),
                )
            check(pid is LinuxNative.SyscallResult.Success && pid.value > 0) {
                "Post-install process liveness failed: $pid"
            }
        }
    }
}
