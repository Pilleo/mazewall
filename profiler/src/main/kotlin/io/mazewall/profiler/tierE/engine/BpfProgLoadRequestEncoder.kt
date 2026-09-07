package io.mazewall.profiler.tierE.engine

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Builds the arena-owned `bpf_attr` payload for `BPF_PROG_LOAD` without issuing a syscall. */
internal object BpfProgLoadRequestEncoder {
    private const val LD_MAP_FD = 0x18
    private const val PSEUDO_FD = 1
    private const val VERIFIER_LOG_SIZE = 64 * 1024L

    internal data class Request(
        val attr: MemorySegment,
        val instructions: MemorySegment,
        val verifierLog: MemorySegment,
    )

    fun encode(
        arena: Arena,
        program: List<TierEbpfEngine.Insn>,
        mapFds: List<Int>,
        programType: Int,
        programName: String,
    ): Request {
        val programNameBytes = programName.toAsciiProgramName()
        require(programNameBytes.size < BpfProgLoadLayout.PROGRAM_NAME_SIZE) {
            "BPF program name must fit ${BpfProgLoadLayout.PROGRAM_NAME_SIZE - 1} ASCII bytes"
        }
        val instructions = program.map { instruction ->
            if (instruction.code == LD_MAP_FD && instruction.src == PSEUDO_FD) {
                require(instruction.imm in mapFds.indices) { "Missing map FD for pseudo index ${instruction.imm}" }
                instruction.copy(imm = mapFds[instruction.imm])
            } else {
                instruction
            }
        }
        val packed = instructions.map(::pack)
        val instructionSegment = arena.allocate(packed.size * 8L, 8)
        packed.forEachIndexed { index, value -> instructionSegment.set(ValueLayout.JAVA_LONG, index * 8L, value) }

        val verifierLog = arena.allocate(VERIFIER_LOG_SIZE)
        val attr = arena.allocate(BpfProgLoadLayout.SIZE)
        attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.PROGRAM_TYPE, programType)
        attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.INSTRUCTION_COUNT, instructions.size)
        attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.INSTRUCTIONS, instructionSegment)
        attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.LICENSE, ascii(arena, "GPL", 4))
        attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_LEVEL, 1)
        attr.set(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_SIZE, verifierLog.byteSize().toInt())
        attr.set(ValueLayout.ADDRESS, BpfProgLoadLayout.LOG_BUFFER, verifierLog)
        programNameBytes.forEachIndexed { index, byte ->
            attr.set(ValueLayout.JAVA_BYTE, BpfProgLoadLayout.PROGRAM_NAME + index, byte)
        }
        return Request(attr, instructionSegment, verifierLog)
    }

    fun pack(instruction: TierEbpfEngine.Insn): Long {
        val code = instruction.code.toLong() and 0xff
        val registers = ((instruction.src and 0xF).toLong() shl 4) or (instruction.dst and 0xF).toLong()
        val offset = (instruction.off.toLong() and 0xFFFF) shl 16
        val immediate = (instruction.imm.toLong() and 0xFFFFFFFFL) shl 32
        return code or (registers shl 8) or offset or immediate
    }

    private fun ascii(
        arena: Arena,
        value: String,
        size: Int,
    ): MemorySegment =
        arena.allocate(size.toLong()).also { segment ->
        value.toByteArray(Charsets.US_ASCII).forEachIndexed { index, byte ->
            segment.set(ValueLayout.JAVA_BYTE, index.toLong(), byte)
        }
    }

    private fun String.toAsciiProgramName(): ByteArray {
        require(all { character -> character.code in 1..0x7F }) {
            "BPF program name must contain only non-NUL ASCII characters"
        }
        return toByteArray(Charsets.US_ASCII)
    }
}
