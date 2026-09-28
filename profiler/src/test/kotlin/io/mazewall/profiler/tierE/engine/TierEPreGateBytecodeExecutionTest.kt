package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierEOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Executes the emitted diagnostic prefix, including real relative jumps and helper outcomes. */
class TierEPreGateBytecodeExecutionTest {
    private val options = TierEOptions(benchmarkPhases = true, benchmarkRawSysEnterAudit = true, benchmarkExactAdmissionAudit = true)

    @Test
    fun `failed first argument read cannot let later successful read invent post session admission`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(0L, machine.pre[2], "failed first read cannot produce a validated pre-session descriptor")
        assertEquals(0L, machine.pre[4], "later v3 read must not invent the v4 post-session checkpoint")
        assertEquals(1L, machine.failure(4), "main read failure must remain explicit")
    }

    @Test
    fun `later helper result cannot redirect validated descriptor to a different ID`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, secondReadFd = 222)
        assertEquals(1L, machine.pre[2], "the first decoded ID passed the independent pre-session checkpoint")
        assertEquals(1L, machine.pre[4], "the post-session stage must use that same validated ID")
        assertEquals(0L, machine.other[4], "the second argument read must not redirect post-session evidence")
    }

    @Test
    fun `unarmed and wrong target syscalls cannot increment pre or post descriptor stages`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, armed = false)
        assertEquals(0L, machine.pre[2], "unarmed event")
        assertEquals(0L, machine.pre[4], "unarmed post stage")
        machine.run(syscall = 257, pid = 12346)
        assertEquals(0L, machine.pre[2], "wrong target event")
        assertEquals(0L, machine.pre[4], "wrong target post stage")
    }

    @Test
    fun `prior eligible syscall cannot leak its descriptor into a later non openat`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345)
        machine.run(syscall = 3, pid = 12345)
        assertEquals(1L, machine.pre[2])
        assertEquals(1L, machine.pre[4])
    }

    @Test
    fun `session mismatch retains pre stage without manufacturing post stage`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12346)
        machine.run(syscall = 257, pid = 12345)
        assertEquals(1L, machine.pre[2])
        assertEquals(0L, machine.pre[4])
    }

    @Test
    fun `missing pre gate map lookup cannot admit post stage and records explicit failure`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterProgram(options, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345)
        machine.run(syscall = 257, pid = 12345, missingPreGateEntry = true)
        assertEquals(0L, machine.pre[2])
        assertEquals(0L, machine.pre[4])
        assertEquals(1L, machine.failure(6))
    }

    @Test
    fun `raw program records only validated declared entries across failed helpers and consecutive syscalls`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(earlyTargetCount = true, benchmarkTargetTgid = 12345)
        val machine = DiagnosticPrefix(program, target = 12345, rawProgram = true)
        machine.run(syscall = 257, pid = 12345, firstReadFails = true)
        assertEquals(0L, machine.pre[1])
        assertEquals(0L, machine.pre[3])
        assertEquals(1L, machine.failure(3))
        machine.run(syscall = 257, pid = 12345, secondReadFd = 222)
        assertEquals(1L, machine.pre[1])
        assertEquals(1L, machine.pre[3])
        assertEquals(0L, machine.other[3])
        machine.run(syscall = 3, pid = 12345)
        machine.run(syscall = 257, pid = 12346)
        assertEquals(1L, machine.pre[1])
        assertEquals(1L, machine.pre[3])
    }

    @Test
    fun `raw probe keeps a pre stage on session rejection and excludes failed descriptor lookup`() {
        val program = TierEBpfProgramBuilder.buildRawTracepointSysEnterAuditProgram(earlyTargetCount = true, benchmarkTargetTgid = 12345)
        val rejected = DiagnosticPrefix(program, target = 12346, rawProgram = true)
        rejected.run(syscall = 257, pid = 12345)
        assertEquals(1L, rejected.pre[1])
        assertEquals(0L, rejected.pre[3])
        val noEntry = DiagnosticPrefix(program, target = 12345, rawProgram = true)
        noEntry.run(syscall = 257, pid = 12345, missingPreGateEntry = true)
        assertEquals(0L, noEntry.pre[1])
        assertEquals(0L, noEntry.pre[3])
        assertEquals(1L, noEntry.failure(5))
    }

    private class DiagnosticPrefix(
        private val program: List<TierEbpfEngine.Insn>,
        private val target: Int,
        private val rawProgram: Boolean = false,
    ) {
        val pre = LongArray(5).apply { this[0] = 1 }
        val other = LongArray(5).apply { this[0] = 1 }
        private val earlyCounters = Array(9) { LongArray(1) }
        private val stack = mutableMapOf<Int, Long>()

        fun failure(key: Int): Long = earlyCounters[key][0]

        private sealed interface Value {
            data class Number(
                val value: Long,
            ) : Value

            data class Address(
                val offset: Int,
            ) : Value

            data class Map(
                val index: Int,
            ) : Value

            data class Entry(
                val map: Int,
                val index: Int,
                val value: LongArray,
            ) : Value

            data object Context : Value

            data class PtRegs(
                val offset: Int,
            ) : Value
        }

        fun run(
            syscall: Int,
            pid: Int,
            armed: Boolean = true,
            firstReadFails: Boolean = false,
            secondReadFd: Int = 111,
            missingPreGateEntry: Boolean = false,
        ) {
            earlyCounters[2][0] = if (armed) 1 else 0
            val registers = MutableList<Value>(11) { Value.Number(0) }
            registers[1] = Value.Context
            registers[10] = Value.Address(0)
            var reads = 0
            var pc = 0
            var steps = 0

            fun number(register: Int): Long =
                when (val value = registers[register]) {
                is Value.Number -> value.value
                is Value.Entry -> 1L // non-null map-value pointer compared against zero
                else -> error("register $register is not comparable")
            }
            while (pc in program.indices && ++steps < 1600) {
                val insn = program[pc]
                if (insn.code == 0x7b && insn.off == (-112).toShort()) return // next step enters in-flight attribution
                if (insn.code == 0x18 && insn.imm == 6) return // main in-flight path is outside this diagnostic prefix
                if (rawProgram && insn.code == 0x85 && insn.imm == 8) return // CPU audit follows the raw post-session stage
                var next = pc + 1
                when (insn.code) {
                    0x00 -> Unit
                    0x18 -> {
                        registers[insn.dst] = Value.Map(insn.imm)
                        next++
                    }
                    0xb7 -> registers[insn.dst] = Value.Number(insn.imm.toLong())
                    0xbf -> registers[insn.dst] = registers[insn.src]
                    0x07 -> registers[insn.dst] = when (val value = registers[insn.dst]) {
                        is Value.Address -> value.copy(offset = value.offset + insn.imm)
                        is Value.PtRegs -> value.copy(offset = value.offset + insn.imm)
                        is Value.Number -> value.copy(value = value.value + insn.imm)
                        else -> error("unsupported add at $pc")
                    }
                    0x77 -> registers[insn.dst] = Value.Number(number(insn.dst) ushr insn.imm)
                    0x63, 0x7b -> stack[(registers[insn.dst] as Value.Address).offset + insn.off] = number(insn.src)
                    0x61, 0x79 -> {
                        val value = when (val source = registers[insn.src]) {
                            is Value.Address -> stack[source.offset + insn.off]
                                ?: error("uninitialized stack at pc=$pc offset=${source.offset + insn.off} reads=$reads")
                            is Value.Context -> when (insn.off.toInt()) {
                                0 -> -1L
                                8 -> syscall.toLong()
                                else -> error("context offset at $pc")
                            }
                            is Value.Entry -> when (insn.off.toInt()) {
                                4 -> if (source.map == 3 || source.map == 1) target.toLong() else source.value[0]
                                else -> source.value[insn.off.toInt() / 8]
                            }
                            else -> error("unsupported load at $pc")
                        }
                        registers[insn.dst] = if (insn.code == 0x61) {
                            Value.Number(value and 0xffff_ffffL)
                        } else {
                            if (sourceIsPtRegs(registers[insn.src], insn.off.toInt())) {
                                Value.PtRegs(0)
                            } else {
                                Value.Number(value)
                            }
                        }
                    }
                    0x15, 0x55, 0x35 -> {
                        val v = number(insn.dst)
                        val take = when (insn.code) {
                            0x15 -> v == insn.imm.toLong()
                            0x55 -> v != insn.imm.toLong()
                            else -> v >= insn.imm.toLong()
                        }
                        if (take) next += insn.off
                    }
                    0x5d -> if (number(insn.dst) != number(insn.src)) next += insn.off
                    0x05 -> next += insn.off
                    0x85 -> {
                        val returned: Value = when (insn.imm) {
                        14 -> Value.Number((pid.toLong() shl 32) or 1L)
                        113 -> {
                            reads++
                            if (reads == 1 && firstReadFails) {
                                Value.Number(-14)
                            } else {
                                stack[(registers[1] as Value.Address).offset] = if (reads == 1) 111L else secondReadFd.toLong()
                                Value.Number(0)
                            }
                        }
                        1 -> {
                            val map = (registers[1] as Value.Map).index
                            val key = stack[(registers[2] as Value.Address).offset]?.toInt() ?: error("missing key at $pc")
                            val cell = when (map) {
                                0 -> if (key == 111 || key == 222) LongArray(5) else null
                                1 -> LongArray(2)
                                3 -> LongArray(2)
                                4 -> earlyCounters.getOrNull(key)
                                9 -> earlyCounters.getOrNull(key)
                                5 -> when (key) {
                                    111 -> if (missingPreGateEntry) null else pre
                                    222 -> other
                                    else -> null
                                }
                                10 -> when (key) {
                                    111 -> if (missingPreGateEntry) null else pre
                                    222 -> other
                                    else -> null
                                }
                                7 -> LongArray(19)
                                else -> null
                            }
                            if (cell == null) Value.Number(0) else Value.Entry(map, key, cell)
                        }
                        else -> error("unsupported helper ${insn.imm} at $pc")
                    }
                        (1..5).forEach { register -> registers[register] = Value.Number(Long.MIN_VALUE) }
                        registers[0] = returned
                    }
                    0xdb -> (registers[insn.dst] as Value.Entry).value[insn.off.toInt() / 8] += number(insn.src)
                    0x95 -> return
                    else -> error("unsupported instruction ${insn.code.toString(16)} at $pc")
                }
                pc = next
            }
            error("diagnostic prefix did not reach the in-flight boundary: pc=$pc steps=$steps")
        }

        private fun sourceIsPtRegs(
            value: Value,
            offset: Int,
        ): Boolean = value == Value.Context && offset == 0
    }
}
