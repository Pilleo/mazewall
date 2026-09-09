package io.mazewall.profiler;

import io.mazewall.Mazewall;
import io.mazewall.EnabledIfLinuxAndSupported;
import io.mazewall.Policy;
import io.mazewall.PolicyScope;
import io.mazewall.PolicyState;
import org.junit.jupiter.api.Test;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KVisibility;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerJavaApiTest {

    @Test
    @EnabledIfLinuxAndSupported
    void shutdownNowRetainsPublicKotlinReturnTypeAndJvmSignature() throws Exception {
        var type = JvmClassMappingKt.getKotlinClass(Profiler.ProfilerExecutorWrapper.class);
        var method = type.getMembers().stream().filter(member -> member.getName().equals("shutdownNow"))
                .findFirst().orElseThrow();
        assertEquals(KVisibility.PUBLIC, type.getVisibility());
        assertEquals(KVisibility.PUBLIC, method.getVisibility());
        assertEquals("kotlin.collections.List<java.lang.Runnable>", method.getReturnType().toString());
        assertFalse(method.getReturnType().isMarkedNullable());
        assertEquals("java.util.List<java.lang.Runnable>",
                Profiler.ProfilerExecutorWrapper.class.getMethod("shutdownNow").getGenericReturnType().getTypeName());
    }

    @Test
    @EnabledIfLinuxAndSupported
    void shutdownNowReturnsQueuedTasksAndInterruptsOnlyItsDelegate() throws Exception {
        var first = Executors.newSingleThreadExecutor();
        var second = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            var wrapped = Profiler.wrap(first);
            var other = Profiler.wrap(second);
            first.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Runnable queued = () -> { throw new AssertionError("Queued task must be returned, not executed"); };
            first.execute(queued);

            List<Runnable> pending = wrapped.shutdownNow();

            assertEquals(1, pending.size());
            assertSame(queued, pending.getFirst());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(wrapped.isShutdown());
            assertTrue(wrapped.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(wrapped.shutdownNow().isEmpty());
            assertFalse(other.isShutdown());
            assertEquals(42, second.submit(() -> 42).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.shutdownNow();
            second.shutdownNow();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            Profiler.shutdown();
        }
    }

    @Test
    void testProfilerJavaProfileRunnable() {
        AtomicInteger counter = new AtomicInteger(0);
        ProfilingResult<?> result = Profiler.profile(() -> {
            counter.incrementAndGet();
        });

        assertNotNull(result);
        assertEquals(1, counter.get());
        assertNotNull(result.getBehavior());
    }

    @Test
    void testProfilerJavaProfileCallable() {
        ProfilingResult<String> result = Profiler.profile(() -> "result-from-callable");

        assertNotNull(result);
        assertEquals("result-from-callable", result.getValue());
        assertNotNull(result.getBehavior());

        String dsl = result.toDsl("Policy.PURE_COMPUTE", Mazewall.pureComputeUnsafe(), null, true);
        assertNotNull(dsl);
    }

    @Test
    void testBillOfBehaviorToPolicyAndDslOverloads() {
        BillOfBehavior bob = new BillOfBehavior();
        assertNotNull(bob);
        assertTrue(bob.getSyscalls().isEmpty());

        // Test toPolicy overloads with allowIncomplete=true for raw BoB
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy1 =
                bob.toPolicy(Mazewall.pureComputeUnsafe(), Path.of("."), null, true);
        assertNotNull(policy1);

        String dslWithBase = bob.toDsl("Policy.PURE_COMPUTE", Mazewall.pureComputeUnsafe(), Path.of("."), ProfilingCoverage.absent(), true);
        assertNotNull(dslWithBase);
    }

    @Test
    void testProfilerWrapExecutor() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        var directExecutor = Executors.newSingleThreadExecutor();
        try {
            var wrapped = Profiler.wrap(directExecutor);
            wrapped.submit(() -> counter.incrementAndGet()).get();
            assertEquals(1, counter.get());
        } finally {
            directExecutor.shutdown();
        }
    }
}
