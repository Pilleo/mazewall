package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.SupervisedKind
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpfFilterTest {
    private val arch = Arch.AMD64

    @Test
    fun `filter contains arch check`() {
        val filter = BpfFilter.build(arch, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition).instructions

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
        val filter = BpfFilter.build(arch, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition).instructions
        // Load syscall NR (LD W ABS 0)
        val hasSyscallLoad = filter.any { it.code == 0x20.toShort() && it.k == 0 }
        assertTrue(hasSyscallLoad, "Filter should contain instruction to load syscall number")
    }

    @Test
    fun `empty policy allows all syscalls`() {
        val filter = BpfFilter.build(arch, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition).instructions
        // The last instruction should be RET ALLOW
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code, "Last instruction should be RET")
        assertEquals(NativeConstants.SECCOMP_RET_ALLOW, last.k, "Last instruction should return ALLOW")
    }

    @Test
    fun `ALLOW_LIST mode has RET DENY as default`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(
            io.mazewall.platform.syscall.SeccompAction
            .ACT_ERRNO(),
        ).build()
        val filter = BpfFilter.build(arch, policy.definition).instructions
        val last = filter.last()
        assertEquals(0x06.toShort(), last.code)
        assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, last.k)
    }

    @Test
    fun `ALLOW_LIST mode generates RET ALLOW for listed syscalls`() {
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .defaultAction(
                    io.mazewall.platform.syscall.SeccompAction
                    .ACT_ERRNO(),
                ).allow(Syscall.READ)
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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(
            io.mazewall.platform.syscall.SeccompAction
            .ACT_ERRNO(),
        ).build()
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
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).unblock(Syscall.MMAP).build() // NO_EXEC by default blocks mmap exec
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
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build() // NO_EXEC by default protects clone
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
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build() // NO_EXEC protects prctl
        val filter = BpfFilter.build(arch, policy.definition).instructions

        val prctlNr = Syscall.PRCTL.numberFor(arch)
        var foundInspection = false
        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort() && f.k == prctlNr) {
                // EqualsAny32 (int ABI): loads ONLY args[0] LO (offset 16); high word ignored
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 16) {
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
                argument = SyscallArgument.SECOND,
                check = ArgCheck.MaskEquals(0x04L, 0x00L),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
            SyscallInspection(
                syscallNumber = 101,
                argument = SyscallArgument.THIRD,
                check = ArgCheck.MaskEquals(0x04L, 0x04L),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
        )
        val builder = BpfProgram
            .builder()
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
                argument = SyscallArgument.FIRST,
                check = ArgCheck.EqualsAny(listOf(5L)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
            SyscallInspection(
                syscallNumber = 201,
                argument = SyscallArgument.FIRST,
                check = ArgCheck.EqualsAny(listOf(10L, 20L)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
        )
        val builder = BpfProgram
            .builder()
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
                argument = SyscallArgument.FIRST,
                check = ArgCheck.MaskEquals(maskVal, maskVal),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
        )
        val builder = BpfProgram
            .builder()
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
                argument = SyscallArgument.FIRST,
                check = ArgCheck.EqualsAny(listOf(largeVal1, largeVal2)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
        )
        val builder = BpfProgram
            .builder()
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

        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
    private fun evalBpf(
        instructions: List<BpfInstruction>,
        syscallNr: Int,
    ): Int =
        requireNotNull(BpfSimulator.simulate(instructions, syscallNr, arch)) {
            "Compiled program fell through without RET for nr=$syscallNr"
        }

    @Test
    fun `allowUnsafePrctl emits a loud security warning`() {
        // issue-20260726_011928_05: compile-time acknowledgment for the TOCTOU-prone flag.
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(r: java.util.logging.LogRecord) {
                records += r
            }

            override fun flush() {}

            override fun close() {}
        }
        val root = java.util.logging.Logger
            .getLogger("")
        root.addHandler(handler)
        try {
            val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowUnsafePrctl().build()
            BpfFilter.build(arch, policy.definition)
        } finally {
            root.removeHandler(handler)
        }
        assertTrue(records.any { it.level == java.util.logging.Level.SEVERE && it.message.contains("allowUnsafePrctl") })
    }

    @Test
    fun `JA instructions encode skip count in k with zero jt and jf`() {
        // Regression: classic BPF JA jumps by K. Emitting the offset in jt (with k=0) makes the
        // filter fall through into the next RET block for every syscall.
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW)
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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
            val mmapNr = Syscall.MMAP.numberFor(a)
            val mprotectNr = Syscall.MPROTECT.numberFor(a)

            if (rtSigprocmaskNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigprocmaskNr), "JVM critical NRs for $a must contain rt_sigprocmask")
            }
            if (rtSigactionNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigactionNr), "JVM critical NRs for $a must contain rt_sigaction")
            }
            if (rtSigreturnNr >= 0) {
                assertTrue(criticalNrs.contains(rtSigreturnNr), "JVM critical NRs for $a must contain rt_sigreturn")
            }
            if (mmapNr >= 0) {
                assertTrue(criticalNrs.contains(mmapNr), "JVM critical NRs for $a must contain mmap")
            }
            if (mprotectNr >= 0) {
                assertTrue(criticalNrs.contains(mprotectNr), "JVM critical NRs for $a must contain mprotect")
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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .addAction(SeccompAction.ACT_ERRNO(99), Syscall.OPEN)
            .build()
        val filter = BpfFilter.build(arch, policy.definition).instructions

        val expectedErrno = NativeConstants.SECCOMP_RET_ERRNO or 99
        assertEquals(expectedErrno, evalBpf(filter, Syscall.OPEN.numberFor(arch)))
    }

    @Test
    fun `test ioctl is whitelisted in profilingMode`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
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
                // EqualsAny32 (int ABI): loads ONLY arg[0] LO (offset 16); high word ignored
                val ldArgs = filter[i + 1]
                if (ldArgs.code == 0x20.toShort() && ldArgs.k == 16) {
                    foundSocketInspection = true
                    break
                }
            }
        }
        assertTrue(foundSocketInspection, "BpfFilter should contain the socket address family argument inspection")
    }

    @Test
    fun `PURE_COMPUTE_UNSAFE traps creat on amd64 and filters unmapped nr on aarch64`() {
        // issue-20260821-113000: creat/truncate/ftruncate must be trapped so USER_NOTIF
        // profiling observes filesystem mutations. CREAT is x86_64-only (85); aarch64 uses
        // openat(O_CREAT), and BpfFilter filters unmapped NRs out of jump tables.
        val amd64 = BpfFilter.build(Arch.AMD64, io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE.definition).instructions
        assertEquals(
            NativeConstants.SECCOMP_RET_ERRNO or 1,
            evalBpf(amd64, Syscall.CREAT.numberFor(Arch.AMD64)),
            "creat(85) must be denied EPERM in PURE_COMPUTE_UNSAFE",
        )

        val aarch64Program = BpfFilter.build(Arch.AARCH64, io.mazewall.enforcer.api.Policy.PURE_COMPUTE_UNSAFE.definition).instructions
        assertTrue(aarch64Program.none { it is BpfInstruction.Jmp && it.k == -1 }, "unmapped NRs must be filtered from jump tables")
    }

    @Test
    fun `EqualsAny32 ignores high-word garbage on int arguments`() {
        // issue-075 problem 3: int ABI args (prctl option, socket family) may carry garbage in
        // the high 32 bits of the u64 seccomp_data slot; only the low word is meaningful.
        val builder = BpfProgram
            .builder()
            .checkArch(arch)
            .loadSyscallNr()
        val inspections = listOf(
            SyscallInspection(
                syscallNumber = arch.prctl,
                argument = SyscallArgument.FIRST,
                check = ArgCheck.EqualsAny32(listOf(15)),
                ifMatched = SeccompAction.ACT_ALLOW,
                ifNotMatched = SeccompAction.ACT_ERRNO(),
            ),
        )
        BpfFilter.emitInspections(builder, inspections, profilingMode = false, handledNrs = mutableSetOf())
        val program = builder.allow().build()
        BpfStaticVerifier.verify(program)
        val instructions = program.instructions

        val optionWithGarbageHighWord = (0xDEADBEEFL shl 32) or 15L
        val wrongOptionWithGarbageHighWord = (0xFEEDFACE shl 32) or 16L

        assertEquals(
            NativeConstants.SECCOMP_RET_ALLOW,
            BpfSimulator.simulate(instructions, arch.prctl, arch.audit, longArrayOf(optionWithGarbageHighWord)),
            "low-word match with garbage high word must be ALLOWED",
        )
        assertEquals(
            NativeConstants.SECCOMP_RET_ERRNO or 1,
            BpfSimulator.simulate(instructions, arch.prctl, arch.audit, longArrayOf(wrongOptionWithGarbageHighWord)),
            "low-word mismatch must be denied regardless of high word",
        )
    }

    @Test
    fun `supervised exec preserves inherited definite spawn denials`() {
        val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(
            defaultAction = SeccompAction.ACT_ERRNO(NativeConstants.EACCES),
            syscallActions = mapOf(Syscall.EXECVE to SeccompAction.ACT_NOTIFY),
        )
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val program = BpfFilter.build(arch, policy).instructions
            for (nr in listOf(arch.fork, arch.vfork, arch.clone).filter { it >= 0 }) {
                val action = BpfSimulator.simulate(program, nr, arch.audit, LongArray(6))
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES, action, "spawn $nr on $arch")
            }
        }
    }

    @Test
    fun `spawn denial leaves JVM clone floor and profiling behavior unchanged`() {
        val denial = SeccompAction.ACT_ERRNO(NativeConstants.EACCES)
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = denial, syscallActions = mapOf(Syscall.EXECVE to SeccompAction.ACT_NOTIFY))
            val program = BpfFilter.build(arch, policy).instructions
            val thread = LongArray(6).also { it[0] = 0x10000 }
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, BpfSimulator.simulate(program, arch.clone, arch.audit, thread))
            assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES, BpfSimulator.simulate(program, arch.clone, arch.audit, LongArray(6)))
            assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.ENOSYS, BpfSimulator.simulate(program, arch.clone3, arch))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, BpfSimulator.simulate(program, Syscall.FUTEX.numberFor(arch), arch))
            for (defaultAction in listOf(denial, SeccompAction.ACT_KILL_PROCESS, SeccompAction.ACT_KILL_THREAD, SeccompAction.ACT_TRAP, SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
                val profilePolicy = policy.copy(defaultAction = defaultAction)
                val profile = BpfFilter.build(arch, profilePolicy, profilingMode = true).instructions
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, BpfSimulator.simulate(profile, arch.clone, arch.audit, LongArray(6)))
            }
        }
    }

    @Test
    fun `spawn propagation preserves non-denial behavior and explicit actions`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            for (exec in listOf(Syscall.EXECVE, Syscall.EXECVEAT)) {
            val spawn = listOf(Syscall.FORK, Syscall.VFORK, Syscall.CLONE).filter { it.numberFor(arch) >= 0 }
            for (syscall in spawn) {
                val nr = syscall.numberFor(arch)

                fun verdict(
                    default: SeccompAction,
                    explicit: SeccompAction?,
                ): Int? {
                    val actions = mutableMapOf<Syscall, SeccompAction>(exec to SeccompAction.ACT_NOTIFY)
                    if (explicit != null) actions[syscall] = explicit
                    val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = default, syscallActions = actions)
                    return BpfSimulator.simulate(BpfFilter.build(arch, policy).instructions, nr, arch.audit, LongArray(6))
                }
                for ((action, expected) in listOf(
                    SeccompAction.ACT_KILL_PROCESS to NativeConstants.SECCOMP_RET_KILL_PROCESS,
                    SeccompAction.ACT_KILL_THREAD to NativeConstants.SECCOMP_RET_KILL_THREAD,
                    SeccompAction.ACT_TRAP to NativeConstants.SECCOMP_RET_TRAP,
                    SeccompAction.ACT_ERRNO(NativeConstants.EACCES) to (NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES),
                )) {
                    assertEquals(expected, verdict(action, null))
                    assertEquals(expected, verdict(SeccompAction.ACT_ALLOW, action))
                }
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, verdict(SeccompAction.ACT_ALLOW, null))
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, verdict(SeccompAction.ACT_ERRNO(), SeccompAction.ACT_ALLOW))
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, verdict(SeccompAction.ACT_ALLOW, SeccompAction.ACT_NOTIFY))
                assertFailsWith<IllegalArgumentException> { verdict(SeccompAction.ACT_LOG, null) }
                assertFailsWith<IllegalArgumentException> { verdict(SeccompAction.ACT_TRACE(37), null) }
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES, verdict(SeccompAction.ACT_ALLOW, SeccompAction.ACT_ERRNO(NativeConstants.EACCES)))
                assertEquals(NativeConstants.SECCOMP_RET_TRACE or 37, verdict(SeccompAction.ACT_ALLOW, SeccompAction.ACT_TRACE(37)))
                assertEquals(NativeConstants.SECCOMP_RET_LOG, verdict(SeccompAction.ACT_ALLOW, SeccompAction.ACT_LOG))
            }
        }
        }
    }

    @Test
    fun `spawn resolver identity and architecture availability are exact`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val symbols = listOf(Syscall.FORK, Syscall.VFORK, Syscall.CLONE, Syscall.EXECVE, Syscall.EXECVEAT)
            val resolved = symbols.map { it.numberFor(arch) }
            assertEquals(listOf(arch.fork, arch.vfork, arch.clone, arch.execve, arch.execveat), resolved)
            val present = resolved.filter { it >= 0 }
            assertEquals(present.size, present.toSet().size)
        }
        assertEquals(-1, Syscall.FORK.numberFor(Arch.AARCH64))
        assertEquals(-1, Syscall.VFORK.numberFor(Arch.AARCH64))
    }

    @Test
    fun `compiler rejects inherited audit spawn only in enforcement`() {
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            for (action in listOf(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
                for (exec in listOf(Syscall.EXECVE, Syscall.EXECVEAT)) {
                    val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = action, syscallActions = mapOf(exec to SeccompAction.ACT_NOTIFY))
                    assertFailsWith<IllegalArgumentException> { BpfFilter.build(arch, policy) }
                    BpfFilter.build(arch, policy, profilingMode = true)
                    val explicit = policy.copy(
                        syscallActions = policy.syscallActions +
                            listOf(Syscall.FORK, Syscall.VFORK, Syscall.CLONE).associateWith { SeccompAction.ACT_TRACE(37) },
                    )
                    BpfFilter.build(arch, explicit)
                }
            }
        }
    }

    @Test
    fun `supervisable syscall check matches SupervisedKind for every arch and syscall`() {
        val pipeline = BpfFilter.getSyscallInspectionPipeline()
        for (arch in listOf(Arch.AMD64, Arch.AARCH64)) {
            val candidateNumbers = Syscall.entries.map { it.numberFor(arch) }.toSet() + setOf(-1, 9999)
            for (nr in candidateNumbers) {
                val expectedSupervisable = SupervisedKind.classify(SyscallNumber(nr), arch) != SupervisedKind.Unknown
                val actions = mapOf(nr to SeccompAction.ACT_NOTIFY)
                if (expectedSupervisable) {
                    BpfFilter.buildFromActions(arch, actions, SeccompAction.ACT_ALLOW, pipeline, profilingMode = false)
                } else {
                    val failure = assertFailsWith<IllegalArgumentException> {
                        BpfFilter.buildFromActions(arch, actions, SeccompAction.ACT_ALLOW, pipeline, profilingMode = false)
                    }
                    assertTrue(failure.message.orEmpty().contains("has no supervisor handler"))
                }
            }
        }
    }

    @Test
    fun `compiled renameat verdicts enforce explicit deny and allow on AARCH64 and AMD64`() {
        data class RenameatCase(
            val arch: Arch,
            val renameatNr: Int,
            val renameat2Nr: Int,
            val readNr: Int,
        )

        val cases =
            listOf(
                RenameatCase(Arch.AARCH64, renameatNr = 38, renameat2Nr = 276, readNr = 63),
                RenameatCase(Arch.AMD64, renameatNr = 264, renameat2Nr = 316, readNr = 0),
            )
        for ((arch, renameatNr, renameat2Nr, readNr) in cases) {
            for (errno in listOf(NativeConstants.EPERM, NativeConstants.EACCES)) {
                val explicitDenyPolicy =
                    PolicyDefinition<PolicyScope.ProcessWideSafe>(
                        defaultAction = SeccompAction.ACT_ALLOW,
                        syscallActions = mapOf(Syscall.RENAMEAT to SeccompAction.ACT_ERRNO(errno)),
                    )
                val denyProgram = BpfFilter.build(arch, explicitDenyPolicy).instructions
                assertEquals(
                    NativeConstants.SECCOMP_RET_ERRNO or errno,
                    BpfSimulator.simulate(denyProgram, renameatNr, arch),
                    "default-ALLOW explicit deny RENAMEAT ($renameatNr) on ${arch.name}",
                )
                assertEquals(
                    NativeConstants.SECCOMP_RET_ALLOW,
                    BpfSimulator.simulate(denyProgram, renameat2Nr, arch),
                    "RENAMEAT2 ($renameat2Nr) should remain ALLOW on ${arch.name}",
                )
                assertEquals(
                    NativeConstants.SECCOMP_RET_ALLOW,
                    BpfSimulator.simulate(denyProgram, readNr, arch),
                    "READ ($readNr) should remain ALLOW on ${arch.name}",
                )
            }

            val explicitAllowPolicy =
                PolicyDefinition<PolicyScope.ProcessWideSafe>(
                    defaultAction = SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                    syscallActions = mapOf(Syscall.RENAMEAT to SeccompAction.ACT_ALLOW),
                )
            val allowProgram = BpfFilter.build(arch, explicitAllowPolicy).instructions
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(allowProgram, renameatNr, arch),
                "default-DENY explicit allow RENAMEAT ($renameatNr) on ${arch.name}",
            )
            assertEquals(
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
                BpfSimulator.simulate(allowProgram, renameat2Nr, arch),
                "default-DENY unlisted RENAMEAT2 ($renameat2Nr) on ${arch.name}",
            )

            val built =
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .block(Syscall.RENAMEAT)
                    .build()
                    .definition
                    .compile(arch)
            assertEquals(
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
                BpfSimulator.simulate(built.program.instructions, renameatNr, arch),
                "ProcessPolicyBuilder block(RENAMEAT) on ${arch.name}",
            )
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(built.program.instructions, readNr, arch),
                "ProcessPolicyBuilder block(RENAMEAT) leaves READ allowed on ${arch.name}",
            )
        }
    }

    @Test
    fun `compiled filesystem-blocking presets deny renameat on AARCH64 and AMD64`() {
        val eperm = NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM
        val presets = listOf(PolicyPresets.PURE_COMPUTE_UNSAFE, PolicyPresets.NO_EXEC_NO_FS_WRITE, PolicyPresets.DEFAULT_SAFE)
        for (preset in presets) {
            val aarch64Program = BpfFilter.build(Arch.AARCH64, preset).instructions
            assertEquals(eperm, BpfSimulator.simulate(aarch64Program, 38, Arch.AARCH64))
            assertEquals(eperm, BpfSimulator.simulate(aarch64Program, 276, Arch.AARCH64))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, BpfSimulator.simulate(aarch64Program, 63, Arch.AARCH64))

            val amd64Program = BpfFilter.build(Arch.AMD64, preset).instructions
            assertEquals(eperm, BpfSimulator.simulate(amd64Program, 82, Arch.AMD64))
            assertEquals(eperm, BpfSimulator.simulate(amd64Program, 264, Arch.AMD64))
            assertEquals(eperm, BpfSimulator.simulate(amd64Program, 316, Arch.AMD64))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, BpfSimulator.simulate(amd64Program, 0, Arch.AMD64))
        }
    }
}
