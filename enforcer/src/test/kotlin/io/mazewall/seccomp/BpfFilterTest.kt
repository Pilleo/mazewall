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
        val hasArchLoad = filter.any { it.code == 0x20.toShort() && it.k == 4 }
        assertTrue(hasArchLoad, "Filter should contain instruction to load architecture audit ID")

        // Find JEQ AUDIT_ARCH_X86_64
        val hasArchCheck = filter.any { it.code == 0x15.toShort() && it.k == Arch.AUDIT_ARCH_X86_64 }
        assertTrue(hasArchCheck, "Filter should contain check for X86_64 architecture")
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
}
