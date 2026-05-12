package io.contained

import java.util.logging.Logger

/**
 * Builds seccomp-bpf programs using a hybrid BST + Linear Scan approach.
 * This ensures O(log N) performance and stays within BPF's 255-jump limit by 
 * using localized return terminals.
 */
object BpfFilter {
    private val logger = Logger.getLogger(BpfFilter::class.java.name)

    private const val BPF_LD = 0x00
    private const val BPF_JMP = 0x05
    private const val BPF_RET = 0x06
    private const val BPF_W = 0x00
    private const val BPF_ABS = 0x20
    private const val BPF_JEQ = 0x10
    private const val BPF_JGT = 0x20
    private const val BPF_K = 0x00

    private const val SECCOMP_DATA_NR_OFFSET = 0
    private const val SECCOMP_DATA_ARCH_OFFSET = 4
    
    // Max instructions in a single contiguous block.
    private const val MAX_BLOCK_SIZE = 100

    private sealed class Insn {
        data class Stmt(val code: Int, val k: Int) : Insn()
        data class Jmp(val code: Int, val k: Int, val jtTarget: String, val jfTarget: String) : Insn()
    }

    private fun stmt(code: Int, k: Int): Insn.Stmt = Insn.Stmt(code, k)
    private fun jmp(code: Int, k: Int, jt: String, jf: String): Insn.Jmp = Insn.Jmp(code, k, jt, jf)

    fun build(arch: Arch, policy: Policy): Array<SockFilter> =
        buildFromNumbers(arch, policy.blockedSyscalls(arch))

    internal fun buildFromNumbers(arch: Arch, blocked: IntArray): Array<SockFilter> {
        val insns = mutableListOf<Insn>()
        val labels = mutableMapOf<String, Int>()

        // 1. Prologue: Check Architecture
        insns.add(stmt(BPF_LD or BPF_W or BPF_ABS, SECCOMP_DATA_ARCH_OFFSET))
        insns.add(jmp(BPF_JMP or BPF_JEQ or BPF_K, arch.audit, "arch_ok", "deny_prologue"))
        
        labels["arch_ok"] = insns.size
        insns.add(stmt(BPF_LD or BPF_W or BPF_ABS, SECCOMP_DATA_NR_OFFSET))

        // 2. High-Bound Check
        insns.add(jmp(BPF_JMP or BPF_JGT or BPF_K, arch.limit, "deny_prologue", "check_blocks"))

        // Local Deny for prologue (must be within 255 instructions of the top)
        labels["deny_prologue"] = insns.size
        insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ERRNO or LinuxNative.EPERM))

        // 3. Block-based checks
        labels["check_blocks"] = insns.size
        if (blocked.isEmpty()) {
            insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ALLOW))
        } else {
            generateBlocks(blocked, 0, blocked.size, insns, labels)
        }

        return resolve(insns, labels)
    }

    private fun generateBlocks(
        blocked: IntArray,
        start: Int,
        end: Int,
        insns: MutableList<Insn>,
        labels: MutableMap<String, Int>
    ) {
        val count = end - start
        if (count <= MAX_BLOCK_SIZE) {
            // Linear scan for this block
            val localDeny = "deny_block_${start}_${end}"
            val localAllow = "allow_block_${start}_${end}"
            
            for (i in start until end) {
                val nextLabel = if (i + 1 < end) "next_${i}" else localAllow
                insns.add(jmp(BPF_JMP or BPF_JEQ or BPF_K, blocked[i], localDeny, nextLabel))
                if (i + 1 < end) labels[nextLabel] = insns.size
            }
            
            labels[localDeny] = insns.size
            insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ERRNO or LinuxNative.EPERM))
            
            labels[localAllow] = insns.size
            insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ALLOW))
            return
        }

        val mid = start + count / 2
        val leftLabel = "block_${start}_${mid}"
        val rightLabel = "block_${mid}_${end}"

        insns.add(jmp(BPF_JMP or BPF_JGT or BPF_K, blocked[mid - 1], rightLabel, leftLabel))

        labels[leftLabel] = insns.size
        generateBlocks(blocked, start, mid, insns, labels)

        labels[rightLabel] = insns.size
        generateBlocks(blocked, mid, end, insns, labels)
    }

    private fun resolve(insns: List<Insn>, labels: Map<String, Int>): Array<SockFilter> {
        return insns.mapIndexed { i, insn ->
            when (insn) {
                is Insn.Stmt -> SockFilter(insn.code.toShort(), 0, 0, insn.k)
                is Insn.Jmp -> {
                    val jt = resolveOffset(i, insn.jtTarget, labels)
                    val jf = resolveOffset(i, insn.jfTarget, labels)
                    SockFilter(insn.code.toShort(), jt.toShort(), jf.toShort(), insn.k)
                }
            }
        }.toTypedArray()
    }

    private fun resolveOffset(fromIdx: Int, target: String, labels: Map<String, Int>): Int {
        val targetIdx = labels[target] ?: throw IllegalStateException("Unresolved label: $target")
        val offset = targetIdx - fromIdx - 1
        if (offset < 0 || offset > 255) {
            throw IllegalStateException("BPF jump offset overflow from $fromIdx to '$target' (offset $offset). " +
                "Total instructions so far: ${labels.size}")
        }
        return offset
    }
}
