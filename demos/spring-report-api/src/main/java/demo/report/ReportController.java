package demo.report;

import io.mazewall.enforcer.api.ContainmentViolationException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ReportController {
    private static final System.Logger LOG = System.getLogger(ReportController.class.getName());
    private final Reports reports;
    private final ReportConfiguration.OrdinaryService ordinary;
    private final io.mazewall.spring.DomainLifecycle domain;
    ReportController(Reports reports, ReportConfiguration.OrdinaryService ordinary, io.mazewall.spring.DomainLifecycle domain) { this.reports = reports; this.ordinary = ordinary; this.domain = domain; }
    @GetMapping("/ordinary") String ordinary() { return ordinary.status(); }
    @GetMapping("/reports/{template}") ResponseEntity<String> report(@PathVariable("template") String template) {
        var receipt = new ReportRequestContext.Receipt();
        if (!ReportConfiguration.ready(domain.diagnostics().getState())) return response(receipt, Outcome.BUSY.status, "BUSY request=" + receipt.id);
        try {
            String result = ReportRequestContext.with(receipt, () -> reports.generate(receipt.id, template));
            return response(receipt, 200, result);
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.ERROR, "Report request " + receipt.id + " failed; original local cause follows", failure);
            Outcome outcome = outcome(failure, receipt.bodyEntered);
            return response(receipt, outcome.status, outcome.name() + " request=" + receipt.id);
        }
    }
    private static ResponseEntity<String> response(ReportRequestContext.Receipt receipt, int status, String body) {
        return ResponseEntity.status(status).header("X-Request-Id", receipt.id).header("X-Transaction-Outcome", receipt.transactionOutcome())
                .header("X-Body-Entered", Boolean.toString(receipt.bodyEntered)).body(body);
    }
    enum Outcome {
        INVALID_INPUT(400), AUTHORIZATION_DENIED(403), CONTAINMENT_REFUSED(422), BUSY(503), CANCELLED(409), OUTCOME_UNKNOWN(504), INTERNAL_FAILURE(500);
        final int status;
        Outcome(int status) { this.status = status; }
    }
    static Outcome outcome(Throwable failure) {
        return outcome(failure, false);
    }
    static Outcome outcome(Throwable failure, boolean bodyEntered) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!bodyEntered && cause instanceof io.mazewall.spring.DomainLifecycle.InactiveDomainException) return Outcome.BUSY;
            if (cause instanceof ContainmentViolationException || cause instanceof java.nio.file.AccessDeniedException) return Outcome.CONTAINMENT_REFUSED;
            if (cause instanceof AccessDeniedException) return Outcome.AUTHORIZATION_DENIED;
            if (cause instanceof TimeoutException || cause instanceof InterruptedException) return Outcome.OUTCOME_UNKNOWN;
            if (cause instanceof CancellationException) return Outcome.CANCELLED;
            if (cause instanceof RejectedExecutionException) return Outcome.BUSY;
            if (cause instanceof IllegalArgumentException) return Outcome.INVALID_INPUT;
        }
        return Outcome.INTERNAL_FAILURE;
    }
}
