package demo.report;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.spring.SpringTransactionEffects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ReportContextTest {
    @Test void receiptsAndSecurityRestoreAfterFailureAndRejectMissingCorrelation() throws Exception {
        var effects = new SpringTransactionEffects();
        var bridge = ReportRequestContext.bridge(effects, new AtomicReference<>());
        assertThrows(IllegalStateException.class, () -> bridge.capture(() -> "unused"));
        var receipt = new ReportRequestContext.Receipt();
        var original = new IllegalArgumentException("local cause");
        var captured = ReportRequestContext.with(receipt, () -> bridge.capture(() -> {
            assertSame(receipt, ReportRequestContext.current());
            ReportRequestContext.bodyStarted(receipt.id);
            throw original;
        }));
        assertNull(ReportRequestContext.current());
        assertSame(original, assertThrows(IllegalArgumentException.class, captured::call));
        assertNull(ReportRequestContext.current());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(receipt.bodyEntered);
        assertEquals("UNKNOWN", receipt.transactionOutcome());
    }
    @Test void publicOutcomesDoNotNeedExceptionMessages() {
        assertEquals(ReportController.Outcome.INVALID_INPUT, ReportController.outcome(new IllegalArgumentException("private path")));
        assertEquals(ReportController.Outcome.AUTHORIZATION_DENIED, ReportController.outcome(new org.springframework.security.access.AccessDeniedException("private")));
        assertEquals(ReportController.Outcome.CONTAINMENT_REFUSED, ReportController.outcome(new java.nio.file.AccessDeniedException("private")));
        assertEquals(ReportController.Outcome.BUSY, ReportController.outcome(new java.util.concurrent.RejectedExecutionException("private")));
        assertEquals(ReportController.Outcome.CANCELLED, ReportController.outcome(new java.util.concurrent.CancellationException("private")));
        assertEquals(ReportController.Outcome.OUTCOME_UNKNOWN, ReportController.outcome(new java.util.concurrent.TimeoutException("private")));
        assertEquals(ReportController.Outcome.INTERNAL_FAILURE, ReportController.outcome(new Exception("private")));
    }
}
