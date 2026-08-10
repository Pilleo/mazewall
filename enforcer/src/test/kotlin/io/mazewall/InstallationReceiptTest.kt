package io.mazewall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationReceiptTest {
    @Test
    fun `receipt data operations retain installation outcome`() {
        val policy = Policy.builder().build().definition
        val session = AutoCloseable {}
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = policy,
                supervisorSession = session,
                timestampMillis = 123L,
                outcome = InstallationOutcome.BYPASSED,
            )

        assertEquals(false, receipt.component1())
        assertSame(policy, receipt.component2())
        assertSame(session, receipt.component3())
        assertEquals(123L, receipt.component4())
        assertEquals(InstallationOutcome.BYPASSED, receipt.component5())

        val copy = receipt.copy()
        assertEquals(receipt, copy)
        assertEquals(receipt.hashCode(), copy.hashCode())
        assertTrue(copy.toString().contains("outcome=BYPASSED"))
        assertNotEquals(receipt, receipt.copy(outcome = InstallationOutcome.INSTALLED))
    }

    @Test
    fun `receipt defaults to installed outcome`() {
        val receipt = InstallationReceipt(false, Policy.builder().build().definition)

        assertEquals(InstallationOutcome.INSTALLED, receipt.outcome)
        assertEquals(InstallationOutcome.BYPASSED, InstallationOutcome.valueOf("BYPASSED"))
        assertEquals(
            listOf(InstallationOutcome.INSTALLED, InstallationOutcome.BYPASSED),
            InstallationOutcome.entries,
        )
    }
}
