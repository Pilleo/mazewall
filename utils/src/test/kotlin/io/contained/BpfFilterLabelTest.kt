package io.contained

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BpfFilterLabelTest {

    private val amd64 = Arch.AMD64
    private val aarch64 = Arch.AARCH64

    @Test
    fun `label-based filter produces identical bytecode to manual layout for NO_EXEC on amd64`() {
        val filter = BpfFilter.build(amd64, Policy.NO_EXEC)
        val blocked = Policy.NO_EXEC.blockedSyscalls(amd64)
        val n = blocked.size

        assertEquals(n + 6, filter.size)

        assertEquals(0x20.toShort(), filter[0].code)
        assertEquals(4, filter[0].k)

        assertEquals(0x15.toShort(), filter[1].code)
        assertEquals(Arch.AUDIT_ARCH_X86_64, filter[1].k)
        assertEquals(0, filter[1].jt)
        assertEquals((n + 3).toByte(), filter[1].jf)

        assertEquals(0x20.toShort(), filter[2].code)
        assertEquals(0, filter[2].k)

        assertEquals(0x25.toShort(), filter[3].code)
        assertEquals(amd64.limit, filter[3].k)
        assertEquals((n + 1).toByte(), filter[3].jt)
        assertEquals(0, filter[3].jf)

        for (i in 0 until n) {
            val insn = filter[4 + i]
            assertEquals(0x15.toShort(), insn.code, "insn[${4 + i}] opcode")
            assertEquals(blocked[i], insn.k, "insn[${4 + i}] syscall nr")
            assertEquals((n - i).toByte(), insn.jt, "insn[${4 + i}] jt offset")
            assertEquals(0, insn.jf, "insn[${4 + i}] jf offset")
        }

        assertEquals(0x06.toShort(), filter[4 + n].code)
        assertEquals(LinuxNative.SECCOMP_RET_ALLOW, filter[4 + n].k)

        assertEquals(0x06.toShort(), filter[5 + n].code)
        assertEquals(LinuxNative.SECCOMP_RET_ERRNO or LinuxNative.EPERM, filter[5 + n].k)
    }

    @Test
    fun `label-based filter produces identical bytecode for NO_NETWORK on amd64`() {
        val filter = BpfFilter.build(amd64, Policy.NO_NETWORK)
        val blocked = Policy.NO_NETWORK.blockedSyscalls(amd64)
        val n = blocked.size

        assertEquals(n + 6, filter.size)

        for (i in 0 until n) {
            val insn = filter[4 + i]
            assertEquals(0x15.toShort(), insn.code)
            assertEquals(blocked[i], insn.k)
            assertEquals((n - i).toByte(), insn.jt)
            assertEquals(0, insn.jf)
        }
    }

    @Test
    fun `label-based filter produces identical bytecode for PURE_COMPUTE on amd64`() {
        val filter = BpfFilter.build(amd64, Policy.PURE_COMPUTE)
        val blocked = Policy.PURE_COMPUTE.blockedSyscalls(amd64)
        val n = blocked.size

        assertEquals(n + 6, filter.size)

        for (i in 0 until n) {
            val insn = filter[4 + i]
            assertEquals(0x15.toShort(), insn.code)
            assertEquals(blocked[i], insn.k)
            assertEquals((n - i).toByte(), insn.jt)
            assertEquals(0, insn.jf)
        }
    }

    @Test
    fun `label-based filter works for custom policy with memfd_create`() {
        val policy = Policy.builder()
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.MEMFD_CREATE)
            .build()
        val filter = BpfFilter.build(amd64, policy)
        val blocked = policy.blockedSyscalls(amd64)
        val n = blocked.size

        assertEquals(3, n)
        assertEquals(n + 6, filter.size)

        for (i in 0 until n) {
            val insn = filter[4 + i]
            assertEquals(0x15.toShort(), insn.code)
            assertEquals(blocked[i], insn.k)
            assertEquals((n - i).toByte(), insn.jt)
            assertEquals(0, insn.jf)
        }
    }

    @Test
    fun `label-based filter works on aarch64 for NO_EXEC`() {
        val filter = BpfFilter.build(aarch64, Policy.NO_EXEC)
        val blocked = Policy.NO_EXEC.blockedSyscalls(aarch64)
        val n = blocked.size

        assertEquals(n + 6, filter.size)

        assertEquals(0x15.toShort(), filter[1].code)
        assertEquals(Arch.AUDIT_ARCH_AARCH64, filter[1].k)
        assertEquals(0, filter[1].jt)
        assertEquals((n + 3).toByte(), filter[1].jf)

        assertEquals(0x25.toShort(), filter[3].code)
        assertEquals(aarch64.limit, filter[3].k)
        assertEquals((n + 1).toByte(), filter[3].jt)
        assertEquals(0, filter[3].jf)
    }

    @Test
    fun `all jump targets land on valid instructions for NO_EXEC`() {
        val filter = BpfFilter.build(amd64, Policy.NO_EXEC)
        val n = Policy.NO_EXEC.blockedSyscalls(amd64).size

        val allowIdx = 4 + n
        val denyIdx = 5 + n

        val archJfTarget = 1 + filter[1].jf + 1
        assertEquals(denyIdx, archJfTarget, "Arch mismatch jump must land on DENY")

        val rangeJtTarget = 3 + filter[3].jt + 1
        assertEquals(denyIdx, rangeJtTarget, "Range check jump must land on DENY")

        for (i in 0 until n) {
            val insn = filter[4 + i]
            val jtTarget = (4 + i) + insn.jt + 1
            assertEquals(denyIdx, jtTarget, "Blocked syscall $i jt must land on DENY")
        }
    }

    @Test
    fun `all jump targets valid for custom multi-syscall policy`() {
        val policy = Policy.builder()
            .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.CONNECT, Syscall.SENDTO, Syscall.MEMFD_CREATE)
            .build()
        val filter = BpfFilter.build(amd64, policy)
        val n = policy.blockedSyscalls(amd64).size
        val denyIdx = 5 + n

        for (i in 0 until n) {
            val insn = filter[4 + i]
            val jtTarget = (4 + i) + insn.jt + 1
            assertEquals(denyIdx, jtTarget, "Blocked syscall $i jt must land on DENY")
        }
    }
}
