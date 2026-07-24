package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockNativeMemory
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BpfLimitTest {
    @Test
    fun `BpfFilter buildFromActions throws exception when instructions exceed limit`() {
        val arch = Arch.AMD64
        // Emitting over 4096 syscalls will exceed the 4096 limit.
        val actions = (1000..5100).associateWith { SeccompAction.ACT_ERRNO }
        assertFailsWith<IllegalArgumentException> {
            BpfFilter.buildFromActions(
                arch,
                actions,
                SeccompAction.ACT_ALLOW,
                DefaultSyscallInspectionPipeline(emptyList())
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
