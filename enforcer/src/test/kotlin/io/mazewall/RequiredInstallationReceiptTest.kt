package io.mazewall.enforcer.api

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class RequiredInstallationReceiptTest {
    @Test
    fun `installed seccomp alone cannot satisfy requested filesystem restriction`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = true, landlockApplied = false)

        assertFailsWith<IllegalStateException> { receipt.requireInstalled() }
    }

    @Test
    fun `requested filesystem restriction and seccomp together satisfy required installation`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = true, landlockApplied = true)

        assertSame(receipt, receipt.requireInstalled())
    }

    @Test
    fun `filesystem restriction alone cannot satisfy failed seccomp installation`() {
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).build()
        val receipt = InstallationReceipt(false, policy.definition, installed = false, landlockApplied = true)

        assertFailsWith<IllegalStateException> { receipt.requireInstalled() }
    }
}
