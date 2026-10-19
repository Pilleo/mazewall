package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.Policy;
import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.enforcer.api.WorkerPolicyBuilder;
import io.mazewall.enforcer.api.execution.DomainLimits;
import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.InProcessDomain;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.testing.MockManagedRuntimeKt;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real owned dispatch with native installation mocked, not kernel-enforcement evidence. */
@Timeout(15)
class ManagedRuntimeDispatcherTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test void capturesEachInvocationOnCallerAndRunsBodyOnOwnedPlatformWorker() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var domain = domain(runtime, 2);
            var caller = Thread.currentThread();
            var context = new ThreadLocal<String>();
            var captures = new AtomicInteger();
            var worker = new AtomicReference<Thread>();
            var dispatcher = new ManagedRuntimeDispatcher(runtime, domain, invocation -> {
                assertSame(caller, Thread.currentThread());
                captures.incrementAndGet();
                String captured = context.get();
                return () -> {
                    assertNull(context.get());
                    context.set(captured);
                    try { return invocation.call(); }
                    finally { context.remove(); }
                };
            });
            try {
                context.set("first");
                assertEquals("first", dispatcher.call(() -> {
                    worker.set(Thread.currentThread());
                    return context.get();
                }));
                context.set("second");
                assertEquals("second", dispatcher.call(context::get));
                assertEquals("second", context.get());
                assertEquals(2, captures.get());
                assertNotSame(caller, worker.get());
                assertFalse(worker.get().isVirtual());
                assertEquals(2L, domain.diagnostics().getInvocations().getAdmitted());
            } finally { context.remove(); }
            return null;
        });
    }

    @Test void sameDomainNestedContinuationStaysOnExactWorkerWithoutReadmission() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var domain = domain(runtime, 1);
            var dispatcher = new ManagedRuntimeDispatcher(runtime, domain);
            var worker = new AtomicReference<Thread>();
            Object result = dispatcher.call(() -> {
                worker.set(Thread.currentThread());
                return dispatcher.call(() -> {
                    assertSame(worker.get(), Thread.currentThread());
                    return "nested";
                });
            });
            assertEquals("nested", result);
            assertEquals(1L, domain.diagnostics().getInvocations().getAdmitted());
            return null;
        });
    }

    @Test void checkedBodyFailureRetainsOriginalIdentityWithoutReplay() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var dispatcher = new ManagedRuntimeDispatcher(runtime, domain(runtime, 1));
            var failure = new Exception("body failure");
            var actions = new AtomicInteger();
            assertSame(failure, assertThrows(Exception.class, () -> dispatcher.call(() -> {
                actions.incrementAndGet();
                throw failure;
            })));
            assertEquals(1, actions.get());
            return null;
        });
    }

    @Test void wrongOwnerRejectsBeforeBody() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var domain = domain(runtime, 1);
            try (var other = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, TIMEOUT, TIMEOUT)) {
                var dispatcher = new ManagedRuntimeDispatcher(other, domain);
                assertThrows(IllegalArgumentException.class,
                        () -> dispatcher.call(() -> { throw new AssertionError("wrong owner entered body"); }));
                assertEquals(0L, domain.diagnostics().getInvocations().getAdmitted());
            }
            return null;
        });
    }

    @Test void closedOwnerRejectsWithoutCallerFallback() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var domain = domain(runtime, 1);
            var dispatcher = new ManagedRuntimeDispatcher(runtime, domain);
            assertSame(DrainReport.Complete.INSTANCE, runtime.drain(TIMEOUT));
            assertThrows(RejectedExecutionException.class,
                    () -> dispatcher.call(() -> { throw new AssertionError("closed owner entered body"); }));
            assertEquals(0L, domain.diagnostics().getInvocations().getAdmitted());
            return null;
        });
    }

    @Test void callerInterruptionRequestsCancellationButCannotRetireHeldCleanup() {
        MockManagedRuntimeKt.withMockManagedRuntime(runtime -> {
            var domain = domain(runtime, 1);
            var dispatcher = new ManagedRuntimeDispatcher(runtime, domain);
            var entered = new CountDownLatch(1);
            var bodyRelease = new CountDownLatch(1);
            var cleanupEntered = new CountDownLatch(1);
            var cleanupRelease = new CountDownLatch(1);
            var observed = new CompletableFuture<Throwable>();
            var caller = Thread.ofPlatform().unstarted(() -> {
                try {
                    dispatcher.call(() -> {
                        entered.countDown();
                        try { bodyRelease.await(); return "released"; }
                        finally {
                            cleanupEntered.countDown();
                            assertTrue(cleanupRelease.await(5, TimeUnit.SECONDS));
                        }
                    });
                    observed.complete(new AssertionError("interrupted call returned normally"));
                } catch (Throwable failure) {
                    observed.complete(failure);
                }
            });
            try {
                caller.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                caller.interrupt();
                assertInstanceOf(InterruptedException.class, observed.get(5, TimeUnit.SECONDS));
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
                assertEquals(1L, domain.diagnostics().getInvocations().getCancellationRequests());
                assertEquals(1, domain.diagnostics().getUnfinishedInvocations());
                assertFalse(domain.diagnostics().getRetirementComplete());
                assertInstanceOf(DrainReport.Incomplete.class, runtime.drain(Duration.ofMillis(20)));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            } finally {
                bodyRelease.countDown();
                cleanupRelease.countDown();
                try { caller.join(5_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                assertFalse(caller.isAlive());
                assertSame(DrainReport.Complete.INSTANCE, runtime.drain(TIMEOUT));
                assertEquals(0, domain.diagnostics().getLiveWorkers());
                assertTrue(domain.diagnostics().getRetirementComplete());
            }
            return null;
        });
    }

    private static InProcessDomain domain(ManagedRuntime runtime, int admissions) {
        return runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), new DomainLimits(1, admissions));
    }
}
