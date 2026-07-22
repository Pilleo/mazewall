package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.function.Consumer
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BpfBuilderCoverageTest {

    @Test
    fun `test jumpIfEqual with labels`() {
        val builder = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()

        val label1 = builder.nextLabel("test1")
        val label2 = builder.nextLabel("test2")

        builder.jumpIfEqual(10, jt = label1, jf = label2)
        builder.mark(label1)
        val terminated = builder.ret(0x7fff0000)
        // Note: we can't easily mark label2 here because builder has transitioned to Terminated
        // But we can test the build process.
    }

    @Test
    fun `test jumpIfEqual with invalid forward offset`() {
        val uninitialized = BpfProgram.builder()
        val verified = uninitialized.checkArch(Arch.AMD64)
        val loaded = verified.loadSyscallNr()

        val label1 = loaded.nextLabel("test1")
        loaded.jumpIfEqual(10, jt = label1)

        // Add more than 255 instructions between jump and label
        repeat(260) {
            loaded.loadAbsolute(0)
        }

        val terminated = loaded.mark(label1).ret(0x7fff0000)

        assertFailsWith<IllegalArgumentException> {
            terminated.build()
        }
    }

    @Test
    fun `test backward jump throw`() {
        val loaded = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()

        val label1 = loaded.nextLabel("test1")
        loaded.mark(label1)
        loaded.loadAbsolute(0)
        loaded.jumpIfEqual(10, jt = label1)
        val terminated = loaded.ret(0)

        assertFailsWith<IllegalArgumentException> {
            terminated.build()
        }
    }

    @Test
    fun `test unknown label throw`() {
        val loaded = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()

        val label1 = BpfLabel("unknown")
        loaded.jumpIfEqual(10, jt = label1)
        val terminated = loaded.ret(0)

        assertFailsWith<IllegalArgumentException> {
            terminated.build()
        }
    }

    @Test
    fun `test NrLoaded helper methods`() {
        BpfProgram.dsl(Arch.AMD64) {
            expect(1) {
                allow()
            }
            expect(2, Consumer { it.deny(1) })
            expect(Syscall.OPEN, Arch.AMD64) {
                killThread()
            }
            expect(Syscall.CLOSE, Arch.AMD64, Consumer { it.notifyUser() })

            jumpIfSet(1, null, null)
            and(1)
            allow()
        }
    }

    @Test
    fun `test dsl Function overload`() {
        BpfProgram.dsl(Arch.AMD64, object : Function<BpfBuilder<BpfState.NrLoaded>, BpfBuilder<BpfState.Terminated>> {
            override fun apply(t: BpfBuilder<BpfState.NrLoaded>): BpfBuilder<BpfState.Terminated> {
                return t.allow()
            }
        })
    }
}
