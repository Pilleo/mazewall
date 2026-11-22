package io.mazewall.lockdown

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
import io.mazewall.lockdown.internal.platform.syscall.NativeArg
import io.mazewall.lockdown.internal.platform.syscall.PrctlCommand
import io.mazewall.lockdown.internal.seccomp.BpfInstruction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MazewallAgentTest {
    @AfterEach
    fun tearDown() {
        LinuxNative.resetToDefault()
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `parsePolicy resolves defaults bare preset directives and ordered mutations`() {
        assertEquals(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT), MazewallAgent.parsePolicy(null))
        assertEquals(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT), MazewallAgent.parsePolicy("   "))

        assertEquals(ProcessPolicy.elasticsearch(), MazewallAgent.parsePolicy("elasticsearch"))
        assertEquals(ProcessPolicy.openSearch(), MazewallAgent.parsePolicy("OPENSEARCH"))
        assertEquals(
            ProcessPolicy.fromPreset(LockdownPreset.MAZEWALL_STRICT),
            MazewallAgent.parsePolicy("mazewall-strict"),
        )

        val customized =
            MazewallAgent.parsePolicy(
                "preset=opensearch,runtime=native-image,block=memfd_create+bpf,unblock=ptrace:memfd-create",
            )
        assertEquals(RuntimeProfile.NATIVE_IMAGE, customized.runtimeProfile())
        assertTrue(LockdownSyscall.BPF in customized.blockedSyscalls())
        assertFalse(LockdownSyscall.PTRACE in customized.blockedSyscalls())
        assertFalse(LockdownSyscall.MEMFD_CREATE in customized.blockedSyscalls())
        assertTrue(LockdownSyscall.EXECVE in customized.blockedSyscalls())

        val aliasProfile = MazewallAgent.parsePolicy("profile=native,preset=elasticsearch")
        assertEquals(ProcessPolicy.elasticsearch(RuntimeProfile.NATIVE_IMAGE), aliasProfile)
    }

    @Test
    fun `parsePolicy rejects malformed duplicate and unknown agent arguments fail-closed`() {
        val invalidArgs =
            listOf(
                "preset=opensearch,",
                ",preset=opensearch",
                "preset=opensearch,unrecognized",
                "preset=",
                "=opensearch",
                "preset=unknown_preset",
                "preset=opensearch,preset=elasticsearch",
                "runtime=hotspot,profile=native",
                "runtime=wasm",
                "block=",
                "block=execve+",
                "block=nonexistent_syscall",
                "foo=bar",
            )
        invalidArgs.forEach { arg ->
            assertFailsWith<MazewallInstallationException>("Expected failure for agentArgs='$arg'") {
                MazewallAgent.parsePolicy(arg)
            }
        }
    }

    @Test
    fun `premain installs parsed policy onto current process`() {
        val mockEngine = AgentMockEngine()
        LinuxNative.setEngine(mockEngine)
        ContainmentStateRegistry.processState = ContainerState()

        MazewallAgent.premain("preset=elasticsearch,block=memfd_create")

        assertEquals(1, ContainmentStateRegistry.processState.filterDepth)
        assertTrue(ContainmentStateRegistry.processState.allowsMmapExec)
    }

    private class AgentMockEngine :
        NativeEngine,
        RawSyscallOperations,
        NativeProcess,
        NativeMemory,
        NativeFileSystem,
        NativeNetworking {
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
        ): SyscallResult<Long, SyscallHandledState.Unhandled> =
            if (a3 is NativeArg.NullArg) {
                SyscallResult.Error(NativeConstants.EFAULT, -1L)
            } else {
                SyscallResult.Success(0L)
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
            when (command) {
                is PrctlCommand.GetSeccomp -> SyscallResult.Success(2L)
                is PrctlCommand.SetSeccomp ->
                    if (command.mode == -1L) {
                        SyscallResult.Error(NativeConstants.EINVAL, -1L)
                    } else {
                        SyscallResult.Success(0L)
                    }
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
