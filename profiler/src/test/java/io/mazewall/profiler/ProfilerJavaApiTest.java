package io.mazewall.profiler;

import io.mazewall.Mazewall;
import io.mazewall.Policy;
import io.mazewall.PolicyScope;
import io.mazewall.PolicyState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerJavaApiTest {

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
