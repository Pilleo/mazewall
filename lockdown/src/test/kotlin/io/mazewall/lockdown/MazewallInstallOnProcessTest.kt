package io.mazewall.lockdown

import io.mazewall.lockdown.internal.enforcer.api.PolicyCompilationCache
import io.mazewall.lockdown.internal.enforcer.api.UnsupportedKernelFeatureException
import io.mazewall.lockdown.internal.enforcer.diagnostics.DiagnosticEventListener
import io.mazewall.lockdown.internal.enforcer.diagnostics.MazewallEvents
import io.mazewall.lockdown.internal.enforcer.state.ContainerState
import io.mazewall.lockdown.internal.enforcer.state.ContainmentStateRegistry
import io.mazewall.lockdown.internal.ffi.NativeConstants
import io.mazewall.lockdown.internal.ffi.memory.ManagedSegment
import io.mazewall.lockdown.internal.ffi.memory.NativeArena
import io.mazewall.lockdown.internal.platform.fd.FdOwnership
import io.mazewall.lockdown.internal.platform.fd.FdState
import io.mazewall.lockdown.internal.platform.fd.FileDescriptor
import io.mazewall.lockdown.internal.platform.lifecycle.MmapFlags
import io.mazewall.lockdown.internal.platform.lifecycle.MmapProt
import io.mazewall.lockdown.internal.platform.lifecycle.OpenFlags
import io.mazewall.lockdown.internal.platform.lifecycle.Pid
import io.mazewall.lockdown.internal.platform.lifecycle.Tid
import io.mazewall.lockdown.internal.platform.sys.LinuxNative
import io.mazewall.lockdown.internal.platform.sys.LinuxNative.SyscallHandledState
import io.mazewall.lockdown.internal.platform.sys.LinuxNative.SyscallResult
import io.mazewall.lockdown.internal.platform.sys.NativeEngine
import io.mazewall.lockdown.internal.platform.sys.NativeFileSystem
import io.mazewall.lockdown.internal.platform.sys.NativeMemory
import io.mazewall.lockdown.internal.platform.sys.NativeNetworking
import io.mazewall.lockdown.internal.platform.sys.NativeProcess
import io.mazewall.lockdown.internal.platform.sys.RawSyscallOperations
import io.mazewall.lockdown.internal.platform.syscall.Arch
import io.mazewall.lockdown.internal.platform.syscall.NativeArg
import io.mazewall.lockdown.internal.platform.syscall.PrctlCommand
import io.mazewall.lockdown.internal.seccomp.BpfInstruction
import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MazewallInstallOnProcessTest {
    private lateinit var fakeEngine: FakeNativeEngine

    @BeforeEach
    fun setUp() {
        fakeEngine = FakeNativeEngine()
        LinuxNative.setEngine(fakeEngine)
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        PolicyCompilationCache.clear()
    }

    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
        PolicyCompilationCache.clear()
    }

    @Test
    fun `calling from virtual thread fails with MazewallInstallationException without quarantining process`() {
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val failure =
                executor
                    .submit<MazewallInstallationException> {
                        assertFailsWith<MazewallInstallationException> {
                            Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
                        }
                    }.get()
            assertIs<IllegalStateException>(failure.cause)
            val msg = failure.message.orEmpty()
            assertTrue(msg.contains("platform thread"))
            assertFalse(msg.contains("ManagedRuntime"))
            assertFalse(msg.contains("portal worker"))
        }
        assertNull(ContainmentStateRegistry.processState.installationFailure)
        assertEquals(0, fakeEngine.syscallCount.get())

        val installation = Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
        assertEquals(1, installation.filterDepth)
    }

    @Test
    fun `unsupported kernel TSYNC failure wraps exception and quarantines process`() {
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ ->
            SyscallResult.Error(NativeConstants.EINVAL, -1L)
        }
        val firstFailure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            }
        assertIs<UnsupportedKernelFeatureException>(firstFailure.cause)
        assertNotNull(ContainmentStateRegistry.processState.installationFailure)

        val callsBeforeRetry = fakeEngine.syscallCount.get()
        val quarantinedFailure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            }
        assertIs<UnsupportedKernelFeatureException>(quarantinedFailure.cause)
        assertEquals(callsBeforeRetry, fakeEngine.syscallCount.get())
    }

    @Test
    fun `non-Linux host fails closed and quarantines process`() {
        val originalOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Mac OS X")
            val failure =
                assertFailsWith<MazewallInstallationException> {
                    Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE))
                }
            assertIs<UnsupportedOperationException>(failure.cause)
            assertNotNull(ContainmentStateRegistry.processState.installationFailure)
            assertEquals(0, fakeEngine.syscallCount.get())
        } finally {
            if (originalOs != null) {
                System.setProperty("os.name", originalOs)
            }
        }
    }

    @Test
    fun `seccomp syscall error or offending sibling tid fails closed and quarantines process`() {
        fakeEngine.onSyscall = { _, _, _, arg3, _, _, _ ->
            if (arg3 is NativeArg.NullArg) {
                SyscallResult.Error(NativeConstants.EFAULT, -1L)
            } else {
                SyscallResult.Success(4242L)
            }
        }
        val tidFailure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            }
        assertIs<IllegalStateException>(tidFailure.cause)
        assertTrue(
            tidFailure.cause
            ?.message
            .orEmpty()
            .contains("tid=4242"),
        )
        assertNotNull(ContainmentStateRegistry.processState.installationFailure)
    }

    @Test
    fun `installOnProcess is idempotent and tightens HOTSPOT_JIT to NATIVE_IMAGE without false prctl warning`() {
        val hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val first = Mazewall.installOnProcess(hotspot)
        assertEquals(1, first.filterDepth)
        assertEquals(Arch.current().name.lowercase(), first.architecture)
        assertTrue(ContainmentStateRegistry.processState.allowsMmapExec)

        val repeated = Mazewall.installOnProcess(hotspot)
        assertEquals(1, repeated.filterDepth)

        val nativeImage = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)
        assertFailsWith<MazewallInstallationException> {
            Mazewall.installOnProcess(hotspot, nativeImage)
        }

        val events = mutableListOf<MazewallEvents.Event>()
        val listener = DiagnosticEventListener { events.add(it) }
        MazewallEvents.register(listener)
        try {
            val tightened = Mazewall.installOnProcess(nativeImage)
            assertEquals(2, tightened.filterDepth)
            assertFalse(ContainmentStateRegistry.processState.allowsMmapExec)
            val prctlFallbacks =
                events.filterIsInstance<MazewallEvents.FallbackEngaged>().filter {
                    it.behaviorName == "ALLOW_UNSAFE_PRCTL"
                }
            assertTrue(prctlFallbacks.isEmpty(), "Delta tightening must not emit ALLOW_UNSAFE_PRCTL warning")
        } finally {
            MazewallEvents.unregister(listener)
        }
    }

    @Test
    fun `relaxing NATIVE_IMAGE back to HOTSPOT_JIT fails closed without corrupting healthy process state`() {
        val nativeImage = ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE)
        val hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val installed = Mazewall.installOnProcess(nativeImage)
        assertEquals(1, installed.filterDepth)

        val failure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertIs<IllegalStateException>(failure.cause)
        assertNull(ContainmentStateRegistry.processState.installationFailure)
        assertEquals(1, ContainmentStateRegistry.processState.filterDepth)

        val stillHealthy = Mazewall.installOnProcess(nativeImage)
        assertEquals(1, stillHealthy.filterDepth)
    }

    @Test
    fun `installOnProcess combines matching profiles and wraps non-fatal install failures`() {
        val hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        val combined = Mazewall.installOnProcess(hotspot, hotspot)
        assertEquals(1, combined.filterDepth)

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw IllegalStateException() }
        val generic =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertEquals("Failed to install process-wide seccomp lockdown filter", generic.message)
        assertIs<IllegalStateException>(generic.cause)

        ContainmentStateRegistry.processState = ContainerState()
        val directFailure = MazewallInstallationException("already wrapped")
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw directFailure }
        val rethrown =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertTrue(rethrown === directFailure)
    }

    @Test
    fun `installOnProcess wraps native initialization and linkage errors cleanly`() {
        val hotspot = ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ ->
            throw ExceptionInInitializerError(IllegalCallerException("native access denied"))
        }
        val restricted =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertTrue(restricted.message.orEmpty().contains("--enable-native-access=io.mazewall.lockdown"))
        assertTrue(restricted.message.orEmpty().contains("ALL-UNNAMED"))

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ ->
            throw ExceptionInInitializerError(IllegalStateException("custom init boom"))
        }
        val customCause =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertEquals("custom init boom", customCause.message)

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw ExceptionInInitializerError() }
        val nullCause =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(hotspot)
            }
        assertEquals("Failed to initialize native FFM seccomp engine", nullCause.message)

        val nativeClasses =
            listOf(
                "io/mazewall/lockdown/internal/platform/sys/LinuxNative",
                "io/mazewall/lockdown/internal/ffi/internal/RealNativeEngine",
                "io.mazewall.lockdown.internal.platform.sys.LinuxNative",
                "io.mazewall.lockdown.internal.ffi.internal.RealNativeEngine",
            )
        nativeClasses.forEach { missingClass ->
            ContainmentStateRegistry.processState = ContainerState()
            fakeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw NoClassDefFoundError(missingClass) }
            val wrapped =
                assertFailsWith<MazewallInstallationException> {
                    Mazewall.installOnProcess(hotspot)
                }
            assertTrue(wrapped.message.orEmpty().contains("--enable-native-access"))
            assertIs<NoClassDefFoundError>(wrapped.cause)
        }

        listOf("com/example/Other", null).forEach { unrelated ->
            ContainmentStateRegistry.processState = ContainerState()
            fakeEngine.onSyscall = { _, _, _, _, _, _, _ -> throw NoClassDefFoundError(unrelated) }
            val err =
                assertFailsWith<NoClassDefFoundError> {
                    Mazewall.installOnProcess(hotspot)
                }
            assertEquals(unrelated, err.message)
        }
    }

    @Test
    fun `excluded experimental and Landlock modules are absent from lockdown classpath`() {
        val excluded =
            listOf(
                "io.mazewall.landlock.Landlock",
                "io.mazewall.platform.seccomp.SupervisedKind",
                "io.mazewall.enforcer.api.Platform",
                "io.mazewall.enforcer.api.FileSystemPolicy",
                "io.mazewall.enforcer.api.WorkerPolicyBuilder",
                "io.mazewall.enforcer.api.ContainedExecutors",
                "io.mazewall.ffi.internal.LiveStackBootstrap",
                "io.mazewall.ffi.internal.ProductionDeploymentVerifier",
            )
        excluded.forEach { className ->
            assertFailsWith<ClassNotFoundException>("Excluded class must not be packaged in :lockdown: $className") {
                Class.forName(className, false, ProcessPolicy::class.java.classLoader)
            }
        }
    }

    @Test
    fun `PR_SET_NO_NEW_PRIVS denial succeeds when PR_GET_NO_NEW_PRIVS confirms already enabled`() {
        fun handler(onGetNoNewPrivs: () -> SyscallResult<Long, SyscallHandledState.Unhandled>): (PrctlCommand) -> SyscallResult<Long, SyscallHandledState.Unhandled> =
            { command ->
                when (command) {
                    is PrctlCommand.SetNoNewPrivs -> SyscallResult.Error(NativeConstants.EPERM, -1L)
                    is PrctlCommand.GetNoNewPrivs -> onGetNoNewPrivs()
                    is PrctlCommand.GetSeccomp -> SyscallResult.Success(2L)
                    is PrctlCommand.SetSeccomp ->
                        if (command.mode == -1L) {
                            SyscallResult.Error(NativeConstants.EINVAL, -1L)
                        } else {
                            SyscallResult.Success(0L)
                        }
                    else -> SyscallResult.Success(0L)
                }
            }

        var getAttempts = 0
        fakeEngine.onPrctl =
            handler {
                getAttempts++
                if (getAttempts == 1) {
                    SyscallResult.Error(NativeConstants.EINTR, -1L)
                } else {
                    SyscallResult.Success(1L)
                }
            }

        val installation = Mazewall.installOnProcess(ProcessPolicy.elasticsearch(RuntimeProfile.HOTSPOT_JIT))
        assertEquals(1, installation.filterDepth)
        assertEquals(2, getAttempts)

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onPrctl = handler { SyscallResult.Success(0L) }
        val zeroFailure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.elasticsearch(RuntimeProfile.HOTSPOT_JIT))
            }
        assertTrue(zeroFailure.message.orEmpty().contains("PR_GET_NO_NEW_PRIVS=0"))

        ContainmentStateRegistry.processState = ContainerState()
        fakeEngine.onPrctl = handler { SyscallResult.Error(NativeConstants.EACCES, -1L) }
        val errorFailure =
            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.elasticsearch(RuntimeProfile.HOTSPOT_JIT))
            }
        assertTrue(errorFailure.message.orEmpty().contains("PR_GET_NO_NEW_PRIVS failed with errno=13"))
    }

    @Test
    fun `extractJvmOptionsFromCmdline and findIncompatibleJvmFatalFlags respect launcher boundaries and overrides`() {
        assertEquals(listOf("java", "-Xmx1g"), ProcessFilterInstaller.parseNulSeparatedTokens("java\u0000-Xmx1g\u0000".toByteArray()))
        assertEquals(listOf("java", "Main", ""), ProcessFilterInstaller.parseNulSeparatedTokens("java\u0000Main\u0000\u0000".toByteArray()))
        assertEquals(emptyList(), ProcessFilterInstaller.parseNulSeparatedTokens(ByteArray(0)))

        val appArgsOnly =
            listOf(
                "/usr/bin/java",
                "-Xmx512m",
                "-cp",
                "app.jar",
                "com.example.Main",
                "-XX:OnError=echo app-arg",
                "-XX:OnOutOfMemoryError=/bin/true",
            )
        assertEquals(listOf("-Xmx512m", "-cp"), ProcessFilterInstaller.extractJvmOptionsFromCmdline(appArgsOnly))
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(appArgsOnly))

        val jarArgsOnly =
            listOf(
                "java",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
                "-jar",
                "service.jar",
                "-XX:OnError=echo",
            )
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(jarArgsOnly))

        val moduleArgsOnly =
            listOf(
                "java",
                "-p",
                "mods",
                "--module=app.mod/app.Main",
                "-XX:OnOutOfMemoryError=kill -9 %p",
            )
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(moduleArgsOnly))

        val clearedFlags =
            listOf(
                "java",
                "-XX:OnOutOfMemoryError=/bin/false",
                "-XX:OnOutOfMemoryError=",
                "-XX:OnError=/bin/false",
                "-XX:OnError=   ",
                "com.example.Main",
            )
        assertEquals(emptyList(), ProcessFilterInstaller.findIncompatibleJvmFatalFlags(clearedFlags))

        val activeFlags =
            listOf(
                "java",
                "-XX:+UseG1GC",
                "-XX:OnError=gdb -p %p",
                "-XX:OnOutOfMemoryError=kill -9 %p",
                "-m",
                "app/Main",
            )
        assertEquals(
            listOf("-XX:OnError=gdb -p %p", "-XX:OnOutOfMemoryError=kill -9 %p"),
            ProcessFilterInstaller.findIncompatibleJvmFatalFlags(activeFlags),
        )
    }

    @Test
    fun `ProcessFilterInstaller rejects incompatible JVM fatal-error flags before quarantine only when spawning is blocked`() {
        val noExec = ProcessPolicy.elasticsearch(RuntimeProfile.HOTSPOT_JIT).definition
        val cmdlineWithOomHook =
            listOf(
                "java",
                "-XX:OnOutOfMemoryError=/usr/bin/notify.sh",
                "-cp",
                "app.jar",
                "probe.Probe",
            )
        val failure =
            assertFailsWith<IllegalStateException> {
                ProcessFilterInstaller.install(noExec, fakeEngine, cmdlineWithOomHook)
            }
        assertTrue(failure.message.orEmpty().contains("-XX:OnOutOfMemoryError=/usr/bin/notify.sh"))
        assertTrue(failure.message.orEmpty().contains("-XX:+ExitOnOutOfMemoryError"))
        assertTrue(failure.message.orEmpty().contains("-XX:+CrashOnOutOfMemoryError"))
        assertNull(ContainmentStateRegistry.processState.installationFailure)
        assertEquals(0, fakeEngine.syscallCount.get())

        val withoutExecBlocks =
            ProcessPolicy
                .elasticsearch(RuntimeProfile.HOTSPOT_JIT)
                .unblock(
                    LockdownSyscall.EXECVE,
                    LockdownSyscall.EXECVEAT,
                    LockdownSyscall.FORK,
                    LockdownSyscall.VFORK,
                ).definition
        val installed = ProcessFilterInstaller.install(withoutExecBlocks, fakeEngine, cmdlineWithOomHook)
        assertEquals(1, installed.filterDepth)
    }

    private class FakeNativeEngine :
        NativeEngine,
        RawSyscallOperations,
        NativeProcess,
        NativeMemory,
        NativeFileSystem,
        NativeNetworking {
        val syscallCount = AtomicInteger(0)
        var onPrctl: ((PrctlCommand) -> SyscallResult<Long, SyscallHandledState.Unhandled>)? = null

        var onSyscall: (Long, NativeArg, NativeArg, NativeArg, NativeArg, NativeArg, NativeArg) -> SyscallResult<Long, SyscallHandledState.Unhandled> =
            { _, _, _, arg3, _, _, _ ->
                if (arg3 is NativeArg.NullArg) {
                    SyscallResult.Error(NativeConstants.EFAULT, -1L)
                } else {
                    SyscallResult.Success(0L)
                }
            }

        override val fileSystem: NativeFileSystem get() = this
        override val networking: NativeNetworking get() = this
        override val process: NativeProcess get() = this
        override val memory: NativeMemory get() = this
        override val raw: RawSyscallOperations get() = this

        override fun syscall(
            nr: Long,
            a1: NativeArg,
            a2: NativeArg,
            a3: NativeArg,
            a4: NativeArg,
            a5: NativeArg,
            a6: NativeArg,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> {
            syscallCount.incrementAndGet()
            return onSyscall(nr, a1, a2, a3, a4, a5, a6)
        }

        override fun syscall4(
            nr: Long,
            a1: NativeArg,
            a2: NativeArg,
            a3: NativeArg,
            a4: NativeArg,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Success(0L)

        override fun ioctl(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            request: Long,
            arg: ManagedSegment,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun ioctl(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            request: Long,
            arg: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun fcntl(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            cmd: Int,
            arg: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun poll(
            fds: ManagedSegment,
            nfds: Long,
            timeout: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun gettid(): Tid = Tid(1)

        override fun prctl(command: PrctlCommand): SyscallResult<Long, SyscallHandledState.Unhandled> =
            onPrctl?.invoke(command)
                ?: when {
                    command is PrctlCommand.GetSeccomp -> SyscallResult.Success(2L)
                    command is PrctlCommand.SetSeccomp && command.mode == -1L ->
                        SyscallResult.Error(NativeConstants.EINVAL, -1L)
                    else -> SyscallResult.Success(0L)
                }

        override fun pidfdOpen(
            pid: Int,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun pidfdGetFd(
            pidfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            targetFd: Int,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun archPrctl(
            code: Int,
            addr: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun archPrctl(
            code: Int,
            addr: ManagedSegment,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun processVmReadv(
            pid: Pid,
            localIov: ManagedSegment,
            liovcnt: Long,
            remoteIov: ManagedSegment,
            riovcnt: Long,
            flags: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun processVmWritev(
            pid: Pid,
            localIov: ManagedSegment,
            liovcnt: Long,
            remoteIov: ManagedSegment,
            riovcnt: Long,
            flags: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun read(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            buf: ManagedSegment,
            count: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun write(
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            buf: ManagedSegment,
            count: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        context(arena: NativeArena) override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment = ManagedSegment.NULL

        override fun open(
            path: ManagedSegment,
            flags: OpenFlags,
            mode: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun openat(
            dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            path: ManagedSegment,
            flags: OpenFlags,
            mode: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun readlink(
            path: ManagedSegment,
            buf: ManagedSegment,
            bufsiz: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun mmap(
            addr: Long,
            length: Long,
            prot: MmapProt,
            flags: MmapFlags,
            fd: FileDescriptor<*, FdState.Open, FdOwnership>,
            offset: Long,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun socketpair(
            domain: Int,
            type: Int,
            protocol: Int,
            sv: ManagedSegment,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun socket(
            domain: Int,
            type: Int,
            protocol: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun bind(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            addr: ManagedSegment,
            addrlen: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun listen(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            backlog: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun accept(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            addr: ManagedSegment,
            addrlen: ManagedSegment,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun accept4(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            addr: ManagedSegment,
            addrlen: ManagedSegment,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun connect(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            addr: ManagedSegment,
            addrlen: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun sendmsg(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            msg: ManagedSegment,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun recvmsg(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            msg: ManagedSegment,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)

        override fun recv(
            sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
            buf: ManagedSegment,
            len: Long,
            flags: Int,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> = SyscallResult.Error(NativeConstants.ENOSYS, -1L)
    }
}
