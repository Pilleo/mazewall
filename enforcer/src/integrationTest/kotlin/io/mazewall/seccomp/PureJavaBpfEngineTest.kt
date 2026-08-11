package io.mazewall.seccomp
import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.compile
import io.mazewall.CompiledSandbox
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PureJavaBpfEngineTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `test PureJavaBpfEngine blocks execve`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor
                    .submit<Boolean> {
                        // We allow mmap exec because on cold CI JVM, ProcessBuilder.start()
                        // might trigger JIT compilation which requires executable memory.
                        val policy = Policy
                            .builder()
                            .base(Policy.NO_EXEC)
                            .allowMmapExec()
                            .build()
                        PureJavaBpfEngine.install(policy.definition.compile(Arch.current()))
                        try {
                            ProcessBuilder("echo", "hello").start()
                            false
                        } catch (e: java.io.IOException) {
                            ContainmentViolationDetector.isContainmentViolation(e)
                        }
                    }.get()
            assertTrue(result == true, "execve should have been blocked by PureJavaBpfEngine")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test PureJavaBpfEngine isSupported`() {
        assertTrue(PureJavaBpfEngine.isSupported, "PureJavaBpfEngine should be supported on Linux")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test PureJavaBpfEngine with large policy`() {
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
            )
        val builder = Policy.builder()
        // Block a reasonable number of safe syscalls to exercise BPF generation
        safeSyscalls.take(20).forEach { builder.block(it) }
        val policy = builder.build()

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    PureJavaBpfEngine.install(policy.definition.compile(Arch.current()))
                }.get()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `test SeccompEngine default implementation`() {
        val engine =
            object : SeccompEngine<EngineState> {
                override val state: EngineState = object : EngineState.Unprivileged {}
                override val isSupported: Boolean = true

                override fun install(policy: CompiledSandbox<*>): SeccompEngine<EngineState.Loaded> {
                    @Suppress("UNCHECKED_CAST")
                    return this as SeccompEngine<EngineState.Loaded>
                }
            }
        // Should throw UnsupportedOperationException as per default impl
        assertFailsWith<UnsupportedOperationException> {
            engine.installOnProcess(Policy.PURE_COMPUTE_UNSAFE.definition.compile(Arch.current()))
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test PureJavaBpfEngine exposes verified state on successful installation`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val state = executor
                .submit<EngineState> {
                // We allow mmap exec because on cold CI JVM, any activity here
                // might trigger JIT compilation which requires executable memory.
                val policy = Policy
                    .builder()
                    .base(Policy.PURE_COMPUTE_UNSAFE)
                    .allowMmapExec()
                    .build()
                PureJavaBpfEngine.install(policy.definition.compile(Arch.current()))
                PureJavaBpfEngine.state
            }.get()
            assertTrue(state is EngineState.Loaded, "Expected EngineState.Loaded, got $state")
        } finally {
            executor.shutdown()
        }
    }
}
