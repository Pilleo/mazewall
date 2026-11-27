package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeadlineTest {
    @Test
    fun `afterMillis handles large duration without overflow`() {
        val now = 1_000_000_000L
        val deadline = Deadline.afterMillis(Long.MAX_VALUE, now)
        assertEquals(Long.MAX_VALUE, deadline.nanoTime - now)
        assertFalse(deadline.isExpired(now))
        assertEquals(Int.MAX_VALUE, deadline.remainingMillis(now))
    }

    @Test
    fun `a negative clock origin can cross zero without shortening the budget`() {
        val now = -1_000_000_000L
        val deadline = Deadline.afterMillis(30_000L, now)
        assertEquals(29_000_000_000L, deadline.nanoTime)
        assertEquals(30_000, deadline.remainingMillis(now))
        assertFalse(deadline.isExpired(0L))
        assertTrue(deadline.isExpired(deadline.nanoTime))
    }

    @Test
    fun `deadline expires at the target when the clock wraps`() {
        val now = Long.MAX_VALUE - 100L
        val deadline = Deadline.afterMillis(10L, now)
        assertEquals(10, deadline.remainingMillis(now))
        assertFalse(deadline.isExpired(now))
        val justBefore = deadline.nanoTime - 1L
        assertEquals(0, deadline.remainingMillis(justBefore))
        assertFalse(deadline.isExpired(justBefore))
        assertTrue(deadline.isExpired(deadline.nanoTime))
        assertTrue(deadline.isExpired(deadline.nanoTime + 1L))
    }

    @Test
    fun `zero duration expires immediately and negative duration is rejected`() {
        val now = Long.MIN_VALUE
        val deadline = Deadline.afterMillis(0L, now)
        assertEquals(0, deadline.remainingMillis(now))
        assertTrue(deadline.isExpired(now))
        assertThrows<IllegalArgumentException> { Deadline.afterMillis(-1L, now) }
    }
}
