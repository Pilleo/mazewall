package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.engine.FilterInstallationPlanner
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentRegistryEffect
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

@NeedsFreshJvm
class AllowListTest : BaseIntegrationTest() {
    /**
     * Canonical JVM floor from JvmFloorPresets (issue-20260823-190000): includes PREAD64 —
     * bootstrap/jrt random-access class reads are positional; a floor missing PREAD64 corrupts
     * lazy bootstrap classloads (ClassFormatError with garbage magic).
     */
    private fun jvmFloor(): Array<Syscall> =
        io.mazewall.enforcer.engine.JvmFloorPresets
        .fullJvmFloor()

    private fun preWarm() {
        // Force loading of classes and native symbols that PureJavaBpfEngine and
        // ContainedExecutors will reference AFTER the ALLOW_LIST filter is installed.
        // The ALLOW_LIST blocks openat (not in jvmFloor), so any class not yet loaded
        // before installation will cause ClassNotFoundException.
        // This is the legitimate use of pre-loading — targeted to THIS test's policy.
        io.mazewall.seccomp.SeccompInstallationState.Uninitialized
            .toString()
        io.mazewall.seccomp.SeccompInstallationState.PrivilegesLocked::class.java.name
        io.mazewall.seccomp.SeccompInstallationState.Verified
            .toString()
        io.mazewall.seccomp.SeccompInstallationState.SystemCallApplied
            .toString()
        io.mazewall.seccomp.SeccompInstallationState.FallbackPrctlApplied
            .toString()
        io.mazewall.seccomp.SeccompInstallationState.FilterBuilt::class.java.name
        io.mazewall.seccomp.SeccompInstallationState.Failed::class.java.name
        Arch.current()
        // Force native symbol linking for LinuxNative downcall stubs

LinuxNative.networking.socket(2, 1, 0)

        val mmap = Arch.current().mmap.toLong()
        if (mmap >= 0) {
LinuxNative.raw.syscall(
    mmap,
    NativeArg.NullArg,
    NativeArg.IntArg(4096),
    NativeArg.NullArg,
    NativeArg.IntArg(0x22),
    NativeArg.IntArg(-1),
    NativeArg.NullArg,
)
        }
    }

    @Test
    fun `ALLOW_LIST mode blocks unlisted syscalls`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool
                .submit {
                    preWarm()

                    val policy =
                        ProcessPolicyBuilder
                            .denyList(RuntimeProfile.NATIVE_IMAGE)
                            .defaultAction(
                                io.mazewall.platform.syscall.SeccompAction
                                .ACT_ERRNO(),
                            ).allow(*jvmFloor())
                            .build()

                    ContainedExecutors.installOnCurrentThread(policy)

                    // socket() is NOT allowed, should fail with EPERM
                    val result =
                    LinuxNative.networking.socket(2, 1, 0)

                    if (result !is LinuxNative.SyscallResult.Error || result.errno != 1) {
                        throw IllegalStateException("Expected EPERM (1), got $result")
                    }
                }.get()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `ALLOW_LIST mode still blocks unsafe arguments even if syscall is allowed`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool
                .submit {
                    preWarm()

                    val policy =
                        ProcessPolicyBuilder
                            .denyList(RuntimeProfile.NATIVE_IMAGE)
                            .defaultAction(
                                io.mazewall.platform.syscall.SeccompAction
                                .ACT_ERRNO(),
                            ).allow(*jvmFloor())
                            .build()

                    ContainedExecutors.installOnCurrentThread(policy)

                    // mmap with PROT_EXEC should fail even though MMAP is allowed
                    val mmap = Arch.current().mmap.toLong()
                    if (mmap >= 0) {
                        val result =
                        LinuxNative.raw.syscall(
                            mmap,
                            NativeArg.NullArg,
                            NativeArg.IntArg(4096),
                            NativeArg.IntArg(0x04 /* PROT_EXEC */),
                            NativeArg.IntArg(0x22),
                            NativeArg.IntArg(-1),
                            NativeArg.NullArg,
                        )

                        if (result !is LinuxNative.SyscallResult.Error || result.errno != 1) {
                            throw IllegalStateException("Expected EPERM (1) for mmap(PROT_EXEC), got $result")
                        }
                    }
                }.get()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun preWarmNarrowing(vararg policies: Policy<PolicyScope.ProcessWideSafe, Uncompiled>) {
        preWarm()
        FilterInstallationPlanner.PlanDecision.AlreadySatisfied.toString()
        val sampleState = ContainerState()
        for (policy in policies) {
            FilterInstallationPlanner.plan(policy.definition, sampleState)
            ContainmentRegistryEffect.SeccompInstalled(false, policy.definition)
        }
        val arch = Arch.current()
        LinuxNative.raw.syscall(
            arch.getppid.toLong(),
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
        )
        LinuxNative.raw.syscall(
            arch.getuid.toLong(),
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
            NativeArg.NullArg,
        )
    }

