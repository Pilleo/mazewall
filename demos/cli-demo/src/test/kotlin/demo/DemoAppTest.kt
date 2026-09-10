package demo

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoAppTest {
    @Test
    @EnabledIfLinuxAndSupported
    fun `unsafe and safe scenarios satisfy opposite explicit expectations`() {
        assertTrue(runUnsafe().passed)
        assertTrue(runSafe().passed)
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner handles malformed payload`() {
        // This won't trigger the JNDI logic, so it's a simple success path
        SafeRunner.run("not-jndi")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `SafeRunner blocks execution in JNDI`() {
        val failure = assertFailsWith<ContainmentViolationException> {
            SafeRunner.run($$"${jndi:ldap://test?cmd=ls}")
        }
        assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, ContainmentViolationDetector.diagnose(failure)?.evidence)
    }
}
