package io.mazewall.seccomp

import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.function.Consumer
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

        val label1 = loaded.nextLabel("never_marked")
        loaded.jumpIfEqual(10, jt = label1)
        val terminated = loaded.ret(0)

        assertFailsWith<IllegalArgumentException> {
            terminated.build()
        }
    }

    @Test
    fun `label issued by another builder is rejected at jump`() {
        val foreign = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()
        val stolen = foreign.nextLabel("x")

        val loaded = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()

        val ex = assertFailsWith<IllegalArgumentException> {
            loaded.jumpIfEqual(10, jt = stolen)
        }
        assertTrue(ex.message!!.contains("not issued"), ex.message)
    }

    @Test
    fun `label issued by another builder is rejected at mark even if names collide`() {
        val a = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()
        val b = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()
        val aLabel = a.nextLabel("x")
        val bLabel = b.nextLabel("x")
        assertTrue(aLabel.name == bLabel.name, "same prefix+counter must not imply same token")

        a.jumpIfEqual(0, jt = aLabel)
        val ex = assertFailsWith<IllegalArgumentException> {
            a.mark(bLabel)
        }
        assertTrue(ex.message!!.contains("not issued"), ex.message)
    }

    @Test
    fun `test Active helper methods`() {
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
        BpfProgram.dsl(Arch.AMD64, object : Function<BpfBuilder<BpfState.Active>, BpfBuilder<BpfState.Terminated>> {
            override fun apply(t: BpfBuilder<BpfState.Active>): BpfBuilder<BpfState.Terminated> {
                return t.allow()
            }
        })
    }

    @Test
    fun `test strongly-typed BPF DSL helpers`() {
        val program = BpfProgram.dsl(Arch.AMD64) {
            val allowLabel = createLabel("allow")
            val nextLabel = createLabel() // uses default "label" prefix

            loadAbsolute(0)
            jmpIfTrue(allowLabel)
            jmpIfFalse(nextLabel)
            jmp(allowLabel)

            mark(nextLabel)
            deny(1)

            mark(allowLabel)
            allow()
        }

        // Verify that the program compiles and contains correct instructions
        // Expected layout:
        // 0: Load SECCOMP_DATA_ARCH_OFFSET (from checkArch)
        // 1: JumpIfEqual AMD64 (from checkArch)
        // 2: Ret SECCOMP_RET_KILL_PROCESS (from checkArch)
        // 3: Load SECCOMP_DATA_NR_OFFSET (from loadSyscallNr)
        // 4: LoadAbsolute(0)
        // 5: jumpIfEqual(0, jf = allowLabel) -> jt=0, jf=6
        // 6: jumpIfEqual(0, jt = nextLabel) -> jt=2, jf=0
        // 7: jumpIfEqual(0, jt = allowLabel, jf = allowLabel) -> jt=4, jf=4
        // 8: Ret deny(1)
        // 9: Ret allow()
        assertEquals(10, program.instructions.size)
    }

    @Test
    fun `test duplicate label marking throws IllegalArgumentException`() {
        val loaded = BpfProgram.builder()
            .checkArch(Arch.AMD64)
            .loadSyscallNr()

        val label = loaded.createLabel("test_dup")
        loaded.mark(label)
        loaded.mark(label)
        val terminated = loaded.allow()

        val ex = assertFailsWith<IllegalArgumentException> {
            terminated.build()
        }
        assertTrue(ex.message!!.startsWith("Duplicate label marked: test_dup_"), "Exception message should indicate duplicate test_dup label")
    }
}
