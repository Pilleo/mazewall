package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StackingIntegrationTest : BaseIntegrationTest() {
    fun testDepthLimit() {
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

        requireDepthLimit {
            // We try to install 34 filters. Kernel limit is 32.
            for (syscall in safeSyscalls.take(34)) {
                val policy =
                    ProcessPolicyBuilder
                        .denyList(RuntimeProfile.NATIVE_IMAGE)
                        .block(Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                        .block(syscall)
                        .build()
                ContainedExecutors.installOnCurrentThread(policy)
            }
        }
    }

    private fun requireDepthLimit(install: () -> Unit) {
        val result = runCatching(install)
        val failure = result.exceptionOrNull()
        if (failure is IllegalStateException && failure.message.orEmpty().contains("32 seccomp filters")) return
        result.getOrThrow()
        throw AssertionError("Successfully installed 34 filters, but limit is 32.")
    }

    @Test
    fun `quality depth limit accepts precise diagnostic`() {
        requireDepthLimit { throw IllegalStateException("Cannot install more than 32 seccomp filters") }
    }

    @Test
    fun `quality depth limit rejects successful installation`() {
        assertThrows(AssertionError::class.java) { requireDepthLimit {} }
    }

    @Test
    fun `quality depth limit preserves wrong type diagnostic`() {
        val failure = IllegalArgumentException("32 seccomp filters")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { requireDepthLimit { throw failure } })
    }

    @Test
    fun `quality depth limit preserves unrelated failure`() {
        val failure = IllegalStateException("unrelated")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { requireDepthLimit { throw failure } })
    }

    @Test
    fun `quality depth limit preserves fatal diagnostic`() {
        val failure = OutOfMemoryError("32 seccomp filters")
        assertSame(failure, assertThrows(OutOfMemoryError::class.java) { requireDepthLimit { throw failure } })
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test filter stacking depth limit`() {
        // Only the precise depth-limit failure is accepted by the isolated operation.
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testDepthLimit")
    }
}
