package io.mazewall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationReceiptTest {
    @Test
    fun `receipt data operations retain installation status`() {
        val policy = Policy.builder().build().definition
        val session = AutoCloseable {}
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = policy,
                supervisorSession = session,
                timestampMillis = 123L,
                installed = false,
            )

        assertEquals(false, receipt.component1())
        assertSame(policy, receipt.component2())
        assertSame(session, receipt.component3())
        assertEquals(123L, receipt.component4())
        assertEquals(false, receipt.component5())

        val copy = receipt.copy()
        assertEquals(receipt, copy)
        assertEquals(receipt.hashCode(), copy.hashCode())
        assertTrue(copy.toString().contains("installed=false"))
        assertNotEquals(receipt, receipt.copy(installed = true))
    }

    @Test
    fun `receipt defaults to installed status`() {
        val receipt = InstallationReceipt(false, Policy.builder().build().definition)

        assertTrue(receipt.installed)
        assertEquals(false, receipt.landlockApplied)
    }

    @Test
    fun `receipt can report Landlock without a seccomp install`() {
        val receipt =
            InstallationReceipt(
                processWide = false,
                requestedPolicy = Policy.builder().build().definition,
                installed = false,
                landlockApplied = true,
            )
        assertEquals(false, receipt.installed)
        assertEquals(true, receipt.landlockApplied)
    }

    @Test
    fun `receipt retains the pre-outcome JVM constructor`() {
        val constructor =
            InstallationReceipt::class.java.getConstructor(
                Boolean::class.javaPrimitiveType,
                PolicyDefinition::class.java,
                AutoCloseable::class.java,
                Long::class.javaPrimitiveType,
            )

        val receipt =
            constructor.newInstance(
                false,
                Policy.builder().build().definition,
                null,
                123L,
            )

        assertTrue(receipt.installed)
    }
}
