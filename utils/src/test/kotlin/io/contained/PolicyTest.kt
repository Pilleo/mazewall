package io.contained

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PolicyTest {

    @Test
    fun `blockedSyscalls returns sorted array`() {
        val policy = Policy.builder()
            .block(Syscall.SENDTO, Syscall.ACCEPT, Syscall.CONNECT, Syscall.BIND)
            .build()
        
        val blocked = policy.blockedSyscalls(Arch.current())
        val sorted = blocked.sortedArray()
        
        assertTrue(blocked.contentEquals(sorted), "blockedSyscalls should return a sorted array")
    }

    @Test
    fun `NO_NETWORK policy includes all network server syscalls`() {
        val policy = Policy.NO_NETWORK
        val arch = Arch.current()
        val blocked = policy.blockedSyscalls(arch).toList()

        assertTrue(blocked.contains(arch.bind), "NO_NETWORK should block bind")
        assertTrue(blocked.contains(arch.listen), "NO_NETWORK should block listen")
        assertTrue(blocked.contains(arch.accept), "NO_NETWORK should block accept")
        assertTrue(blocked.contains(arch.accept4), "NO_NETWORK should block accept4")
        assertTrue(blocked.contains(arch.connect), "NO_NETWORK should block connect")
    }
}
