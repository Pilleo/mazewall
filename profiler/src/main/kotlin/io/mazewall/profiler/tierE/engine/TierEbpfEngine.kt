package io.mazewall.profiler.tierE.engine

import io.mazewall.LinuxNative.SyscallResult
import io.mazewall.RawSyscallOperations
import io.mazewall.core.NativeArg
import io.mazewall.core.NativeArg.MemoryArg
import io.mazewall.ffi.memory.ConfinedSegment
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Pure-Kotlin eBPF engine for Tier E context attribution.
 *
 * Loads a raw_tp/sys_enter program plus HASH/RINGBUF/ARRAY maps entirely
 * through bpf(2) syscalls via FFM downcalls. No libbpf, no C shim,
 * no marker library.
 */
public class TierEbpfEngine(
    private val native: RawSyscallOperations,
) : AutoCloseable {
    public companion object {
        private const val SYS_BPF: Long = 321
        private const val SYS_CLOSE: Long = 3

        private const val BPF_MAP_CREATE: Long = 0
        private const val BPF_MAP_UPDATE_ELEM: Long = 2
        private const val BPF_MAP_DELETE_ELEM: Long = 3
        private const val BPF_PROG_LOAD: Long = 5
        private const val BPF_RAW_TRACEPOINT_OPEN: Long = 17

        private const val MAP_HASH: Int = 1
        private const val MAP_ARRAY: Int = 2
        private const val MAP_RINGBUF: Int = 27
        private const val PROG_RAW_TP: Int = 17

        /** Atomic add-and-store opcode: lock *(u64*)(dst+off) += src. */
        private const val ATOMIC_ADD_DW: Int = 0xdb

        // eBPF helper IDs (uapi/linux/bpf.h)
        private const val H_LOOKUP: Int = 1
        private const val H_KTIME: Int = 5
        private const val H_PID_TGID: Int = 14
        private const val H_RB_RESERVE: Int = 131
        private const val H_RB_SUBMIT: Int = 132

        // eBPF opcodes (64-bit class)
        private const val ADD_IMM: Int = 0x07
        private const val LDX_W: Int = 0x61
        private const val STX_W: Int = 0x63
        private const val STX_DW: Int = 0x7b
        private const val JEQ_IMM: Int = 0x15
        private const val JNE_REG: Int = 0x5d
        private const val AND_IMM: Int = 0x57
        private const val RSH_IMM: Int = 0x77
        private const val LD_MAP_FD: Int = 0x18
        private const val MOV_IMM: Int = 0xb7
        private const val MOV_REG: Int = 0xbf
        private const val CALL: Int = 0x85
        private const val EXIT: Int = 0x95

        private const val PSEUDO_FD: Int = 1
        private const val FP: Int = 10

        /** Event layout consumed by userspace: ktime(8) tgid(4) tid(4) nr(4) ctx(4). */
        public const val EVENT_SIZE: Int = 24
        private const val NAME_MAX: Int = 16
    }

    /** One eBPF instruction. */
    public data class Insn(val code: Int, val dst: Int = 0, val src: Int = 0, val off: Short = 0, val imm: Int = 0)

    @Volatile private var hashFd: Int = -1
    @Volatile private var ringFd: Int = -1
    @Volatile private var targetFd: Int = -1
    @Volatile private var attrFd: Int = -1
    @Volatile private var progFd: Int = -1
    @Volatile private var linkFd: Int = -1

    public fun install(targetTgid: Int) {
        check(hashFd < 0) { "already installed" }
        hashFd = createMap(MAP_HASH, keySize = 4, valueSize = 4, maxEntries = 65_536)
        ringFd = createMap(MAP_RINGBUF, keySize = 0, valueSize = 0, maxEntries = 1 shl 20)
        targetFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 4, maxEntries = 1)
        attrFd = createMap(MAP_ARRAY, keySize = 4, valueSize = 8, maxEntries = 4096)
        Arena.ofConfined().use { updateElem(it, targetFd, intSeg(it, 0), intSeg(it, targetTgid)) }
        progFd = loadProg(listOf(hashFd, ringFd, targetFd, attrFd))
        linkFd = openRawTp(progFd, "sys_enter")
    }

    /** Reads the per-context attributed-syscall counters. Index = context id (0..4095). */
    public fun readAttrCounts(): LongArray {
        require(attrFd >= 0) { "not installed" }
        return LongArray(4096).also { out ->
            Arena.ofConfined().use { a ->
                for (ctx in 0 until 4096) {
                    val attr = a.allocate(16)
                    attr.set(ValueLayout.JAVA_INT, 0, attrFd)
                    val keySeg = intSeg(a, ctx)
                    val valSeg = a.allocate(8, 8)
                    attr.set(ValueLayout.ADDRESS, 8, keySeg)
                    // BPF_MAP_LOOKUP_ELEM: fd, key_ptr, value_ptr
                    val lookupAttr = a.allocate(24)
                    lookupAttr.set(ValueLayout.JAVA_INT, 0, attrFd)
                    lookupAttr.set(ValueLayout.ADDRESS, 8, keySeg)
                    lookupAttr.set(ValueLayout.ADDRESS, 16, valSeg)
                    try {
                        bpfCall(1L /* LOOKUP */, lookupAttr, 24)
                        out[ctx] = valSeg.get(ValueLayout.JAVA_LONG, 0)
                    } catch (_: Exception) {
                        // entry not present
                    }
                }
            }
        }
    }

    public fun setContext(tid: Int, contextId: Int) {
        require(hashFd >= 0) { "not installed" }
        Arena.ofConfined().use {
            if (contextId == 0) deleteElem(it, hashFd, intSeg(it, tid))
            else updateElem(it, hashFd, intSeg(it, tid), intSeg(it, contextId))
        }
    }

    override fun close() {
        closeIfOpen(linkFd); linkFd = -1
        closeIfOpen(progFd); progFd = -1
        closeIfOpen(ringFd); ringFd = -1
        closeIfOpen(targetFd); targetFd = -1
        closeIfOpen(attrFd); attrFd = -1
        closeIfOpen(hashFd); hashFd = -1
    }

    // ── program construction ────────────────────────────────────────────────

    internal fun buildProgram(): List<Insn> {
        val p = mutableListOf<Insn>()
        fun emit(i: Insn): Int { p += i; return p.size - 1 }
        fun ldMap(dst: Int, idx: Int) {
            emit(Insn(LD_MAP_FD, dst = dst, src = PSEUDO_FD, imm = idx))
            emit(Insn(code = 0x00))
        }
        fun exitFrom(fromIdx: Int) {
            p[fromIdx] = p[fromIdx].copy(off = (p.size - fromIdx - 1).toShort())
        }

        emit(Insn(CALL, imm = H_PID_TGID))
        emit(Insn(MOV_REG, dst = 9, src = 0))
        emit(Insn(MOV_REG, dst = 8, src = 9))
        emit(Insn(AND_IMM, dst = 8, imm = -1))   // r8 = tid
        emit(Insn(MOV_REG, dst = 7, src = 9))
        emit(Insn(RSH_IMM, dst = 7, imm = 32))   // r7 = tgid

        ldMap(1, 2)
        emit(Insn(MOV_IMM, dst = 2, imm = 0))
        emit(Insn(MOV_REG, dst = 3, src = FP))
        emit(Insn(ADD_IMM, dst = 3, imm = -8))
        emit(Insn(CALL, imm = H_LOOKUP))
        val jNoTarget = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 5, src = 0))
        val jNotTarget = emit(Insn(JNE_REG, dst = 5, src = 7))

        ldMap(1, 0)
        emit(Insn(MOV_REG, dst = 2, src = 8))
        emit(Insn(MOV_REG, dst = 3, src = FP))
        emit(Insn(ADD_IMM, dst = 3, imm = -8))
        emit(Insn(CALL, imm = H_LOOKUP))
        val jNoCtx = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(LDX_W, dst = 5, src = 0))
        val jZeroCtx = emit(Insn(JEQ_IMM, dst = 5))
        ldMap(1, 1)
        emit(Insn(MOV_IMM, dst = 2, imm = EVENT_SIZE))
        emit(Insn(MOV_IMM, dst = 3, imm = 0))
        emit(Insn(CALL, imm = H_RB_RESERVE))
        val jDrop = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_REG, dst = 6, src = 0))

        emit(Insn(CALL, imm = H_KTIME))
        emit(Insn(STX_DW, dst = 6, src = 0, off = 0))
        emit(Insn(STX_W, dst = 6, src = 7, off = 8))
        emit(Insn(STX_W, dst = 6, src = 8, off = 12))
        emit(Insn(MOV_IMM, dst = 0, imm = -1))
        emit(Insn(STX_W, dst = 6, src = 0, off = 16))
        emit(Insn(STX_W, dst = 6, src = 5, off = 20))

        emit(Insn(MOV_REG, dst = 1, src = 6))
        emit(Insn(MOV_IMM, dst = 2, imm = 0))
        emit(Insn(CALL, imm = H_RB_SUBMIT))

        // Increment attr_by_ctx[context_id] atomically.
        emit(Insn(STX_W, dst = FP, src = 5, off = -12))  // key = ctx_id at fp-12
        ldMap(1, 3)                                      // r1 = attr map fd
        emit(Insn(MOV_REG, dst = 2, src = FP))
        emit(Insn(ADD_IMM, dst = 2, imm = -12))          // r2 = &key
        emit(Insn(CALL, imm = H_LOOKUP))
        val jNoSlot = emit(Insn(JEQ_IMM, dst = 0))
        emit(Insn(MOV_IMM, dst = 1, imm = 1))
        emit(Insn(ATOMIC_ADD_DW, dst = 0, src = 1, off = 0))
        // fallthrough → exit
        emit(Insn(EXIT))

        exitFrom(jNoTarget); exitFrom(jNoCtx); exitFrom(jZeroCtx); exitFrom(jDrop); exitFrom(jNoSlot)
        val end = p.size
        p[jNotTarget] = Insn(JNE_REG, dst = 5, src = 7, off = (end - jNotTarget - 1).toShort())

        return p.toList()
    }

    internal fun pack(i: Insn): Long {
        val code = i.code.toLong() and 0xff
        val regs = ((i.src and 0xF).toLong() shl 4) or (i.dst and 0xF).toLong()
        val off = (i.off.toLong() and 0xFFFF) shl 16
        val imm = (i.imm.toLong() and 0xFFFFFFFFL) shl 32
        return code or (regs shl 8) or off or imm
    }

    // ── bpf(2) wrappers ─────────────────────────────────────────────────────

    private fun bpfCall(cmd: Long, attr: MemorySegment, size: Long): Int {
        return when (
            val res = native.syscall(
                SYS_BPF,
                NativeArg.LongArg(cmd),
                NativeArg.MemoryArg(ConfinedSegment(attr)),
                NativeArg.LongArg(size),
            )
        ) {
            is SyscallResult.Success -> res.value.toInt()
            else -> error("bpf(cmd=$cmd) failed")
        }
    }

    private fun createMap(type: Int, keySize: Int, valueSize: Int, maxEntries: Int): Int =
        Arena.ofConfined().use {
            val attr = it.allocate(72)
            attr.set(ValueLayout.JAVA_INT, 0, type)
            attr.set(ValueLayout.JAVA_INT, 4, keySize)
            attr.set(ValueLayout.JAVA_INT, 8, valueSize)
            attr.set(ValueLayout.JAVA_INT, 12, maxEntries)
            bpfCall(BPF_MAP_CREATE, attr, 72)
        }

    private fun updateElem(a: Arena, fd: Int, key: MemorySegment, value: MemorySegment) {
        val attr = a.allocate(32)
        attr.set(ValueLayout.JAVA_INT, 0, fd)
        attr.set(ValueLayout.ADDRESS, 8, key)
        attr.set(ValueLayout.ADDRESS, 16, value)
        bpfCall(BPF_MAP_UPDATE_ELEM, attr, 32)
    }

    private fun deleteElem(a: Arena, fd: Int, key: MemorySegment) {
        val attr = a.allocate(16)
        attr.set(ValueLayout.JAVA_INT, 0, fd)
        attr.set(ValueLayout.ADDRESS, 8, key)
        bpfCall(BPF_MAP_DELETE_ELEM, attr, 16)
    }

    private fun loadProg(mapFds: List<Int>): Int {
        val insns = buildProgram().map { insn ->
            if (insn.code == LD_MAP_FD && insn.src == PSEUDO_FD) insn.copy(imm = mapFds[insn.imm]) else insn
        }
        return Arena.ofConfined().use {
            val packed = insns.map(::pack)
            val seg = it.allocate(packed.size * 8L, 8)
            packed.forEachIndexed { i, v -> seg.set(ValueLayout.JAVA_LONG, i * 8L, v) }

            val attr = it.allocate(104)
            attr.set(ValueLayout.JAVA_INT, 0, PROG_RAW_TP)
            attr.set(ValueLayout.JAVA_INT, 4, insns.size)
            attr.set(ValueLayout.ADDRESS, 8, seg)
            attr.set(ValueLayout.ADDRESS, 16, strSeg(it, "tier_e_ctx", NAME_MAX))
            attr.set(ValueLayout.ADDRESS, 24, strSeg(it, "GPL", 4))
            bpfCall(BPF_PROG_LOAD, attr, 104)
        }
    }

    private fun openRawTp(progFd: Int, name: String): Int =
        Arena.ofConfined().use {
            val attr = it.allocate(80)
            attr.set(ValueLayout.JAVA_INT, 0, progFd)
            attr.set(ValueLayout.ADDRESS, 8, strSeg(it, name, 64))
            bpfCall(BPF_RAW_TRACEPOINT_OPEN, attr, 80)
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun strSeg(a: Arena, s: String, size: Int): MemorySegment {
        val seg = a.allocate(size.toLong())
        s.toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> seg.set(ValueLayout.JAVA_BYTE, i.toLong(), b) }
        return seg
    }

    private fun intSeg(a: Arena, v: Int): MemorySegment {
        val s = a.allocate(4, 4)
        s.set(ValueLayout.JAVA_INT, 0, v)
        return s
    }

    private fun closeIfOpen(fd: Int) {
        if (fd >= 0) native.syscall(SYS_CLOSE, NativeArg.LongArg(fd.toLong()))
    }
}
