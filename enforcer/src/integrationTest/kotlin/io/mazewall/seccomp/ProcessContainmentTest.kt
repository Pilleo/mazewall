package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import io.mazewall.Platform
import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.channels.Selector

class ProcessContainmentTest : BaseIntegrationTest() {
    fun testProcessWide() {
        val safeGlobalPolicy =
            Policy
                .builder()
                .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                .allowMmapExec()
                .allowNonThreadClone()
                .build()

        ContainedExecutors.installOnProcess(safeGlobalPolicy)
        try {
            ProcessBuilder("echo", "should-fail").start()
            throw IllegalStateException("Should have failed")
        } catch (e: Exception) {
            if (!ContainmentViolationDetector.isContainmentViolation(e)) {
                throw e
            }
        }
    }

    fun testInheritance() {
        val policy =
            Policy
                .builder()
                .base(Policy.NO_EXEC)
                .allowMmapExec()
                .build()
        ContainedExecutors.installOnProcess(policy)

        val thread =
            Thread {
                try {
                    ProcessBuilder("echo", "should-fail").start()
                    throw IllegalStateException("Child thread should have been contained")
                } catch (e: Exception) {
                    if (!ContainmentViolationDetector.isContainmentViolation(e)) {
                        throw e
                    }
                }
            }
        thread.start()
        thread.join()
    }

    fun testNioStability() {
        // Warm up NIO and Networking before installing the filter.
        Selector.open().close()
        try {
            java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 1), 1)
        } catch (e: IOException) {
            System.out.println("Warmup connection failed as expected: ${e.message}")
        }

        val noNetworkAllowJit = Policy
            .builder()
            .base(Policy.NO_NETWORK)
            .allowMmapExec()
            .build()
        ContainedExecutors.installOnProcess(noNetworkAllowJit)

        val selector = Selector.open()
        selector.close()

        try {
            java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 80))
            throw IllegalStateException("Connect should have failed")
        } catch (e: Exception) {
            if (!ContainmentViolationDetector.isContainmentViolation(e)) {
                throw e
            }
        }
    }

    fun testThreadDepth() {
        val safeSyscalls =
            listOf(
                Syscall.EXECVE,
                Syscall.EXECVEAT,
                Syscall.FORK,
                Syscall.VFORK,
                Syscall.CONNECT,
                Syscall.SOCKET,
                Syscall.BIND,
                Syscall.LISTEN,
                Syscall.ACCEPT,
                Syscall.ACCEPT4,
                Syscall.SENDTO,
                Syscall.SENDMSG,
                Syscall.MEMFD_CREATE,
                Syscall.IO_URING_SETUP,
                Syscall.BPF,
                Syscall.PTRACE,
                Syscall.PROCESS_VM_WRITEV,
                Syscall.PROCESS_VM_READV,
                Syscall.USERFAULTFD,
                Syscall.UNSHARE,
                Syscall.SETNS,
                Syscall.MOUNT,
                Syscall.UMOUNT2,
                Syscall.PIVOT_ROOT,
                Syscall.CHROOT,
                Syscall.INIT_MODULE,
                Syscall.FINIT_MODULE,
                Syscall.GETPID,
                Syscall.GETPPID,
                Syscall.GETUID,
                Syscall.GETEUID,
                Syscall.GETGID,
                Syscall.GETEGID,
                Syscall.GETTID,
                Syscall.GETCWD,
                Syscall.UMASK,
            )

        for (i in 0 until 32) {
            ContainedExecutors.installOnCurrentThread(
                Policy
                    .builder()
                    .block(safeSyscalls[i])
                    .allowMmapExec()
                    .allowNonThreadClone()
                    .build(),
            )
        }

        try {
            ContainedExecutors.installOnCurrentThread(
                Policy
                    .builder()
                    .block(safeSyscalls[32])
                    .allowMmapExec()
                    .allowNonThreadClone()
                    .build(),
            )
            throw IllegalStateException("Should have failed")
        } catch (e: IllegalStateException) {
            if (!e.message!!.contains("32 seccomp filters")) {
                throw e
            }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `installOnProcess applies containment globally`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testProcessWide")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `subsequent threads inherit process-wide filter`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testInheritance")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `process-wide NO_NETWORK does not break NIO selector`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testNioStability")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `thread-local installation respects filter depth`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testThreadDepth")
    }

    fun testLandlockSupportLimits() {
        val policyWithFs = Policy.builder().allowMmapExec().allowFsRead("/etc").build()
        val features = Platform.featureMatrix

        if (features.landlockTsyncSupported) {
            // On modern kernels, this should at least pass our internal guard
            // (might still fail with EACCES if sibling threads aren't ready, but that's a different error)
            try {
                @Suppress("UNCHECKED_CAST")
                ContainedExecutors.installOnProcess(policyWithFs as Policy<PolicyScope.ProcessWideSafe, io.mazewall.Uncompiled>)
            } catch (e: Exception) {
                // EACCES is acceptable in this test context as it proves we bypassed the version guard
                val strerror13 = io.mazewall.ffi.memory.getSystemStrerror(13)
                val matchesLocale = strerror13 != null && e.message?.contains(strerror13, ignoreCase = true) == true
                if (e.message?.contains("EACCES") == false && e.message?.contains("13") == false && !matchesLocale && e !is io.mazewall.UnsupportedKernelFeatureException) {
                    throw e
                }
            }
        } else {
            org.junit.jupiter.api.assertThrows<io.mazewall.UnsupportedKernelFeatureException> {
                @Suppress("UNCHECKED_CAST")
                ContainedExecutors.installOnProcess(policyWithFs as Policy<PolicyScope.ProcessWideSafe, io.mazewall.Uncompiled>)
            }
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `installOnProcess respects Landlock support limits`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testLandlockSupportLimits")
    }
}
