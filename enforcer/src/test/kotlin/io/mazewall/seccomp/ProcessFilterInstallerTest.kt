package io.mazewall.seccomp

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.PolicyCompilationCache
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.lifecycle.SandboxedPath
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Isolated
class ProcessFilterInstallerTest {
    private lateinit var mockEngine: MockNativeEngine
    private var savedOsName: String? = null

    @BeforeEach
    fun setUp() {
        savedOsName = System.getProperty("os.name")
        System.setProperty("os.name", "Linux")
        mockEngine =
            MockNativeEngine().apply {
                process.onPrctl = { command ->
                    when (command) {
                        is PrctlCommand.GetSeccomp -> LinuxNative.SyscallResult.Success(2L)
                        is PrctlCommand.SetSeccomp ->
                            if (command.mode == -1L) {
                                LinuxNative.SyscallResult.Error(NativeConstants.EINVAL, -1L)
                            } else {
                                LinuxNative.SyscallResult.Success(0L)
                            }
                        else -> LinuxNative.SyscallResult.Success(0L)
                    }
                }
                onSyscall = { _, _, _, arg3, _, _, _ ->
                    if (arg3 is NativeArg.NullArg) {
                        LinuxNative.SyscallResult.Error(NativeConstants.EFAULT, -1L)
                    } else {
                        LinuxNative.SyscallResult.Success(0L)
                    }
                }
            }
        LinuxNative.setEngine(mockEngine)
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        PolicyCompilationCache.clear()
    }

    @AfterEach
    fun tearDown() {
        if (savedOsName != null) {
            System.setProperty("os.name", savedOsName)
        } else {
            System.clearProperty("os.name")
        }
        LinuxNative.resetToDefault()
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        PolicyCompilationCache.clear()
    }

    @Test
    fun `preset definitions match expected blocked syscall sets and mmap settings`() {
        assertEquals(
            setOf(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.FORK, Syscall.VFORK),
            ProcessFilterInstaller.ELASTICSEARCH_BLOCKED_SYSCALLS,
        )
        assertEquals(
            setOf(
                Syscall.EXECVE,
                Syscall.EXECVEAT,
                Syscall.FORK,
                Syscall.VFORK,
                Syscall.PTRACE,
                Syscall.PROCESS_VM_READV,
                Syscall.PROCESS_VM_WRITEV,
                Syscall.PIDFD_GETFD,
            ),
            ProcessFilterInstaller.OPENSEARCH_BLOCKED_SYSCALLS,
        )
        assertEquals(
            setOf(
                Syscall.EXECVE,
                Syscall.EXECVEAT,
                Syscall.MEMFD_CREATE,
                Syscall.IO_URING_SETUP,
                Syscall.IO_URING_ENTER,
            ),
            ProcessFilterInstaller.DENY_PROCESS_CREATION_BLOCKED_SYSCALLS,
        )
        assertEquals(
            ProcessFilterInstaller.OPENSEARCH_BLOCKED_SYSCALLS + ProcessFilterInstaller.DENY_PROCESS_CREATION_BLOCKED_SYSCALLS,
            ProcessFilterInstaller.MAZEWALL_STRICT_BLOCKED_SYSCALLS,
        )

        val deny = ProcessFilterInstaller.denyProcessCreationDefinition(allowExecutableMappings = true)
        assertTrue(deny.allowMmapExec)
        assertEquals(SeccompAction.ACT_ALLOW, deny.defaultAction)
        assertEquals(ProcessFilterInstaller.DENY_PROCESS_CREATION_BLOCKED_SYSCALLS, deny.syscallActions.keys)

        val custom = ProcessFilterInstaller.policyDefinition(ProcessFilterInstaller.ELASTICSEARCH_BLOCKED_SYSCALLS, allowExecutableMappings = false)
        assertFalse(custom.allowMmapExec)
        assertEquals(ProcessFilterInstaller.ELASTICSEARCH_BLOCKED_SYSCALLS, custom.syscallActions.keys)
    }

    @Test
    fun `cmdline helpers parse NUL tokens and detect incompatible fatal-error flags before app boundary`() {
        assertEquals(emptyList(), ProcessFilterInstaller.parseNulSeparatedTokens(ByteArray(0)))
        assertEquals(listOf("java", "-Xmx1g"), ProcessFilterInstaller.parseNulSeparatedTokens("java\u0000-Xmx1g\u0000".toByteArray()))
        assertEquals(listOf("java", "-Xmx1g", "Main"), ProcessFilterInstaller.parseNulSeparatedTokens("java\u0000-Xmx1g\u0000Main".toByteArray()))
        assertEquals(emptyList(), ProcessFilterInstaller.extractJvmOptionsFromCmdline(listOf("java")))

        val withAppArgs =
            listOf(
                "/usr/bin/java",
                "-cp",
                "app.jar",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "-Xmx512m",
                "com.example.App",
                "-XX:OnError=echo",
                "-XX:OnOutOfMemoryError=/bin/true",
            )
        assertEquals(listOf("-cp", "--add-opens", "-Xmx512m"), ProcessFilterInstaller.extractJvmOptionsFromCmdline(withAppArgs))
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(withAppArgs))

