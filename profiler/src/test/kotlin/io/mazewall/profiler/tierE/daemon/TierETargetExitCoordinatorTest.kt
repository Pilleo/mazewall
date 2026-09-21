package io.mazewall.profiler.tierE.daemon

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierETargetExitCoordinatorTest {
    private val target = TierERegisteredTarget(
        registrationId = "orders",
        pid = 4242,
        sessionTag = 0x4d5a,
        processIncarnation = 99L,
        markerPath = Path.of("/operator/agent.so"),
        markerOffset = 0x20,
        artifactFileName = "orders.mzse",
    )

    @Test
    fun `exit notification is delivered exactly once`() {
        val notifications = AtomicInteger()
        val delivered = CountDownLatch(1)
        val coordinator = TierETargetExitCoordinator(TierEProcessIdentityVerifier { null }, pollIntervalMillis = 1)

        coordinator
            .watch(target) {
            notifications.incrementAndGet()
            delivered.countDown()
        }.use {
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
        }

        assertEquals(1, notifications.get())
    }

    @Test
    fun `matched target is not finalized while watch is active`() {
        val notifications = AtomicInteger()
        val coordinator = TierETargetExitCoordinator(
            TierEProcessIdentityVerifier { target.processIncarnation },
            pollIntervalMillis = 1,
        )

        coordinator.watch(target) { notifications.incrementAndGet() }.use {
            Thread.sleep(20)
        }

        assertFalse(notifications.get() > 0)
    }

    @Test
    fun `reused pid notifies exactly once`() {
        val notifications = AtomicInteger()
        val delivered = CountDownLatch(1)
        val coordinator = TierETargetExitCoordinator(TierEProcessIdentityVerifier { 100L }, pollIntervalMillis = 1)

        coordinator
            .watch(target) {
            notifications.incrementAndGet()
            delivered.countDown()
        }.use {
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
        }

        assertEquals(1, notifications.get())
    }
}
