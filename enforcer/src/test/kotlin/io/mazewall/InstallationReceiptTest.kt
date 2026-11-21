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

    @Test
    fun `installed receipt cannot carry a failure`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InstallationReceipt(
                processWide = false,
                requestedPolicy = policy,
                installed = true,
                failure = RuntimeException("boom"),
            )
        }
    }

    @Test
    fun `supervised installed receipt cannot carry a failure and leaves registration uninvoked`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        var closes = 0
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InstallationReceipt.withSupervisorRegistration(
                processWide = false,
                requestedPolicy = policy,
                supervisorRegistration = AutoCloseable { closes++ },
                installed = true,
                failure = RuntimeException("boom"),
            )
        }
        assertEquals(0, closes)
    }

    @Test
    fun `outcome factories project compatibility fields and rethrow exact failures`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        val rootCause = IllegalStateException("root")
        val failedCause = PartialInstallationException(InstallationProgress(false, landlockApplied = true, seccompApplied = false), rootCause)
        val failed =
            InstallationReceipt.failed(
                processWide = false,
                requestedPolicy = policy,
                cause = failedCause,
                landlockApplied = true,
                progress = failedCause.progress,
            )
        assertTrue(failed.outcome is InstallationReceipt.Outcome.Failed)
        assertEquals(false, failed.installed)
        assertEquals(true, failed.landlockApplied)
        assertEquals(failedCause.progress, failed.progress)
        kotlin.test.assertSame(failedCause, failed.failure)
        val thrownFailure = kotlin.test.assertFailsWith<PartialInstallationException> { failed.requireInstalled() }
        kotlin.test.assertSame(failedCause, thrownFailure)
        kotlin.test.assertSame(rootCause, thrownFailure.cause)

        val bypassReason = UnsupportedOperationException("unsupported landlock")
        val bypassProgress = InstallationProgress(false, landlockApplied = false, seccompApplied = false)
        val bypassed =
            InstallationReceipt.bypassed(
                processWide = false,
                requestedPolicy = policy,
                reason = bypassReason,
                landlockApplied = true,
                progress = bypassProgress,
            )
        assertTrue(bypassed.outcome is InstallationReceipt.Outcome.Bypassed)
        assertEquals(false, bypassed.installed)
        assertEquals(true, bypassed.landlockApplied)
        assertEquals(bypassProgress, bypassed.progress)
        kotlin.test.assertSame(bypassReason, bypassed.failure)
        val thrownBypass = kotlin.test.assertFailsWith<UnsupportedOperationException> { bypassed.requireInstalled() }
        kotlin.test.assertSame(bypassReason, thrownBypass)
    }

    @Test
    fun `requireInstalled rejection preserves supervisor lease until close`() {
        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .filesystem(FileSystemPolicy.DenyAll)
                .lockIntelCet()
                .build()
                .definition

        var failedCloses = 0
        val cause = IllegalStateException("seccomp failed")
        val failedReceipt =
            InstallationReceipt.failed(
                processWide = false,
                requestedPolicy = policy,
                cause = cause,
                landlockApplied = true,
                supervisorRegistration = AutoCloseable { failedCloses++ },
            )
        assertTrue(failedReceipt.ownsSupervisorLease)
        kotlin.test.assertSame(
            cause,
            kotlin.test.assertFailsWith<IllegalStateException> {
                failedReceipt.use { it.requireInstalled() }
            },
        )
        assertEquals(1, failedCloses)
        assertEquals(false, failedReceipt.ownsSupervisorLease)

        var missingLandlockCloses = 0
        val missingLandlock =
            InstallationReceipt.installed(
                processWide = false,
                requestedPolicy = policy,
                landlockApplied = false,
                cetOutcome = CetOutcome.VERIFIED_CURRENT_THREAD,
                supervisorRegistration = AutoCloseable { missingLandlockCloses++ },
            )
        kotlin.test.assertFailsWith<IllegalStateException> {
            missingLandlock.use { it.requireInstalled() }
        }
        assertEquals(1, missingLandlockCloses)

        var missingCetCloses = 0
        val missingCet =
            InstallationReceipt.installed(
                processWide = false,
                requestedPolicy = policy,
                landlockApplied = true,
                cetOutcome = CetOutcome.UNCONFIRMED,
                supervisorRegistration = AutoCloseable { missingCetCloses++ },
            )
        kotlin.test.assertFailsWith<IllegalStateException> {
            missingCet.use { it.requireInstalled() }
        }
        assertEquals(1, missingCetCloses)
    }

    @Test
    fun `inherited filesystem restriction without newly applied stages satisfies requireInstalled`() {
        val policy =
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .filesystem(FileSystemPolicy.DenyAll)
                .build()
                .definition
        val progress = InstallationProgress(false, landlockApplied = false, seccompApplied = false)
        val receipt =
            InstallationReceipt.installed(
                processWide = false,
                requestedPolicy = policy,
                landlockApplied = true,
                progress = progress,
            )
        assertTrue(receipt.outcome is InstallationReceipt.Outcome.Installed)
        kotlin.test.assertSame(receipt, receipt.requireInstalled())
        assertEquals(progress, receipt.progress)
    }

    @Test
    fun `close detaches registration once even when registration close throws`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition
        var closes = 0
        val closeError = IllegalStateException("close failure")
        val receipt =
            InstallationReceipt.withSupervisorRegistration(
                processWide = false,
                requestedPolicy = policy,
                supervisorRegistration =
                    AutoCloseable {
                        closes++
                        throw closeError
                    },
            )
        kotlin.test.assertSame(closeError, kotlin.test.assertFailsWith<IllegalStateException> { receipt.close() })
        assertEquals(false, receipt.ownsSupervisorLease)
        receipt.close()
        assertEquals(1, closes)
    }
}
