package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpfFilterTest {
    private val arch = Arch.AMD64

    @Test
    fun `filter contains arch check`() {
        val filter = BpfFilter.build(arch, Policy.builder().build().definition)

        // Find LD W ABS 4 (Load architecture audit ID)
        val ldIndex = filter.indexOfFirst { it.code == 0x20.toShort() && it.k == 4 }
        assertTrue(ldIndex >= 0, "Filter should contain instruction to load architecture audit ID")

        // Next instruction must be the JEQ audit arch check
        val jmpIndex = ldIndex + 1
        val jmpIns = filter[jmpIndex]
        assertEquals(0x15.toShort(), jmpIns.code, "Next instruction should be JEQ check")
        assertEquals(Arch.AUDIT_ARCH_X86_64, jmpIns.k, "Filter should check for X86_64 architecture")
        assertEquals(1, jmpIns.jt, "jt jump offset should be 1 to skip the kill instruction on success")
        assertEquals(0, jmpIns.jf, "jf jump offset should be 0 to fall through to the kill instruction on failure")

        // If mismatch, fall through to the strict RET SECCOMP_RET_KILL_PROCESS
        val killIndex = jmpIndex + 1
        val killIns = filter[killIndex]
        assertEquals(0x06.toShort(), killIns.code, "Instruction after JEQ should be RET")
        assertEquals(NativeConstants.SECCOMP_RET_KILL_PROCESS, killIns.k, "Architecture check mismatch should strictly return SECCOMP_RET_KILL_PROCESS")
    }

    @Test
    fun `filter contains syscall nr load`() {
        val filter = BpfFilter.build(arch, Policy.builder().build().definition)
        // Load syscall NR (LD W ABS 0)
        val hasSyscallLoad = filter.any { it.code == 0x20.toShort() && it.k == 0 }
        assertTrue(hasSyscallLoad, "Filter should contain instruction to load syscall number")
    }

    @Test
    fun `empty policy allows all syscalls`() {
        val filter = BpfFilter.build(arch, Policy.builder().build().definition)
        // The last instruction should be RET ALLOW
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code, "Last instruction should be RET")
        assertEquals(NativeConstants.SECCOMP_RET_ALLOW, last.k, "Last instruction should return ALLOW")
    }

    @Test
    fun `ALLOW_LIST mode has RET DENY as default`() {
        val policy = Policy.builder().defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO).build()
        val filter = BpfFilter.build(arch, policy.definition)
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code)
        assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, last.k)
    }

    @Test
    fun `ALLOW_LIST mode generates RET ALLOW for listed syscalls`() {
        val policy =
            Policy
                .builder()
                .defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO)
                .allow(Syscall.READ)
                .build()
        val filter = BpfFilter.build(arch, policy.definition)

        // Find JEQ read -> RET ALLOW
        val readNr = Syscall.READ.numberFor(arch)
        var found = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == readNr) {
                // jt=0 (match), next instruction should be RET ALLOW
                val next = filter[i + 1]
                if (next.code == 0x06.toShort() && next.k == NativeConstants.SECCOMP_RET_ALLOW) {
                    found = true
                    break
                }
            }
        }
        assertTrue(found, "Filter should return ALLOW for listed syscall in ALLOW_LIST mode")
    }

    @Test
    fun `clone3 always returns ENOSYS even in ALLOW_LIST`() {
        val policy = Policy.builder().defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO).build()
        val filter = BpfFilter.build(arch, policy.definition)

        val clone3Nr = arch.clone3
        var found = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == clone3Nr) {
                val next = filter[i + 1]
                if (next.code == 0x06.toShort() && next.k == (NativeConstants.SECCOMP_RET_ERRNO or 38)) {
                    found = true
                    break
                }
            }
        }
        assertTrue(found, "clone3 should always return ENOSYS")
    }

    @Test
    fun `testBpfMmapArgumentInspection`() {
        val policy = Policy.builder().unblock(Syscall.MMAP).build() // NO_EXEC by default blocks mmap exec
        val filter = BpfFilter.build(arch, policy.definition)

        // Find JEQ mmap -> check PROT_EXEC
        val mmapNr = Syscall.MMAP.numberFor(arch)
        var foundInspection = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == mmapNr) {
                // Should load args[2] HI (offset 32 + 4)
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 36) {
                    // Should bitwise AND with 0x04 (PROT_EXEC) -> hi mask is 0
                    val andIns = filter[i + 2]
                    if (andIns.code == 0x54.toShort() && andIns.k == 0) {
                        // Should check JEQ 0 (expected hi)
                        val jeqIns = filter[i + 3]
                        if (jeqIns.code == 0x15.toShort() && jeqIns.k == 0) {
                            foundInspection = true
                        }
                    }
                }
            }
        }
        assertTrue(foundInspection, "Filter should inspect mmap arguments for PROT_EXEC")
    }

    @Test
    fun `testBpfCloneArgumentInspection`() {
        val policy = Policy.builder().build() // NO_EXEC by default protects clone
        val filter = BpfFilter.build(arch, policy.definition)

        val cloneNr = Syscall.CLONE.numberFor(arch)
        var foundInspection = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == cloneNr) {
                // Should load args[0] HI (offset 16 + 4)
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 20) {
                    // Should mask CLONE_VM | CLONE_THREAD (0x00010100) -> hi mask = 0
                    val mask = filter[i + 2]
                    if (mask.code == 0x54.toShort() && mask.k == 0) {
                        foundInspection = true
                    }
                }
            }
        }
        assertTrue(foundInspection, "Filter should inspect clone arguments for CLONE_THREAD")
    }

    @Test
    fun `testBpfPrctlArgumentInspection`() {
        val policy = Policy.builder().build() // NO_EXEC protects prctl
        val filter = BpfFilter.build(arch, policy.definition)

        val prctlNr = Syscall.PRCTL.numberFor(arch)
        var foundInspection = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == prctlNr) {
                // Should load args[0] HI (offset 16 + 4 = 20)
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 20) {
                    foundInspection = true
                }
            }
        }
        assertTrue(foundInspection, "Filter should inspect prctl arguments")
    }

    @Test
    fun `testBpfMaskEquals zero and non-zero`() {
        val inspections = listOf(
            SyscallInspection(
                syscallNumber = 100,
                argIndex = 1,
                check = ArgCheck.MaskEquals(0x04L, 0x00L),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
            SyscallInspection(
                syscallNumber = 101,
                argIndex = 2,
                check = ArgCheck.MaskEquals(0x04L, 0x04L),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
        )
        val builder = BpfProgram.builder()
            .checkArch(arch)
            .loadSyscallNr()
        val handled = mutableSetOf<Int>()
        BpfFilter.emitInspections(builder, inspections, false, handled)
        val instructions = builder.allow().build().instructions
        assertTrue(handled.contains(100))
        assertTrue(handled.contains(101))

        // Find JEQ 100
        val has100 = instructions.any { it.code == 0x15.toShort() && it.k == 100 }
        assertTrue(has100)
        // Find JEQ 101
        val has101 = instructions.any { it.code == 0x15.toShort() && it.k == 101 }
        assertTrue(has101)
    }

    @Test
    fun `testBpfEqualsAny options size variety`() {
        val inspections = listOf(
            SyscallInspection(
                syscallNumber = 200,
                argIndex = 0,
                check = ArgCheck.EqualsAny(listOf(5L)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
            SyscallInspection(
                syscallNumber = 201,
                argIndex = 0,
                check = ArgCheck.EqualsAny(listOf(10L, 20L)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
        )
        val builder = BpfProgram.builder()
            .checkArch(arch)
            .loadSyscallNr()
        val handled = mutableSetOf<Int>()
        BpfFilter.emitInspections(builder, inspections, false, handled)
        val instructions = builder.allow().build().instructions
        assertTrue(handled.contains(200))
        assertTrue(handled.contains(201))

        // Ensure instruction jumps check correct expected values
        val valuesChecked = instructions.filter { it.code == 0x15.toShort() }.map { it.k }
        assertTrue(valuesChecked.contains(200))
        assertTrue(valuesChecked.contains(201))
        assertTrue(valuesChecked.contains(5))
        assertTrue(valuesChecked.contains(10))
        assertTrue(valuesChecked.contains(20))
    }

    @Test
    fun `testBpfMaskEquals handles 64-bit values accurately across HI and LO words`() {
        // e.g. clone() checking CLONE_NEWNET (0x40000000L) and something in HI like 0x0000000100000000L
        val maskVal = 0x0000000140000000L
        val inspections = listOf(
            SyscallInspection(
                syscallNumber = 300,
                argIndex = 0,
                check = ArgCheck.MaskEquals(maskVal, maskVal),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
        )
        val builder = BpfProgram.builder()
            .checkArch(arch)
            .loadSyscallNr()
        val handled = mutableSetOf<Int>()
        BpfFilter.emitInspections(builder, inspections, false, handled)
        val instructions = builder.allow().build().instructions

        // Check if we find the correct operations on hi and lo bounds:
        val maskHi = (maskVal ushr 32).toInt() // 1
        val maskLo = maskVal.toInt() // 0x40000000

        val hasHiMask = instructions.any { it.code == 0x54.toShort() && it.k == maskHi }
        val hasLoMask = instructions.any { it.code == 0x54.toShort() && it.k == maskLo }

        assertTrue(hasHiMask, "Filter should contain the bitwise AND instruction for the HI half")
        assertTrue(hasLoMask, "Filter should contain the bitwise AND instruction for the LO half")
    }

    @Test
    fun `testBpfEqualsAny handles 64-bit values accurately across HI and LO words`() {
        val largeVal1 = 0x1122334455667788L
        val largeVal2 = -0x778899aabbccddefL
        val inspections = listOf(
            SyscallInspection(
                syscallNumber = 400,
                argIndex = 0,
                check = ArgCheck.EqualsAny(listOf(largeVal1, largeVal2)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO,
            ),
        )
        val builder = BpfProgram.builder()
            .checkArch(arch)
            .loadSyscallNr()
        val handled = mutableSetOf<Int>()
        BpfFilter.emitInspections(builder, inspections, false, handled)
        val instructions = builder.allow().build().instructions

        val hi1 = (largeVal1 ushr 32).toInt()
        val lo1 = largeVal1.toInt()
        val hi2 = ((largeVal2 ushr 32)).toInt()
        val lo2 = largeVal2.toInt()

        // Ensure instruction jumps check correct expected values
        val valuesChecked = instructions.filter { it.code == 0x15.toShort() }.map { it.k }
        assertTrue(valuesChecked.contains(400), "Syscall check should be present")
        assertTrue(valuesChecked.contains(hi1), "HI 1 check should be present")
        assertTrue(valuesChecked.contains(lo1), "LO 1 check should be present")
        assertTrue(valuesChecked.contains(hi2), "HI 2 check should be present")
        assertTrue(valuesChecked.contains(lo2), "LO 2 check should be present")
    }

    @Test
    fun `test BpfFilter groups identical native actions and uses shared RET block`() {
        // Create a policy where 5 syscalls are mapped to ACT_ERRNO
        val sys1 = Syscall.GETEUID
        val sys2 = Syscall.GETPPID
        val sys3 = Syscall.GETUID
        val sys4 = Syscall.GETGID
        val sys5 = Syscall.GETEGID

        val policy = Policy.builder()
            .block(sys1, sys2, sys3, sys4, sys5)
            .build()

        val filter = BpfFilter.build(arch, policy.definition)

        val nr1 = sys1.numberFor(arch)
        val nr2 = sys2.numberFor(arch)
        val nr3 = sys3.numberFor(arch)
        val nr4 = sys4.numberFor(arch)
        val nr5 = sys5.numberFor(arch)
        val nrs = setOf(nr1, nr2, nr3, nr4, nr5)

        val targetRetAction = NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM

        // For each of the blocked syscall numbers, locate its JEQ check and resolve its target index
        val resolvedTargetsMap = nrs.associateWith { nr ->
            val jeqIdx = filter.indexOfFirst { it.code == 0x15.toShort() && it.k == nr }
            assertTrue(jeqIdx >= 0, "Should find JEQ check for syscall $nr")
            val jeqInst = filter[jeqIdx]
            val resolvedTargetIdx = jeqIdx + jeqInst.jt + 1
            resolvedTargetIdx
        }
        val resolvedTargets = resolvedTargetsMap.values.toSet()

        // All 5 syscall checks should jump to the exact same RET instruction index
        assertEquals(1, resolvedTargets.size, "All blocked syscalls should jump to the exact same instruction index: $resolvedTargetsMap")

        val sharedRetIdx = resolvedTargets.first()
        val sharedRetInst = filter[sharedRetIdx]
        assertEquals(0x06.toShort(), sharedRetInst.code, "Target instruction should be a RET instruction")
        assertEquals(targetRetAction, sharedRetInst.k, "Shared RET instruction should return the blocked action")
    }

    private fun evalBpf(instructions: List<BpfInstruction>, syscallNr: Int): Int {
        var pc = 0
        var accumulator = 0
        while (pc < instructions.size) {
            val inst = instructions[pc]
            when (inst.code) {
                0x20.toShort() -> { // BPF_LD_ABS
                    if (inst.k == 0) { // SECCOMP_DATA_NR_OFFSET
                        accumulator = syscallNr
                    } else if (inst.k == 4) { // SECCOMP_DATA_ARCH_OFFSET
                        accumulator = Arch.AUDIT_ARCH_X86_64
                    } else {
                        accumulator = 0
                    }
                    pc++
                }
                0x15.toShort() -> { // BPF_JEQ
                    if (accumulator == inst.k) {
                        pc += inst.jt.toInt() + 1
                    } else {
                        pc += inst.jf.toInt() + 1
                    }
                }
                0x25.toShort() -> { // BPF_JGT
                    val accUnsigned = accumulator.toLong() and 0xFFFFFFFFL
                    val kUnsigned = inst.k.toLong() and 0xFFFFFFFFL
                    if (accUnsigned > kUnsigned) {
                        pc += inst.jt.toInt() + 1
                    } else {
                        pc += inst.jf.toInt() + 1
                    }
                }
                0x45.toShort() -> { // BPF_JSET
                    val accUnsigned = accumulator.toLong() and 0xFFFFFFFFL
                    val kUnsigned = inst.k.toLong() and 0xFFFFFFFFL
                    if ((accUnsigned and kUnsigned) != 0L) {
                        pc += inst.jt.toInt() + 1
                    } else {
                        pc += inst.jf.toInt() + 1
                    }
                }
                0x54.toShort() -> { // BPF_ALU_AND
                    accumulator = accumulator and inst.k
                    pc++
                }
                0x06.toShort() -> { // BPF_RET
                    return inst.k
                }
                else -> {
                    pc++
                }
            }
        }
        throw IllegalStateException("BPF program fell through without returning")
    }

    @Test
    fun `blacklist policy compiled with BST contains greater-than comparisons and routes correctly`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .build()
        val filter = BpfFilter.build(arch, policy.definition)

        // Verify that BPF_JMP_JGT (0x25.toShort()) instruction is present in the compiled filter
        val hasGreaterThan = filter.any { it.code == 0x25.toShort() }
        assertTrue(hasGreaterThan, "The compiled filter for a blacklist policy should contain BPF_JMP_JGT instructions due to BST optimization")

        val blockAction = NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM
        val allowAction = NativeConstants.SECCOMP_RET_ALLOW

        // Verify routing for blocked syscalls
        assertEquals(blockAction, evalBpf(filter, Syscall.EXECVE.numberFor(arch)))
        assertEquals(blockAction, evalBpf(filter, Syscall.EXECVEAT.numberFor(arch)))
        assertEquals(blockAction, evalBpf(filter, Syscall.MEMFD_CREATE.numberFor(arch)))

        // Verify routing for allowed syscalls
        assertEquals(allowAction, evalBpf(filter, Syscall.READ.numberFor(arch)))
        assertEquals(allowAction, evalBpf(filter, Syscall.WRITE.numberFor(arch)))
        assertEquals(allowAction, evalBpf(filter, Syscall.OPEN.numberFor(arch)))
        assertEquals(allowAction, evalBpf(filter, Syscall.CLOSE.numberFor(arch)))
        assertEquals(allowAction, evalBpf(filter, Syscall.SOCKET.numberFor(arch)))
        assertEquals(allowAction, evalBpf(filter, 323))
        assertEquals(allowAction, evalBpf(filter, 1000))
    }

    @Test
    fun `test getJvmCriticalNrs explicitly and unconditionally contains signal handling syscalls`() {
        for (a in listOf(Arch.AMD64, Arch.AARCH64)) {
            val criticalNrs = BpfFilter.getJvmCriticalNrs(a)
            val rtSigprocmaskNr = Syscall.RT_SIGPROCMASK.numberFor(a)
            val rtSigactionNr = Syscall.RT_SIGACTION.numberFor(a)
            val rtSigreturnNr = Syscall.RT_SIGRETURN.numberFor(a)

            if (rtSigprocmaskNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigprocmaskNr), "JVM critical NRs for $a must contain rt_sigprocmask")
            }
            if (rtSigactionNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigactionNr), "JVM critical NRs for $a must contain rt_sigaction")
            }
            if (rtSigreturnNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigreturnNr), "JVM critical NRs for $a must contain rt_sigreturn")
            }
        }
    }
}
