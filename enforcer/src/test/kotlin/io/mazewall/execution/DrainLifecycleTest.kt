package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.internal.execution.DomainDrainSnapshot
import io.mazewall.enforcer.internal.execution.DrainLifecycle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DrainLifecycleTest {
    @Test
    fun `pending physical observation cannot become complete when later metrics reach zero`() {
        assertIs<DrainReport.Incomplete>(DrainLifecycle.evaluate(true, false, listOf(DomainDrainSnapshot(false, 0, 0, null))))
        assertIs<DrainReport.Incomplete>(DrainLifecycle.evaluate(false, false, listOf(DomainDrainSnapshot(true, 0, 0, null))))
        assertEquals(DrainReport.Complete, DrainLifecycle.evaluate(true, false, listOf(DomainDrainSnapshot(true, 0, 0, null))))
    }

    @Test
    fun `physically finished ordinary and fatal failures remain incomplete with exact identity on repeated observation`() {
        for (failure in listOf(IllegalStateException("ordinary cleanup"), AssertionError("fatal cleanup"))) {
            val terminal = DomainDrainSnapshot(true, 0, 0, failure)
            repeat(2) {
                val result = assertIs<DrainReport.Incomplete>(DrainLifecycle.evaluate(true, false, listOf(terminal)))
                assertTrue(result.cleanupFailure === failure)
            }
        }
    }
}
