package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@NeedsFreshJvm
class SupervisorAcceptIntegrationTest : BaseIntegrationTest() {
    companion object {
        @org.junit.jupiter.api.AfterAll @JvmStatic
        fun tearDownAll() {
            io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
                .getInstance()
                .stop()
        }
    }

    @Test @EnabledIfLinuxAndSupported
    fun `supervised addressless accept is denied when no identity-bound authority is available`() = exercise(peerMemory = false)

    @Test @EnabledIfLinuxAndSupported
    fun `supervised peer address output is denied when no identity-bound authority is available`() = exercise(peerMemory = true)

    private fun exercise(peerMemory: Boolean) {
        val authorized = AtomicInteger()
        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.ACCEPT to { _, _, stack -> authorize(stack) },
                Syscall.ACCEPT4 to { _, _, stack -> authorize(stack) },
            )

            private fun authorize(stack: List<StackTraceElement>): Boolean = stack.any { it.className.contains("LegitContext") }.also { if (it) authorized.incrementAndGet() }
        }
        val policy = Policy
            .builder()
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .allowJvmClasspath()
            .block(Syscall.ACCEPT, Syscall.ACCEPT4)
            .build()
        val raw = Executors.newSingleThreadExecutor()
        val contained = ContainedExecutors.wrap(raw, policy, scopingPolicy)
        try {
            listeningSocket().use { listener ->
                Socket("127.0.0.1", port(listener)).use { client ->
                    val future = contained.submit<LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>> {
                        class LegitContext {
                            fun run() =
                                NativeArena.ofConfined().use { arena ->
                                val address = if (peerMemory) arena.allocate(128L) else ManagedSegment.NULL
                                val length = if (peerMemory) arena.allocate(4L).also { it.writeInt(0, 128) } else ManagedSegment.NULL
                                LinuxNative.networking.accept4(listener.handle, address, length, 524288)
                            }
                        }
                        LegitContext().run()
                    }
                    val result = future.get(20, TimeUnit.SECONDS)
                    val denied = assertInstanceOf(SupervisorDescriptorResult.Denied::class.java, SupervisorDescriptorResult.from(result, LinuxNative))
                    assertEquals(1, denied.errno)
                    assertTrue(authorized.get() > 0, "JVM policy must authorize the request before helper execution")
                }
            }
        } finally {
            raw.shutdown()
            assertTrue(raw.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun listeningSocket(): SupervisorOwnedFd {
        val listener = assertInstanceOf(SupervisorDescriptorResult.Opened::class.java, SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, 1 or 524288, 0), LinuxNative)).descriptor
        NativeArena.ofConfined().use { arena ->
            val address = arena.allocate(16L)
            ManagedSegment.copy(byteArrayOf(2, 0, 0, 0, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), 0, address, 0, 16)
            LinuxNative.networking.bind(listener.handle, address, 16).getOrThrow("bind")
            LinuxNative.networking.listen(listener.handle, 1).getOrThrow("listen")
        }
        return listener
    }

    private fun port(listener: SupervisorOwnedFd): Int =
        NativeArena.ofConfined().use { arena ->
        val address = arena.allocate(16L)
        val length = arena.allocate(4L).also { it.writeInt(0, 16) }
        LinuxNative.raw.syscall(Arch.current().getsockname.toLong(), NativeArg.FdArg(listener.handle), NativeArg.MemoryArg(address), NativeArg.MemoryArg(length)).getOrThrow("getsockname")
        ((address.readByte(2).toInt() and 255) shl 8) or (address.readByte(3).toInt() and 255)
    }
}
