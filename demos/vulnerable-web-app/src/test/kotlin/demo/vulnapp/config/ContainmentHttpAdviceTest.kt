package demo.vulnapp.config

import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainmentHttpAdviceTest {
    @Test
    fun `known evidence remains explicit while an unknown origin never becomes a permission verdict`() {
        val advice = ContainmentHttpAdvice()
        for (evidence in ContainmentViolationEvidence.entries) {
            val response = advice.refused(ContainmentViolationException("Permission denied", java.io.IOException("Permission denied"), errno = 13, evidence = evidence))
            assertEquals(evidence.name, response.body?.get("evidence"))
            when (evidence) {
                ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL -> {
                    assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
                    assertTrue(
                        response.body
                        ?.get("detail")
                        .orEmpty()
                        .contains("EACCES"),
                    )
                }
                ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE -> {
                    assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
                    assertTrue(
                        response.body
                        ?.get("detail")
                        .orEmpty()
                        .contains("Permission denied"),
                    )
                }
                ContainmentViolationEvidence.UNKNOWN -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
                    assertFalse(
                        response.body
                        ?.get("detail")
                        .orEmpty()
                        .contains("Permission denied"),
                    )
                }
            }
        }
    }
}
