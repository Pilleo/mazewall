package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;
import io.mazewall.enforcer.api.execution.DrainReport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DomainLifecycleTest {
    @Test void explicitPreparationAndIncompleteDrainRetainOwnerAndRejectNewAdmission() {
        AtomicInteger prepare = new AtomicInteger();
        AtomicInteger drains = new AtomicInteger();
        var lifecycle = DomainLifecycle.forTrustedHostCharacterization(
                () -> { prepare.incrementAndGet(); return body -> body.proceed(); },
                timeout -> drains.incrementAndGet() == 1 ? new DrainReport.Incomplete(1, 1, null) : DrainReport.Complete.INSTANCE,
                Duration.ofMillis(50));
        assertFalse(lifecycle.isAutoStartup());
        assertThrows(IllegalStateException.class, lifecycle::dispatcher);
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
        assertInstanceOf(DrainReport.Incomplete.class, lifecycle.lastDrain());
        assertThrows(IllegalStateException.class, lifecycle::dispatcher);
        assertThrows(IllegalStateException.class, lifecycle::start);
        lifecycle.stop();
        assertSame(DrainReport.Complete.INSTANCE, lifecycle.lastDrain());
        assertEquals(1, prepare.get());
        assertEquals(2, drains.get());
    }

    @Test void preparationFailureRemainsInactiveAndDrainsRetainedOwner() {
        var failure = new IllegalStateException("required installation failed");
        AtomicInteger drains = new AtomicInteger();
        var lifecycle = DomainLifecycle.forTrustedHostCharacterization(() -> { throw failure; },
                timeout -> { drains.incrementAndGet(); return DrainReport.Complete.INSTANCE; }, Duration.ofMillis(50));
        assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::start));
        assertFalse(lifecycle.isRunning());
        assertThrows(IllegalStateException.class, lifecycle::dispatcher);
        assertEquals(1, drains.get());
    }
}
