package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.LinuxNative
import io.mazewall.NeedsFreshJvm
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.NativeArg
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
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
        io.mazewall.enforcer.engine.JvmFloorPresets.fullJvmFloor()

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
                        Policy
                            .builder()
                            .defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO())
                            .allow(*jvmFloor())
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
                        Policy
                            .builder()
                            .defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO())
                            .allow(*jvmFloor())
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
}