    @Test
    fun `ALLOW_LIST narrowing denies removed syscall in the kernel`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool
                .submit {
                    val withGetppid =
                        ProcessPolicyBuilder
                            .allowList(RuntimeProfile.HOTSPOT_JIT)
                            .allow(Syscall.GETPPID)
                            .build()
                    val withoutGetppid =
                        ProcessPolicyBuilder
                            .allowList(RuntimeProfile.HOTSPOT_JIT)
                            .build()
                    preWarmNarrowing(withGetppid, withoutGetppid)

                    ContainedExecutors.installOnCurrentThread(withGetppid).requireInstalled()
                    val arch = Arch.current()
                    val allowedResult =
                        LinuxNative.raw.syscall(
                            arch.getppid.toLong(),
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                        )
                    if (allowedResult !is LinuxNative.SyscallResult.Success || allowedResult.value <= 0) {
                        throw IllegalStateException("Expected GETPPID to succeed after first allowList install, got $allowedResult")
                    }

                    val controlDenied =
                        LinuxNative.raw.syscall(
                            arch.getuid.toLong(),
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                        )
                    if (controlDenied !is LinuxNative.SyscallResult.Error || controlDenied.errno != 1) {
                        throw IllegalStateException("Expected GETUID control to fail with EPERM (1), got $controlDenied")
                    }

                    ContainedExecutors.installOnCurrentThread(withoutGetppid).requireInstalled()
                    val narrowedDenied =
                        LinuxNative.raw.syscall(
                            arch.getppid.toLong(),
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                        )
                    if (narrowedDenied !is LinuxNative.SyscallResult.Error || narrowedDenied.errno != 1) {
                        throw IllegalStateException("Expected removed GETPPID to fail with EPERM (1), got $narrowedDenied")
                    }
                }.get()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `LOG default to ERRNO default escalation with identical ALLOW set denies in the kernel`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool
                .submit {
                    val logDefault =
                        ProcessPolicyBuilder
                            .denyList(RuntimeProfile.HOTSPOT_JIT)
                            .defaultAction(SeccompAction.ACT_LOG)
                            .allow(*jvmFloor())
                            .build()
                    val errnoDefault =
                        ProcessPolicyBuilder
                            .allowList(RuntimeProfile.HOTSPOT_JIT)
                            .build()
                    preWarmNarrowing(logDefault, errnoDefault)

                    ContainedExecutors.installOnCurrentThread(logDefault).requireInstalled()
                    val arch = Arch.current()
                    val loggedSuccess =
                        LinuxNative.raw.syscall(
                            arch.getppid.toLong(),
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                        )
                    if (loggedSuccess !is LinuxNative.SyscallResult.Success || loggedSuccess.value <= 0) {
                        throw IllegalStateException("Expected GETPPID to succeed under ACT_LOG default, got $loggedSuccess")
                    }

                    ContainedExecutors.installOnCurrentThread(errnoDefault).requireInstalled()
                    val deniedAfterEscalation =
                        LinuxNative.raw.syscall(
                            arch.getppid.toLong(),
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                            NativeArg.NullArg,
                        )
                    if (deniedAfterEscalation !is LinuxNative.SyscallResult.Error || deniedAfterEscalation.errno != 1) {
                        throw IllegalStateException("Expected GETPPID to fail with EPERM (1) after LOG->ERRNO escalation, got $deniedAfterEscalation")
                    }
                }.get()
        } finally {
            pool.shutdownNow()
        }
    }
}
