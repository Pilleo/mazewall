package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DeadlineTest {
    @Test
    fun `afterMillis handles large duration without overflow`() {
        val now = System.nanoTime()
        val deadline = Deadline.afterMillis(Long.MAX_VALUE / 1_000L, now)
        assertEquals(Long.MAX_VALUE, deadline.nanoTime)
        assertFalse(deadline.isExpired(now))
        assertEquals(Int.MAX_VALUE, deadline.remainingMillis(now))
    }

    @Test
    fun `afterMillis saturates when addition would overflow`() {
        val now = Long.MAX_VALUE - 100L
        val deadline = Deadline.afterMillis(10L, now)
        assertEquals(Long.MAX_VALUE, deadline.nanoTime)
        assertFalse(deadline.isExpired(now))
    }
}
