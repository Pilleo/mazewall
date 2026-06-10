package io.mazewall.seccomp

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.IsolatedProcessTester
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import org.junit.jupiter.api.Test

/**
 * Helper app for isolated execution of [StackingIntegrationTest] methods.
 */
object StackingIsolatedApp {
    @JvmStatic
    fun main(args: Array<String>) {
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

        try {
            for (syscall in safeSyscalls.take(34)) {
                val policy =
                    Policy
                        .builder()
                        .block(Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                        .block(syscall)
                        .build()
                ContainedExecutors.installOnCurrentThread(policy)
            }
            System.err.println("Should have failed after 32 filters")
            System.exit(1)
        } catch (e: IllegalStateException) {
            if (e.message!!.contains("Cannot install more than 32 seccomp filters")) {
                System.exit(0)
            } else {
                System.err.println("Unexpected error message: ${e.message}")
                System.exit(2)
            }
        } catch (e: Throwable) {
            System.err.println("Unexpected error: ${e.message}")
            System.exit(3)
        }
    }
}

class StackingIntegrationTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `test filter stacking depth limit`() {
        IsolatedProcessTester.runIsolatedTest(StackingIsolatedApp::class.java.name, "depth-limit")
    }
}
