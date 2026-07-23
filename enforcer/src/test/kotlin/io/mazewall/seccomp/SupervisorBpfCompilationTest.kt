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
            .addAction(io.mazewall.core.SeccompAction.ACT_NOTIFY, Syscall.OPENAT)
            .addAction(io.mazewall.core.SeccompAction.ACT_NOTIFY, Syscall.CONNECT)
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
                    val targetIdx = i + f.jt + 1
                    val targetInsn = filter[targetIdx]
                    if (targetInsn.code == 0x06.toShort() && targetInsn.k == NativeConstants.SECCOMP_RET_USER_NOTIF) {
                        foundOpenatNotify = true
                    }
                }
                if (f.k == connectNr) {
                    val targetIdx = i + f.jt + 1
                    val targetInsn = filter[targetIdx]
                    if (targetInsn.code == 0x06.toShort() && targetInsn.k == NativeConstants.SECCOMP_RET_USER_NOTIF) {
                        foundConnectNotify = true
                    }
                }
            }
        }

        assertTrue(foundOpenatNotify, "Filter should contain JEQ to RET USER_NOTIF for supervised openat")
        assertTrue(foundConnectNotify, "Filter should contain JEQ to RET USER_NOTIF for supervised connect")
    }
}
