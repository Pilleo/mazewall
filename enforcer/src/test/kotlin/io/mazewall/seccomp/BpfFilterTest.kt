package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpfFilterTest {
    private val arch = Arch.AMD64

    @Test
    fun `filter contains arch check`() {
        val filter = BpfFilter.build(arch, Policy.builder().build().definition).instructions

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
        val filter = BpfFilter.build(arch, Policy.builder().build().definition).instructions
        // Load syscall NR (LD W ABS 0)
        val hasSyscallLoad = filter.any { it.code == 0x20.toShort() && it.k == 0 }
        assertTrue(hasSyscallLoad, "Filter should contain instruction to load syscall number")
    }

    @Test
    fun `empty policy allows all syscalls`() {
        val filter = BpfFilter.build(arch, Policy.builder().build().definition).instructions
        // The last instruction should be RET ALLOW
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code, "Last instruction should be RET")
        assertEquals(NativeConstants.SECCOMP_RET_ALLOW, last.k, "Last instruction should return ALLOW")
    }

    @Test
    fun `ALLOW_LIST mode has RET DENY as default`() {
        val policy = Policy.builder().defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO()).build()
        val filter = BpfFilter.build(arch, policy.definition).instructions
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code)
        assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, last.k)
    }

    @Test
    fun `ALLOW_LIST mode generates RET ALLOW for listed syscalls`() {
        val policy =
            Policy
                .builder()
                .defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO())
                .allow(Syscall.READ)
                .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

        // Simulate the filter for READ: an allow-list policy must return ALLOW (not fall through
        // to the default errno action, and not match any other NR).
        assertEquals(
            NativeConstants.SECCOMP_RET_ALLOW,
            evalBpf(filter, Syscall.READ.numberFor(arch)),
            "Filter should return ALLOW for listed syscall in ALLOW_LIST mode",
        )
        // Guard against accidental nr==0 aliasing: a different syscall must get the default action.
        assertEquals(
            327681, // SECCOMP_RET_ERRNO | EPERM(1)
            evalBpf(filter, Syscall.CONNECT.numberFor(arch)),
            "Unlisted syscall must receive the default errno action",
        )
    }

    @Test
    fun `clone3 always returns ENOSYS even in ALLOW_LIST`() {
        val policy = Policy.builder().defaultAction(io.mazewall.core.SeccompAction.ACT_ERRNO()).build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

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
        val filter = BpfFilter.build(arch, policy.definition).instructions

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
        val filter = BpfFilter.build(arch, policy.definition).instructions

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
        val filter = BpfFilter.build(arch, policy.definition).instructions

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
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
            SyscallInspection(
                syscallNumber = 101,
                argIndex = 2,
                check = ArgCheck.MaskEquals(0x04L, 0x04L),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
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
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
            SyscallInspection(
                syscallNumber = 201,
                argIndex = 0,
                check = ArgCheck.EqualsAny(listOf(10L, 20L)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
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
                ifNotMatched = SeccompAction.ACT_ERRNO(),
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
                ifNotMatched = SeccompAction.ACT_ERRNO(),
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

        val filter = BpfFilter.build(arch, policy.definition).instructions

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

    /**
     * Delegates to the platform reference interpreter — the single semantic oracle shared by
     * unit tests, differential kernel tests, and :profiler (issue-20260823-171500/172100).
     */
    private fun evalBpf(instructions: List<BpfInstruction>, syscallNr: Int): Int =
        requireNotNull(BpfSimulator.simulate(instructions, syscallNr, arch)) {
            "Compiled program fell through without RET for nr=$syscallNr"
        }

    @Test
    fun `JA instructions encode skip count in k with zero jt and jf`() {
        // Regression: classic BPF JA jumps by K. Emitting the offset in jt (with k=0) makes the
        // filter fall through into the next RET block for every syscall.
        val policy = Policy.builder()
            .defaultAction(io.mazewall.core.SeccompAction.ACT_ALLOW)
            .block(Syscall.CONNECT)
            .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions
        val jaInstructions = filter.filter { it.code == 0x05.toShort() }
        assertTrue(jaInstructions.isNotEmpty(), "Expected at least one JA jump in a blacklist filter")
        for (ja in jaInstructions) {
            assertEquals(0.toShort(), ja.jt, "JA jt must be zero")
            assertEquals(0.toShort(), ja.jf, "JA jf must be zero")
            assertTrue(ja.k > 0, "JA must carry its forward skip count in k")
        }
    }

    @Test
    fun `blacklist policy compiled with BST contains greater-than comparisons and routes correctly`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

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

            // arch_prctl must be whitelisted on every architecture that provides it (x86_64 only).
            val archPrctlNr = Syscall.ARCH_PRCTL.numberFor(a)
            if (archPrctlNr >= 0) {
                assertTrue(criticalNrs.contains(archPrctlNr), "JVM critical NRs for $a must contain arch_prctl")
            } else {
                assertFalse(criticalNrs.contains(archPrctlNr), "$a does not provide arch_prctl; it must not appear")
            }
        }
    }

    @Test
    fun `test ACT_TRACE dynamic action compilation and evaluation`() {
        val policy = Policy.builder()
            .addAction(SeccompAction.ACT_TRACE(1234), Syscall.EXECVE)
            .addAction(SeccompAction.ACT_TRACE(5678), Syscall.MEMFD_CREATE)
            .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

        val expectedTrace1 = NativeConstants.SECCOMP_RET_TRACE or 1234
        val expectedTrace2 = NativeConstants.SECCOMP_RET_TRACE or 5678

        assertEquals(expectedTrace1, evalBpf(filter, Syscall.EXECVE.numberFor(arch)))
        assertEquals(expectedTrace2, evalBpf(filter, Syscall.MEMFD_CREATE.numberFor(arch)))
    }

    @Test
    fun `test ACT_ERRNO dynamic action compilation with custom errno`() {
        val policy = Policy.builder()
            .addAction(SeccompAction.ACT_ERRNO(99), Syscall.OPEN)
            .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

        val expectedErrno = NativeConstants.SECCOMP_RET_ERRNO or 99
        assertEquals(expectedErrno, evalBpf(filter, Syscall.OPEN.numberFor(arch)))
    }

    @Test
    fun `test ioctl is whitelisted in profilingMode`() {
        val policy = Policy.builder()
            .block(Syscall.IOCTL)
            .build()
        val filter = BpfFilter.build(arch, policy.definition, profilingMode = true).instructions
        assertEquals(NativeConstants.SECCOMP_RET_ALLOW, evalBpf(filter, Syscall.IOCTL.numberFor(arch)))
    }

    @Test
    fun `test socket address family inspector allows AF_UNIX and blocks others under NO_NETWORK`() {
        // Policy.NO_NETWORK blocks Syscall.SOCKET, resulting in ACT_ERRNO by default
        val policy = Policy.NO_NETWORK
        val filter = BpfFilter.build(arch, policy.definition).instructions

        val socketNr = Syscall.SOCKET.numberFor(arch)
        assertTrue(socketNr >= 0, "socket syscall number must be valid")

        // Find the inspection block for Syscall.SOCKET
        // It checks if arg[0] equals AF_UNIX (1).
        // Let's verify by checking instructions structure for Syscall.SOCKET
        var foundSocketInspection = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == socketNr) {
                // Should load arg[0] HI (offset 16 + 4 = 20)
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 20) {
                    foundSocketInspection = true
                    break
                }
            }
        }
        assertTrue(foundSocketInspection, "BpfFilter should contain the socket address family argument inspection")
    }
}
