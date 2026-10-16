package io.mazewall.spring.diagnostics;

import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState;
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource;
import io.mazewall.enforcer.api.execution.ExecutionGeneration;
import io.mazewall.enforcer.api.execution.InvocationDiagnostics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Status;
import static org.junit.jupiter.api.Assertions.*;

/** Typed observation fixtures test health decisions, not kernel attestation. */
class DiagnosticsHealthTest {
    @Test void readyRequiresObservedGenerationAndLiveWorkerAndEverySourceMustBeReady() {
        var beans = new DefaultListableBeanFactory();
        var indicator = new MazewallDiagnosticsHealthIndicator(beans);
        assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
        beans.registerSingleton("first", source("first-operation", sample(ExecutionDiagnosticState.READY, true, 1)));
        assertEquals(Status.UP, indicator.health().getStatus());
        beans.registerSingleton("second", source("second-operation", sample(ExecutionDiagnosticState.READY, false, 1)));
        assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
        beans.destroySingleton("second");
        beans.registerSingleton("second", source("second-operation", sample(ExecutionDiagnosticState.READY, true, 0)));
        assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
    }

    @Test void failedDrainingClosedAndUnknownObservationsCannotBecomeHealthyFromCounters() {
        for (var state : ExecutionDiagnosticState.values()) {
            if (state == ExecutionDiagnosticState.READY) continue;
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("operation", source("approved-operation", sample(state, true, 1)));
            var indicator = new MazewallDiagnosticsHealthIndicator(beans);
            assertEquals(state == ExecutionDiagnosticState.FAILED ? Status.DOWN : Status.OUT_OF_SERVICE,
                    indicator.health().getStatus(), state.name());
        }
    }

    @Test void endpointReturnsImmutableSeparateIdentitiesAndActualCounterSnapshots() {
        var beans = new DefaultListableBeanFactory();
        var first = sample(ExecutionDiagnosticState.READY, true, 1);
        beans.registerSingleton("beanA", source("shared-operation-name", first));
        beans.registerSingleton("beanB", source("shared-operation-name", sample(ExecutionDiagnosticState.DRAINING, true, 1)));
        var records = new MazewallDiagnosticsEndpoint(beans).read();
        assertEquals(2, records.size(), "duplicate operation names cannot silently erase an owner");
        assertEquals("beanA", records.getFirst().source());
        assertEquals("shared-operation-name", records.getFirst().operationId());
        assertSame(first, records.getFirst().execution());
        assertEquals(7L, records.getFirst().execution().getInvocations().getAdmitted());
        assertEquals(2L, records.getFirst().execution().getInvocations().getRejected());
        assertEquals(1L, records.getFirst().execution().getInvocations().getCancellationRequests());
        assertThrows(UnsupportedOperationException.class, records::clear);
    }

    @Test void anActualSourceFailureCannotBeConvertedToHealthyOrSilentlyOmitted() {
        var beans = new DefaultListableBeanFactory();
        var fatal = new LinkageError("actual diagnostic source failure");
        beans.registerSingleton("broken", new ExecutionDiagnosticsSource() {
            @Override public String operationId() { return "broken-operation"; }
            @Override public ExecutionDiagnostics diagnostics() { throw fatal; }
        });
        assertSame(fatal, assertThrows(LinkageError.class, new MazewallDiagnosticsEndpoint(beans)::read));
        assertSame(fatal, assertThrows(LinkageError.class, new MazewallDiagnosticsHealthIndicator(beans)::health));
    }

    private static ExecutionDiagnostics sample(ExecutionDiagnosticState state, boolean generation, int workers) {
        return new ExecutionDiagnostics(state, generation ? new ExecutionGeneration.InProcess(1) : ExecutionGeneration.Unavailable.INSTANCE,
                new InvocationDiagnostics(7, 2, 1, 5, 4), workers, 1, state == ExecutionDiagnosticState.CLOSED);
    }

    private static ExecutionDiagnosticsSource source(String id, ExecutionDiagnostics sample) {
        return new ExecutionDiagnosticsSource() {
            @Override public String operationId() { return id; }
            @Override public ExecutionDiagnostics diagnostics() { return sample; }
        };
    }
}
