package io.mazewall.profiler

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.MockNativeNetworking
import io.mazewall.MockPlatformProvider
import io.mazewall.Platform
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.networking.NativeDescriptorInputStream
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.profiler.engine.RealProfilerInstaller
import io.mazewall.profiler.internal.NativeSocketInputStream
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.seccomp.BpfInstruction
import io.mazewall.seccomp.BpfProgram
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilerRuntimeTest {
    @Test
    @Timeout(20)
    fun `close during a published listener handshake prevents later tsync`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = CopyOnWriteArrayList<String>()
        val native = installationEngine(
            calls,
            beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            },
            onPassThrough = { release.countDown() },
        )
        val manager = ProfilerDaemonManager(native, ProfilerTest.MockSocketManager(), ProfilerTest.MockProcessLauncher())
        val service = ProfilerService(ProfilerRuntime({ manager }, RealProfilerInstaller(native, true), { native.process.gettid() }, native))
        val session = service.openSession(processWide = true)
        val callers = Executors.newFixedThreadPool(2)
        val installation = callers.submit { session.installOnCurrentThread() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            callers.submit { service.shutdown() }.get(1, TimeUnit.SECONDS)
            val failure = assertFailsWith<ExecutionException> { installation.get(5, TimeUnit.SECONDS) }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(1, calls.count { it == "syscall" }, "Closing during handshake must cancel the post-handshake TSYNC")
            assertEquals(1, calls.count { it == "close:702" })
        } finally {
            release.countDown()
            callers.shutdown()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            service.shutdown()
        }
    }

    @Test
    @Timeout(20)
    fun `late real installation discards events closes once and does not run tsync`() {
        val calls = CopyOnWriteArrayList<String>()
        val native = installationEngine(calls)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.networking.onConnect = { _, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            LinuxNative.SyscallResult.Success(0L)
        }
        val manager = ProfilerDaemonManager(native, ProfilerTest.MockSocketManager(), ProfilerTest.MockProcessLauncher())
        val runtime = ProfilerRuntime({ manager }, RealProfilerInstaller(native, true), { native.process.gettid() }, native)
        val service = ProfilerService(runtime)
        val session = service.openSession(processWide = true)
        val callers = Executors.newFixedThreadPool(2)
        val installation = callers.submit { session.installOnCurrentThread() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            callers.submit { service.shutdown() }.get(1, TimeUnit.SECONDS)
            release.countDown()
            val failure = assertFailsWith<ExecutionException> { installation.get(5, TimeUnit.SECONDS) }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(1, calls.count { it == "syscall" }, "A cancelled installation must not promote its filter with TSYNC")
            assertTrue(calls.contains("read"), "The late socket must still be drained")
            assertTrue(session.logs.isEmpty(), "Late events must not reach the closed session")
            assertEquals(1, calls.count { it == "close:701" })
            assertEquals(1, calls.count { it == "close:702" })
        } finally {
            release.countDown()
            callers.shutdown()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            service.shutdown()
        }
    }

    @Test
    fun `native installation helpers retain their original method descriptors`() {
        assertNotNull(SupervisorSocketUtils::class.java.getMethod("sendDescriptor", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        assertNotNull(
            SupervisorSeccompNotifInstaller::class.java.getMethod(
                "install",
                String::class.java,
                BpfProgram::class.java,
                Boolean::class.javaPrimitiveType,
                Function1::class.java,
                Function2::class.java,
                Function0::class.java,
                Function2::class.java,
            ),
        )
    }

    @Test
    @Timeout(20)
    fun `default runtime installs connects transfers and listens through its captured engine`() {
        val firstCalls = CopyOnWriteArrayList<String>()
        val secondCalls = CopyOnWriteArrayList<String>()
        val first = installationEngine(firstCalls)
        val second = installationEngine(secondCalls)
        Platform.setProvider(MockPlatformProvider().apply { mockSeccompUserNotifSupported = true })
        try {
            LinuxNative.setEngine(first)
            val manager = ProfilerDaemonManager(first, ProfilerTest.MockSocketManager(), ProfilerTest.MockProcessLauncher())
            val runtime = ProfilerRuntime.default().copy(
                daemonManager = { manager },
                environment = { ProfileEnvironment("test", EbpfLoad.Denied("test")) },
            )
            val service = ProfilerService(runtime)
            LinuxNative.setEngine(second)
            val result = service.profile { "captured" }
            service.shutdown()
            assertEquals(emptyList(), secondCalls.toList(), "Replacement engine must have no installation or socket authority")
            assertEquals(listOf("/tmp/captured-engine"), result.observations.flatMap { it.paths })
            assertTrue(firstCalls.containsAll(listOf("bpf", "prctl", "syscall", "socket", "connect", "sendmsg", "read", "write", "close:701", "close:702")), firstCalls.toString())
        } finally {
            LinuxNative.resetToDefault()
            Platform.resetToDefault()
        }
    }

    private fun installationEngine(
        calls: MutableList<String>,
        beforeRead: () -> Unit = {},
        onPassThrough: () -> Unit = {},
    ): MockNativeEngine {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(0xAC)
            output.writeInt(1234)
            output.writeInt(6)
            output.writeBytes("OPENAT")
            output.writeInt(0)
            output.writeInt(1)
            val path = "/tmp/captured-engine"
            output.writeInt(path.length)
            output.writeBytes(path)
        }
        val input = ByteArrayInputStream(bytes.toByteArray())
        val drained = CountDownLatch(1)
        val memory = object : MockNativeMemory() {
            context(arena: NativeArena) override fun newSockFProg(filters: List<BpfInstruction>): ManagedSegment {
                calls.add("bpf")
                return super.newSockFProg(filters)
            }

            override fun read(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                calls.add("read")
                if (input.available() > 0) beforeRead()
                val length = minOf(input.available(), count.toInt())
                repeat(length) { buf.writeByte(it.toLong(), input.read().toByte()) }
                if (length == 0) check(drained.await(5, TimeUnit.SECONDS))
                return LinuxNative.SyscallResult.Success(length.toLong())
            }

            override fun write(
                fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                buf: ManagedSegment,
                count: Long,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                calls.add("write")
                if (buf.readByte(0) == 0x54.toByte()) {
                    drained.countDown()
                    onPassThrough()
                }
                return LinuxNative.SyscallResult.Success(count)
            }
        }
        val networking = object : MockNativeNetworking() {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                calls.add("sendmsg")
                return LinuxNative.SyscallResult.Success(1L)
            }
        }.apply {
            onSocket = { _, _, _ ->
                calls.add("socket")
                LinuxNative.SyscallResult.Success(702L)
            }
            onConnect = { _, _, _ ->
                calls.add("connect")
                LinuxNative.SyscallResult.Success(0L)
            }
        }
        return MockNativeEngine(memory = memory, networking = networking).apply {
            onSyscall = { _, _, _, _, _, _, _ ->
                calls.add("syscall")
                LinuxNative.SyscallResult.Success(701L)
            }
            process.onPrctl = {
                calls.add("prctl")
                LinuxNative.SyscallResult.Success(0L)
            }
            fileSystem.onClose = {
                calls.add("close:${it.value}")
                LinuxNative.SyscallResult.Success(0L)
            }
        }
    }

    @Test
    fun `readers with identical descriptors use their injected runtime concurrently`() {
        val barrier = CyclicBarrier(2)
        val readers = Executors.newFixedThreadPool(2)

        fun read(marker: Byte) =
            readers.submit(
            Callable {
            val memory = object : MockNativeMemory() {
                override fun read(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    barrier.await()
                    buf.writeByte(0, marker)
                    return LinuxNative.SyscallResult.Success(1)
                }
            }
            NativeArena.ofConfined().use { arena ->
                NativeSocketInputStream(FileDescriptor.replace<FileDescriptorRole.Generic>(603), arena, memory).read()
            }
        },
        )
        try {
            val first = read(11)
            val second = read(29)
            assertEquals(11, first.get())
            assertEquals(29, second.get())
        } finally {
            readers.shutdownNow()
        }
    }

    @Test
    fun `native reader retains its original constructor descriptor`() {
        assertNotNull(
            NativeDescriptorInputStream::class.java.getConstructor(
            FileDescriptor::class.java,
            NativeArena::class.java,
            String::class.java,
        ),
        )
    }
}
