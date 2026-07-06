package io.mazewall.enforcer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ProcessStateRegistryTest {

    @Test
    fun `test ProcessStateRegistry update`() {
        val originalState = ProcessStateRegistry.state
        assertNotNull(originalState)

        try {
            ProcessStateRegistry.update { state ->
                state.copy(filterDepth = 100)
            }

            assertEquals(100, ProcessStateRegistry.state.filterDepth)
        } finally {
            // Restore state to avoid polluting other tests
            ProcessStateRegistry.state = originalState
        }
    }
}
