package io.mazewall.seccomp

import io.mazewall.BpfFilter
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.Syscall
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupervisorBpfCompilationTest {
    private val arch = Arch.AMD64

    @Test
    fun `policy builder with supervise produces notify action in bpf filter`() {
        val policy = Policy.builder()
            .supervise(Syscall.OPENAT)
            .supervise(Syscall.CONNECT)
            .build()

        assertTrue(policy.definition.hasSupervisedSyscalls)

        val filter = BpfFilter.build(arch, policy.definition)

        // Verify that there is a check for OPENAT and CONNECT that jumps to RET USER_NOTIF
        val openatNr = Syscall.OPENAT.numberFor(arch)
        val connectNr = Syscall.CONNECT.numberFor(arch)

        var foundOpenatNotify = false
        var foundConnectNotify = false

        for (i in filter.indices) {
            val f = filter[i]
            if (f.code == 0x15.toShort()) { // JEQ
                if (f.k == openatNr) {
                    val next = filter[i + 1]
                    if (next.code == 0x06.toShort() && next.k == NativeConstants.SECCOMP_RET_USER_NOTIF) {
                        foundOpenatNotify = true
                    }
                }
                if (f.k == connectNr) {
                    val next = filter[i + 1]
                    if (next.code == 0x06.toShort() && next.k == NativeConstants.SECCOMP_RET_USER_NOTIF) {
                        foundConnectNotify = true
                    }
                }
            }
        }

        assertTrue(foundOpenatNotify, "Filter should contain JEQ to RET USER_NOTIF for supervised openat")
        assertTrue(foundConnectNotify, "Filter should contain JEQ to RET USER_NOTIF for supervised connect")
    }
}
