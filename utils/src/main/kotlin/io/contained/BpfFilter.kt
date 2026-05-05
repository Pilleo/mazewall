package io.contained

object BpfFilter {

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

    private class Label(val name: String)

    private sealed class Insn {
        data class Stmt(val code: Int, val k: Int) : Insn()
        data class Jmp(val code: Int, val k: Int, val jtTarget: String, val jfTarget: String) : Insn()
    }

    private fun stmt(code: Int, k: Int): Insn.Stmt = Insn.Stmt(code, k)

    private fun jmp(code: Int, k: Int, jtTarget: String, jfTarget: String): Insn.Jmp =
        Insn.Jmp(code, k, jtTarget, jfTarget)

    fun build(arch: Arch, policy: Policy): Array<SockFilter> {
        val blocked = policy.blockedSyscalls(arch)
        val n = blocked.size

        val insns = mutableListOf<Insn>()
        val labels = mutableMapOf<String, Int>()

        // [0] Load arch field
        insns.add(stmt(BPF_LD or BPF_W or BPF_ABS, SECCOMP_DATA_ARCH_OFFSET))
        // [1] Arch check: on match fall through, on mismatch jump to "deny"
        insns.add(jmp(BPF_JMP or BPF_JEQ or BPF_K, arch.audit, "next", "deny"))

        labels["next"] = insns.size // points to [2]
        // [2] Load syscall number
        insns.add(stmt(BPF_LD or BPF_W or BPF_ABS, SECCOMP_DATA_NR_OFFSET))
        // [3] Range check: too high -> deny, within range -> next
        val rangePassTarget = if (n > 0) "check_0" else "allow"
        insns.add(jmp(BPF_JMP or BPF_JGT or BPF_K, arch.limit, "deny", rangePassTarget))

        // [4..3+n] Blocked syscall checks
        for (i in 0 until n) {
            labels["check_$i"] = insns.size // points to this check
            val nextTarget = if (i + 1 < n) "check_${i + 1}" else "allow"
            insns.add(jmp(BPF_JMP or BPF_JEQ or BPF_K, blocked[i], "deny", nextTarget))
        }

        // [4+n] Allow
        labels["allow"] = insns.size
        insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ALLOW))

        // [5+n] Deny
        labels["deny"] = insns.size
        insns.add(stmt(BPF_RET or BPF_K, LinuxNative.SECCOMP_RET_ERRNO or LinuxNative.EPERM))

        return resolve(insns, labels)
    }

    private fun resolve(insns: List<Insn>, labels: Map<String, Int>): Array<SockFilter> {
        return insns.mapIndexed { i, insn ->
            when (insn) {
                is Insn.Stmt -> SockFilter(insn.code.toShort(), 0, 0, insn.k)
                is Insn.Jmp -> {
                    val jt = resolveOffset(i, insn.jtTarget, labels)
                    val jf = resolveOffset(i, insn.jfTarget, labels)
                    SockFilter(insn.code.toShort(), jt.toByte(), jf.toByte(), insn.k)
                }
            }
        }.toTypedArray()
    }

    private fun resolveOffset(fromIdx: Int, target: String, labels: Map<String, Int>): Int {
        val targetIdx = labels[target]
            ?: throw IllegalStateException("Unresolved label: $target")
        return targetIdx - fromIdx - 1
    }
}
