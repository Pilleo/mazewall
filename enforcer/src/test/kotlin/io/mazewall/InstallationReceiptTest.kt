package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallationReceiptTest {
    @Test
    fun `closing a receipt retires its supervisor registration once`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        var closes = 0
        val receipt = InstallationReceipt.withSupervisorRegistration(false, policy, AutoCloseable { closes++ })

        receipt.use { assertTrue(it.installed) }
        receipt.close()

        assertEquals(1, closes)
    }

    @Test
    fun `receipt defaults to installed status`() {
        val receipt = InstallationReceipt(false, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)

        assertTrue(receipt.installed)
        assertEquals(false, receipt.landlockApplied)
    }

    @Test
    fun `receipt can report Landlock without a seccomp install`() {
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition,
                installed = false,
                landlockApplied = true,
            )
        assertEquals(false, receipt.installed)
        assertEquals(true, receipt.landlockApplied)
    }

    @Test
    fun `unsupervised receipts can be released by fire-and-forget install APIs`() {
        val receipt = InstallationReceipt(false, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)
        receipt.releaseIfUnsupervised()
    }

    @Test
    fun `supervised receipts cannot be dropped by fire-and-forget install APIs`() {
        val receipt =
            InstallationReceipt.withSupervisorRegistration(
                false,
                ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition,
                AutoCloseable { },
            )
        kotlin.test.assertFailsWith<IllegalStateException> { receipt.releaseIfUnsupervised() }
        receipt.close()
    }
}
