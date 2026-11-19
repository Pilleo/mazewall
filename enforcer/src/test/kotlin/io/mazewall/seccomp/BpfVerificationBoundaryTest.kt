package io.mazewall.seccomp

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BpfVerificationBoundaryTest {
    @Test
    fun `JA destination is checked using k even when fallthrough returns`() {
        for (offset in listOf(1, Int.MAX_VALUE, -1)) {
            assertFailsWith<IllegalArgumentException> {
                BpfStaticVerifier.verify(
                    BpfProgram.unverified(
                        listOf(BpfInstruction.Ja(offset), BpfInstruction.Ret(SeccompAction.ACT_KILL_THREAD)),
                    ),
                )
            }
        }
    }

    @Test
    fun `unreachable invalid jump is rejected like the kernel`() {
        assertFailsWith<IllegalArgumentException> {
            BpfStaticVerifier.verify(
                BpfProgram.unverified(
                    listOf(BpfInstruction.Ret(SeccompAction.ACT_KILL_THREAD), BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, 255, 0, 0), BpfInstruction.Ret(SeccompAction.ACT_KILL_THREAD)),
                ),
            )
        }
    }

    @Test
    fun `verification owns an immutable instruction snapshot`() {
        val input = mutableListOf<BpfInstruction>(BpfInstruction.Ret(SeccompAction.ACT_KILL_THREAD))
        val unverified = BpfProgram.unverified(input)
        input[0] = BpfInstruction.Ret(SeccompAction.ACT_ALLOW)
        val verified = BpfStaticVerifier.verify(unverified)
        assertEquals(0, BpfSimulator.simulate(verified.instructions, 0, Arch.AMD64))
        assertFailsWith<UnsupportedOperationException> {
            (verified.instructions as MutableList)[0] = BpfInstruction.Ret(SeccompAction.ACT_ALLOW)
        }
    }

    @Test
    fun `terminated builder is a snapshot rather than a live alias`() {
        val active = BpfProgram.builder().checkArch(Arch.AARCH64).loadSyscallNr()
        val terminated = active.allow()
        active.loadAbsolute(0)
        val snapshot = terminated.build()
        assertEquals(0x7fff0000, snapshot.instructions.last().k)
        BpfStaticVerifier.verify(terminated.build())
    }

    @Test
    fun `unconditional jumps may cross more than 255 instructions`() {
        val program = BpfProgram.dsl(Arch.AARCH64) {
            val destination = nextLabel("distant")
            jumpUnconditional(destination)
            repeat(256) { loadAbsolute(0) }
            mark(destination)
            allow()
        }
        assertEquals(0x7fff0000, BpfSimulator.simulate(BpfStaticVerifier.verify(program).instructions, 0, Arch.AARCH64))
    }

    @Test
    fun `native policy rejects x32 syscall aliases and legacy x32 range`() {
        val program = BpfFilter.build(Arch.AMD64, Policy.NO_EXEC.definition)
        for (nr in listOf(0x40000000, 0x4000003b, 0x40000208, 512, 521, 547)) {
            assertEquals(Int.MIN_VALUE, BpfSimulator.simulate(program.instructions, nr, Arch.AMD64), "nr=$nr")
        }
        for (nr in listOf(511, 548)) {
            assertEquals(0x7fff0000, BpfSimulator.simulate(program.instructions, nr, Arch.AMD64), "nr=$nr")
        }
    }

    @Test
    fun `DSL cannot substitute another architecture or builder at completion`() {
        val foreign = BpfProgram
            .builder()
            .checkArch(Arch.AARCH64)
            .loadSyscallNr()
            .allow()
        assertFailsWith<IllegalArgumentException> { BpfProgram.dsl(Arch.AMD64) { foreign } }
    }

    @Test
    fun `syscall expectation rejects foreign and stale termination tokens`() {
        val active = BpfProgram.builder().checkArch(Arch.AARCH64).loadSyscallNr()
        val other = BpfProgram.builder().checkArch(Arch.AARCH64).loadSyscallNr()
        val foreignEnd = other.allow()
        assertFailsWith<IllegalArgumentException> { active.expect(1) { foreignEnd } }
        assertFailsWith<IllegalArgumentException> {
            active.expect(2) {
                val stale = deny(13)
                loadAbsolute(0)
                stale
            }
        }
    }

    @Test
    fun `initialization aliases cannot rewrite an active program`() {
        val initial = BpfProgram.builder()
        val checked = initial.checkArch(Arch.AARCH64)
        checked.loadSyscallNr()
        assertFailsWith<IllegalStateException> { initial.checkArch(Arch.AMD64) }
        assertFailsWith<IllegalStateException> { checked.loadSyscallNr() }
    }

    @Test
    fun `syscall expectation cannot accidentally compare an argument word`() {
        val program = BpfProgram.dsl(Arch.AARCH64) {
            loadAbsolute(16)
            expect(172) { deny(13) }
            allow()
        }
        assertEquals(0x0005000d, BpfSimulator.simulate(program.instructions, 172, Arch.AARCH64))
        assertEquals(0x7fff0000, BpfSimulator.simulate(program.instructions, 173, Arch.AARCH64.audit, longArrayOf(172)))
    }

    @Test
    fun `x32 guard follows audit ABI rather than metadata object equality`() {
        val arch = Arch.AMD64.copy(name = "same ABI")
        val program = BpfProgram.dsl(arch) { allow() }
        assertEquals(Int.MIN_VALUE, BpfSimulator.simulate(program.instructions, 0x4000003b, arch))
    }

    @Test
    fun `argument equality checks own their permitted value snapshots`() {
        val longs = mutableListOf(1L)
        val ints = mutableListOf(1)
        val wide = ArgCheck.EqualsAny(longs)
        val narrow = ArgCheck.EqualsAny32(ints)
        longs[0] = 2L
        ints[0] = 2
        assertEquals(listOf(1L), wide.allowedValues)
        assertEquals(listOf(1), narrow.allowedValues)
        assertFailsWith<UnsupportedOperationException> { (wide.allowedValues as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (narrow.allowedValues as MutableList).clear() }
    }

    @Test
    fun `mask checks cannot require bits outside their mask`() {
        assertFailsWith<IllegalArgumentException> { ArgCheck.MaskEquals(1, 2) }
        assertFailsWith<IllegalArgumentException> { ArgCheck.MaskEquals(1, 1).copy(expected = 2) }
    }

    @Test
    fun `safe prctl options remain a JVM floor under explicit denial`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val program = BpfFilter.buildFromActions(
                arch,
                mapOf(arch.prctl to SeccompAction.ACT_ERRNO(13)),
                SeccompAction.ACT_ALLOW,
                BpfFilter.getSyscallInspectionPipeline(),
            )
            for (option in listOf(15L, 16L, 21L, 22L, 38L, 39L)) {
                assertEquals(0x7fff0000, BpfSimulator.simulate(program.instructions, arch.prctl, arch.audit, longArrayOf(option)))
            }
            assertEquals(0x00050001, BpfSimulator.simulate(program.instructions, arch.prctl, arch.audit, longArrayOf(35)))
        }
    }

    @Test
    fun `duplicate syscall inspections cannot silently shadow restrictions`() {
        val inspection = SyscallInspection(1000, SyscallArgument.FIRST, ArgCheck.MaskEquals(1, 1), SeccompAction.ACT_ALLOW, SeccompAction.ACT_ERRNO())
        val builder = BpfProgram.builder().checkArch(Arch.AMD64).loadSyscallNr()
        assertFailsWith<IllegalArgumentException> {
            BpfFilter.emitInspections(builder, listOf(inspection, inspection.copy(ifMatched = SeccompAction.ACT_KILL_PROCESS)), false, mutableSetOf())
        }
    }

    @Test
    fun `argument inspection cannot shadow the special clone3 fallback`() {
        val shadow = object : SyscallInspector {
            override fun getInspections(
                arch: Arch,
                context: InspectionContext,
            ): List<SyscallInspection> =
                listOf(
                SyscallInspection(arch.clone3, SyscallArgument.FIRST, ArgCheck.MaskEquals(0, 0), SeccompAction.ACT_ALLOW, SeccompAction.ACT_ALLOW),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BpfFilter.buildFromActions(
                Arch.AMD64,
                emptyMap(),
                SeccompAction.ACT_ALLOW,
                DefaultSyscallInspectionPipeline(listOf(shadow, Clone3Inspector())),
            )
        }
    }
}
