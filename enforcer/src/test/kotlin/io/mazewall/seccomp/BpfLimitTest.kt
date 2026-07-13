package io.mazewall.seccomp

import io.mazewall.LinuxNative
import io.mazewall.ffi.NativeConstants
import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BpfLimitTest {
    @Test
    fun `newSockFProg throws exception when instructions exceed limit`() {
        val instructions = List(NativeConstants.BPF_MAXINSNS + 1) {
            BpfInstruction.Ret(0, 0)
        }

        Arena.ofConfined().use { arena ->
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

        Arena.ofConfined().use { arena ->
            with(arena) {
                LinuxNative.memory.newSockFProg(instructions)
            }
        }
    }
}
