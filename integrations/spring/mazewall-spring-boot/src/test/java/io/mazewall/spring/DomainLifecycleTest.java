package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;
import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState;
import io.mazewall.enforcer.api.execution.ExecutionGeneration;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        assertEquals(ExecutionDiagnosticState.UNKNOWN, lifecycle.diagnostics().getState(),
                "trusted uncontained characterization cannot invent protected readiness");
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
        assertInstanceOf(DrainReport.Incomplete.class, lifecycle.lastDrain());
        var pending = lifecycle.diagnostics();
        assertEquals(ExecutionDiagnosticState.DRAINING, pending.getState());
        assertEquals(1, pending.getLiveWorkers());
        assertEquals(1, pending.getUnfinishedInvocations());
        assertFalse(pending.getRetirementComplete());
        assertThrows(IllegalStateException.class, lifecycle::dispatcher);
        assertThrows(IllegalStateException.class, lifecycle::start);
        lifecycle.stop();
        assertSame(DrainReport.Complete.INSTANCE, lifecycle.lastDrain());
        assertEquals(ExecutionDiagnosticState.CLOSED, lifecycle.diagnostics().getState());
        assertTrue(lifecycle.diagnostics().getRetirementComplete());
        assertFalse(pending.getRetirementComplete(), "a captured incomplete sample cannot become complete");
        assertEquals(1, prepare.get());
        assertEquals(2, drains.get());
    }

    @Test void passiveSnapshotDoesNotWaitForPreparationOrStartAnotherOwner() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        var lifecycle = DomainLifecycle.forTrustedHostCharacterization(() -> {
            preparations.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("held preparation was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return body -> body.proceed();
        }, timeout -> DrainReport.Complete.INSTANCE, Duration.ofSeconds(5));
        assertEquals(ExecutionDiagnosticState.UNKNOWN, lifecycle.diagnostics().getState());
        assertEquals(0, preparations.get());
        var starter = Thread.ofPlatform().unstarted(lifecycle::start);
        try {
            starter.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var snapshot = CompletableFuture.supplyAsync(lifecycle::diagnostics).get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionDiagnosticState.PREPARING, snapshot.getState());
            assertSame(ExecutionGeneration.Unavailable.INSTANCE, snapshot.getGeneration());
            assertEquals(1, preparations.get());
        } finally {
            release.countDown();
            starter.join(5_000);
            assertFalse(starter.isAlive());
            lifecycle.stop();
        }
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
