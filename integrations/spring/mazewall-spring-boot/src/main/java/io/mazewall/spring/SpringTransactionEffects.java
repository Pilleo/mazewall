package io.mazewall.spring;

import io.mazewall.enforcer.diagnostics.ContainmentDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One retained worker transaction-chain completion report, independent of caller result delivery. */
public final class SpringTransactionEffects {
    public enum Outcome { COMMITTED, ROLLED_BACK, UNKNOWN }
    public enum Kind { TRANSACTION, SAVEPOINT }
    public record TransactionEffect(long id, Kind kind, Outcome outcome, long workerThreadId, Long completionThreadId) {}
    public record Completion(long invocationId, long workerThreadId, List<TransactionEffect> transactions,
                             Throwable executionFailure, ContainmentDiagnostic diagnostic) {
        public Completion { transactions = List.copyOf(transactions); }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final AtomicLong nextInvocation = new AtomicLong();
    private final AtomicReference<Completion> last = new AtomicReference<>();

    /** Most recently completed supported body/transaction chain, not a complete audit stream. */
    public Completion lastCompletion() { return last.get(); }

    Object execute(InvocationDispatcher.Body body) throws Throwable {
        if (CURRENT.get() != null) return body.proceed();
        Scope scope = new Scope(nextInvocation.incrementAndGet(), Thread.currentThread());
        CURRENT.set(scope);
        Throwable failure = null;
        try {
            return body.proceed();
        } catch (Throwable problem) {
            failure = problem instanceof TransactionalFailureBridge bridge ? bridge.original() : problem;
            throw problem;
        } finally {
            last.set(new Completion(scope.id, scope.worker.threadId(),
                    scope.transactions.stream().map(Effect::snapshot).toList(), failure, scope.diagnostic));
            CURRENT.remove();
        }
    }

    static Scope current() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.worker != Thread.currentThread()) {
            throw new IllegalStateException("JDBC diagnosis must run inside the exact physical dispatch scope");
        }
        return scope;
    }

    static final class Scope {
        final long id;
        final Thread worker;
        final List<Effect> transactions = new ArrayList<>();
        Throwable recordedFailure;
        ContainmentDiagnostic diagnostic;
        Scope(long id, Thread worker) { this.id = id; this.worker = worker; }
        Effect add(Kind kind) {
            Effect effect = new Effect(transactions.size() + 1L, kind, worker.threadId());
            transactions.add(effect);
            return effect;
        }
        void record(Throwable failure, ContainmentDiagnostic evidence) {
            if (recordedFailure == null) { recordedFailure = failure; diagnostic = evidence; }
        }
    }

    static final class Effect {
        final long id;
        final Kind kind;
        final long worker;
        Outcome outcome = Outcome.UNKNOWN;
        Long completionThread;
        Effect(long id, Kind kind, long worker) { this.id = id; this.kind = kind; this.worker = worker; }
        void complete(Outcome actual) {
            if (Thread.currentThread().threadId() != worker) throw new IllegalStateException("Transaction completion left its physical worker");
            outcome = actual;
            completionThread = Thread.currentThread().threadId();
        }
        TransactionEffect snapshot() { return new TransactionEffect(id, kind, outcome, worker, completionThread); }
    }
}
