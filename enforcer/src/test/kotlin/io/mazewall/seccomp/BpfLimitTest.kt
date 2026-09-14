package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockNativeMemory
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BpfLimitTest {
    @Test
    fun `BpfFilter buildFromActions throws exception when instructions exceed limit`() {
        val arch = Arch.AMD64
        // Emitting over 4096 syscalls will exceed the 4096 limit.
        val actions = (1000..5100).associateWith { SeccompAction.ACT_ERRNO() }
        assertFailsWith<IllegalArgumentException> {
            BpfFilter.buildFromActions(
                arch,
                actions,
                SeccompAction.ACT_ALLOW,
                DefaultSyscallInspectionPipeline(emptyList()),
            )
        }
    }

    @Test
    fun `MockNativeMemory newSockFProg throws exception when instructions exceed limit`() {
        try {
            val mockMemory = MockNativeMemory()
            LinuxNative.setEngine(MockNativeEngine(memory = mockMemory))

            val instructions = List(NativeConstants.BPF_MAXINSNS + 1) {
                BpfInstruction.Ret(0, 0)
            }

            NativeArena.ofConfined().use { arena ->
                assertFailsWith<IllegalArgumentException> {
                    with(arena) {
                        LinuxNative.memory.newSockFProg(instructions)
                    }
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `newSockFProg throws exception when instructions exceed limit`() {
        val instructions = List(NativeConstants.BPF_MAXINSNS + 1) {
            BpfInstruction.Ret(0, 0)
        }

        NativeArena.ofConfined().use { arena ->
            assertFailsWith<IllegalArgumentException> {
                with(arena) {
                    LinuxNative.memory.newSockFProg(instructions)
                }
            }
        }
    }

    @Test
    fun `newSockFProg accepts instructions at exactly the limit`() {
        val instructions = List(NativeConstants.BPF_MAXINSNS) {
            BpfInstruction.Ret(0, 0)
        }

        NativeArena.ofConfined().use { arena ->
            with(arena) {
                LinuxNative.memory.newSockFProg(instructions)
            }
        }
    }
}
