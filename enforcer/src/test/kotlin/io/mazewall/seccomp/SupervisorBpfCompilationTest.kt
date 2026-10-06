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
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SupervisorBpfCompilationTest {
    private val arch = Arch.AMD64

    @ParameterizedTest
    @ValueSource(strings = ["amd64", "aarch64"])
    fun `unsupported supervised operations are rejected during compilation`(architecture: String) {
        val arch = if (architecture == "amd64") Arch.AMD64 else Arch.AARCH64
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).addAction(SeccompAction.ACT_NOTIFY, Syscall.UNLINKAT).build()
        assertFailsWith<IllegalArgumentException> { BpfFilter.build(arch, policy.definition) }
    }

    @Test
    fun `default notify cannot dispatch unhandled operations`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).defaultAction(SeccompAction.ACT_NOTIFY).build()
        assertFailsWith<IllegalArgumentException> { BpfFilter.build(arch, policy.definition) }
    }

    @Test
    fun `profiler can observe operations without enforcement handlers`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).addAction(SeccompAction.ACT_NOTIFY, Syscall.UNLINKAT).build()
        BpfFilter.build(arch, policy.definition, profilingMode = true)
    }

    @Test
    fun `policy builder with supervise produces notify action in bpf filter`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .addAction(io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY, Syscall.OPENAT)
            .addAction(io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY, Syscall.CONNECT)
            .build()

        assertTrue(policy.definition.hasSupervisedSyscalls)

        val program = BpfFilter.build(arch, policy.definition)
        val filter = program.instructions

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
