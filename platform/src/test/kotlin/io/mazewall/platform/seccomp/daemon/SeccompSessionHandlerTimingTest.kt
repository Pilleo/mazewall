package io.mazewall.platform.seccomp.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class SeccompSessionHandlerTimingTest {
    @Test
    fun `pending preparation retains a bounded fallback poll despite completion wakeups`() {
        assertEquals(50, SeccompSessionHandler.pendingDeadlinePollTimeoutMillis(30_000))
        assertEquals(1, SeccompSessionHandler.pendingDeadlinePollTimeoutMillis(1))
        assertEquals(0, SeccompSessionHandler.pendingDeadlinePollTimeoutMillis(0))
    }
}