        val stopFlags = listOf("java", "-XX:+UseG1GC", "--", "-XX:OnError=echo")
        assertEquals(listOf("-XX:+UseG1GC"), ProcessFilterInstaller.extractJvmOptionsFromCmdline(stopFlags))
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(stopFlags))

        assertEquals(
            emptyList(),
            ProcessFilterInstaller.findIncompatibleJvmFatalFlags(listOf("java", "-jar", "svc.jar", "-XX:OnOutOfMemoryError=/bin/true")),
        )
        assertEquals(
            emptyList(),
            ProcessFilterInstaller.findIncompatibleJvmFatalFlags(listOf("java", "--module=mod/Main", "-XX:OnError=echo")),
        )
        assertEquals(
            emptyList(),
            ProcessFilterInstaller.findIncompatibleJvmFatalFlags(listOf("java", "-m", "mod/Main", "-XX:OnError=echo")),
        )

        val cleared = listOf("java", "-XX:OnError=/bin/false", "-XX:OnError=", "-XX:OnOutOfMemoryError=/bin/false", "-XX:OnOutOfMemoryError=  ", "Main")
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(cleared))

        val active = listOf("java", "-XX:OnError=gdb %p", "-XX:OnOutOfMemoryError=kill -9 %p", "Main")
        assertEquals(
            listOf("-XX:OnError=gdb %p", "-XX:OnOutOfMemoryError=kill -9 %p"),
            ProcessFilterInstaller.findIncompatibleJvmFatalFlags(active),
        )
    }

    @Test
    fun `install handles idempotency tightening irreversible relaxation and failure preconditions`() {
        val hotspot = ProcessFilterInstaller.denyProcessCreationDefinition(allowExecutableMappings = true)
        val first = ProcessFilterInstaller.install(hotspot, mockEngine, cmdlineTokens = listOf("java", "Main"))
        assertEquals(1, first.filterDepth)
        assertEquals(Arch.current(), first.arch)

        val repeat = ProcessFilterInstaller.install(hotspot, mockEngine, cmdlineTokens = listOf("java", "Main"))
        assertEquals(1, repeat.filterDepth)

        val strict = ProcessFilterInstaller.denyProcessCreationDefinition(allowExecutableMappings = false)
        val tightened = ProcessFilterInstaller.install(strict, mockEngine, cmdlineTokens = listOf("java", "Main"))
        assertEquals(2, tightened.filterDepth)

        val relaxFailure =
            assertFailsWith<IllegalStateException> {
                ProcessFilterInstaller.install(hotspot, mockEngine, cmdlineTokens = listOf("java", "Main"))
            }
        assertTrue(relaxFailure.message.orEmpty().contains("RuntimeProfile.HOTSPOT_JIT"))
        assertNull(ContainmentStateRegistry.processState.installationFailure)
    }

    @Test
    fun `install validates thread type policy restrictions and fatal flags before quarantine`() {
        val policy = ProcessFilterInstaller.denyProcessCreationDefinition(allowExecutableMappings = true)

        Executors.newVirtualThreadPerTaskExecutor().use { vexec ->
            val future =
                vexec.submit<IllegalStateException> {
                    assertFailsWith<IllegalStateException> {
                        ProcessFilterInstaller.install(policy, mockEngine, cmdlineTokens = listOf("java", "Main"))
                    }
                }
            val vthreadEx = future.get()
            assertTrue(vthreadEx.message.orEmpty().contains("virtual thread"))
        }

        val landlockPolicy =
            PolicyDefinition<PolicyScope.ProcessWideSafe>(
                enforceLandlock = true,
                allowedFsReadPaths = setOf(SandboxedPath.of("/tmp", allowNonExistent = true)),
            )
        assertFailsWith<IllegalArgumentException> {
            ProcessFilterInstaller.install(landlockPolicy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }

        val supervisedPolicy = PolicyDefinition<PolicyScope.ProcessWideSafe>(defaultAction = SeccompAction.ACT_NOTIFY)
        assertFailsWith<IllegalArgumentException> {
            ProcessFilterInstaller.install(supervisedPolicy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }

        val cetPolicy = PolicyDefinition<PolicyScope.ProcessWideSafe>(lockIntelCet = true)
        assertFailsWith<IllegalStateException> {
            ProcessFilterInstaller.install(cetPolicy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }

        val oomFlagFailure =
            assertFailsWith<IllegalStateException> {
                ProcessFilterInstaller.install(policy, mockEngine, cmdlineTokens = listOf("java", "-XX:OnOutOfMemoryError=/bin/true", "Main"))
            }
        assertTrue(oomFlagFailure.message.orEmpty().contains("-XX:+ExitOnOutOfMemoryError"))
        assertNull(ContainmentStateRegistry.processState.installationFailure)

        val allowAll = ProcessFilterInstaller.policyDefinition(emptySet(), allowExecutableMappings = true)
        assertEquals(1, ProcessFilterInstaller.install(allowAll, mockEngine, cmdlineTokens = listOf("java", "-XX:OnError=echo", "Main")).filterDepth)
    }

    @Test
    fun `install quarantines on TSYNC error and rethrows fatal Error`() {
        val policy = ProcessFilterInstaller.denyProcessCreationDefinition(allowExecutableMappings = true)
        mockEngine.onSyscall = { _, _, _, _, _, _, _ -> LinuxNative.SyscallResult.Error(NativeConstants.EINVAL, -1L) }

        assertFailsWith<UnsupportedKernelFeatureException> {
            ProcessFilterInstaller.install(policy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }
        assertNotNull(ContainmentStateRegistry.processState.installationFailure)

        assertFailsWith<UnsupportedKernelFeatureException> {
            ProcessFilterInstaller.install(policy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }

        ContainmentStateRegistry.processState = ContainerState()
        mockEngine.onSyscall = { _, _, _, _, _, _, _ -> throw AssertionError("fatal native boom") }
        assertFailsWith<AssertionError> {
            ProcessFilterInstaller.install(policy, mockEngine, cmdlineTokens = listOf("java", "Main"))
        }
        assertNotNull(ContainmentStateRegistry.processState.installationFailure)
    }
}
