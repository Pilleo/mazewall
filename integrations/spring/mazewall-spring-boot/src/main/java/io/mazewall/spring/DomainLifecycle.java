package io.mazewall.spring;

import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.execution.DomainLimits;
import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.IncompleteDrainException;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.SmartLifecycle;

/** Application-owned explicit preparation. The caller bootstraps the runtime beforehand. */
public final class DomainLifecycle implements SmartLifecycle {
    private enum State { NEW, READY, DRAINING, CLOSED, FAILED }
    private final Supplier<InvocationDispatcher> prepare;
    private final Function<Duration, DrainReport> drain;
    private final Duration timeout;
    private final ReviewedOperationArtifact artifact;
    private State state = State.NEW;
    private InvocationDispatcher dispatcher;
    private DrainReport lastDrain;

    public DomainLifecycle(ManagedRuntime owner, ReviewedOperationArtifact artifact, DomainLimits limits, Duration timeout) {
        this(() -> new ManagedRuntimeDispatcher(owner, owner.prepare(artifact, limits)), owner::drain,
                timeout, Objects.requireNonNull(artifact));
        Objects.requireNonNull(owner);
        Objects.requireNonNull(limits);
    }

    private DomainLifecycle(Supplier<InvocationDispatcher> prepare, Function<Duration, DrainReport> drain,
                            Duration timeout, ReviewedOperationArtifact artifact) {
        this.prepare = Objects.requireNonNull(prepare);
        this.drain = Objects.requireNonNull(drain);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive bounded drain required");
        this.artifact = artifact;
    }

    /** Unprotected lifecycle state characterization; not a production preparation capability. */
    static DomainLifecycle forTrustedHostCharacterization(Supplier<InvocationDispatcher> prepare,
            Function<Duration, DrainReport> drain, Duration timeout) {
        return new DomainLifecycle(prepare, drain, timeout, null);
    }

    @Override public synchronized void start() {
        if (state == State.READY) return;
        if (state != State.NEW) throw new IllegalStateException("Explicit new owner required after retirement: " + state);
        try {
            dispatcher = Objects.requireNonNull(prepare.get());
            state = State.READY;
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            try {
                lastDrain = drain.apply(timeout);
                if (lastDrain instanceof DrainReport.Incomplete incomplete) {
                    failure.addSuppressed(new IncompleteDrainException(incomplete));
                }
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override public synchronized void stop() {
        if (state == State.CLOSED) return;
        state = State.DRAINING;
        // Retain prepare/drain closures (and their exact owner) even on timeout or failure.
        lastDrain = Objects.requireNonNull(drain.apply(timeout));
        if (lastDrain == DrainReport.Complete.INSTANCE) state = State.CLOSED;
    }

    @Override public void stop(Runnable callback) {
        stop();
        callback.run(); // A completed bounded stop is not a claim that live owners disappeared.
    }
    @Override public synchronized boolean isRunning() { return state == State.READY; }
    @Override public boolean isAutoStartup() { return false; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    public synchronized DrainReport lastDrain() { return lastDrain; }
    synchronized InvocationDispatcher dispatcher() {
        if (state != State.READY) throw new IllegalStateException("Required Spring domain is inactive: " + state);
        return dispatcher;
    }
    ReviewedOperationArtifact artifact() { return artifact; }
}
