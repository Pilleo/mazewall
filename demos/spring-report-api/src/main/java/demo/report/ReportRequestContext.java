package demo.report;

import io.mazewall.spring.InvocationContextBridge;
import io.mazewall.spring.SpringSecurityContextBridge;
import io.mazewall.spring.SpringTransactionEffects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-request metadata only. No servlet request, connection, credentials or global receipt registry. */
final class ReportRequestContext {
    private static final ThreadLocal<Receipt> CURRENT = new ThreadLocal<>();
    static final AtomicInteger bodyEntries = new AtomicInteger();
    static final AtomicInteger cleanContexts = new AtomicInteger();
    static final class Receipt {
        final String id = UUID.randomUUID().toString();
        volatile boolean bodyEntered;
        final AtomicReference<SpringTransactionEffects.Completion> transaction = new AtomicReference<>();
        String transactionOutcome() {
            var completed = transaction.get();
            return completed == null || completed.transactions().isEmpty() ? "UNKNOWN" : completed.transactions().getFirst().outcome().name();
        }
    }
    static Receipt current() { return CURRENT.get(); }
    static <T> T with(Receipt receipt, Callable<T> body) throws Exception {
        Receipt previous = CURRENT.get();
        CURRENT.set(receipt);
        try { return body.call(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    static void bodyStarted(String id) {
        var receipt = CURRENT.get();
        if (receipt != null) {
            if (!receipt.id.equals(id)) throw new IllegalStateException("Request correlation changed before body entry");
            receipt.bodyEntered = true;
            bodyEntries.incrementAndGet();
        }
    }
    static InvocationContextBridge bridge(SpringTransactionEffects effects, AtomicReference<Thread> worker) {
        var security = new SpringSecurityContextBridge();
        return invocation -> {
            Receipt receipt = CURRENT.get();
            if (receipt == null) throw new IllegalStateException("Explicit request metadata required before dispatch");
            var delegated = security.capture(invocation);
            return () -> {
                Thread actual = Thread.currentThread();
                worker.compareAndSet(null, actual);
                if (worker.get() != actual || actual.isVirtual() || CURRENT.get() != null
                        || org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null) throw new IllegalStateException("Clean single owned worker required");
                var before = effects.lastCompletion();
                try { return with(receipt, delegated); }
                finally {
                    var after = effects.lastCompletion();
                    if (after != null && after != before) receipt.transaction.set(after);
                    if (CURRENT.get() != null || org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null) throw new IllegalStateException("Worker context was not restored");
                    cleanContexts.incrementAndGet();
                }
            };
        };
    }
}
