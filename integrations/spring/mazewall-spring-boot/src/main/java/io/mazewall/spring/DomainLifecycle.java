package io.mazewall.spring;

import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.execution.DomainLimits;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState;
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource;
import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.IncompleteDrainException;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.PreparedOperationResources;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.SmartLifecycle;

/** Application-owned explicit preparation. The caller bootstraps the runtime beforehand. */
public final class DomainLifecycle implements SmartLifecycle, ExecutionDiagnosticsSource {
    /** Typed pre-entry refusal for application outcomes; remains compatible with IllegalStateException callers. */
    public static final class InactiveDomainException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private InactiveDomainException(String state) { super("Required Spring domain is inactive: " + state); }
    }
    private enum State { NEW, PREPARING, READY, DRAINING, CLOSED, FAILED }
    private final Supplier<InvocationDispatcher> prepare;
    private final Function<Duration, DrainReport> drain;
    private final Duration timeout;
    private final ReviewedOperationArtifact artifact;
    private final PreparedOperationResources resources;
    private volatile State state = State.NEW;
    private volatile InvocationDispatcher dispatcher;
    private volatile DrainReport lastDrain;

    /** Takes responsibility for the genuine owner after successful construction; no metadata-only fallback. */
    public DomainLifecycle(ManagedRuntime owner, ReviewedOperationArtifact artifact, DomainLimits limits,
                           PreparedOperationResources resources, Duration timeout) {
        this(owner, artifact, limits, resources, timeout, InvocationContextBridge.none());
    }

    /** Explicit optional context delegation; capture happens for each call before the owned-worker hop. */
    public DomainLifecycle(ManagedRuntime owner, ReviewedOperationArtifact artifact, DomainLimits limits,
                           PreparedOperationResources resources, Duration timeout, InvocationContextBridge contexts) {
        this(() -> new ManagedRuntimeDispatcher(owner, owner.prepare(artifact, limits, resources), contexts), owner::drain,
                timeout, Objects.requireNonNull(artifact), Objects.requireNonNull(resources));
        Objects.requireNonNull(owner);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(contexts);
    }

    private DomainLifecycle(Supplier<InvocationDispatcher> prepare, Function<Duration, DrainReport> drain,
                            Duration timeout, ReviewedOperationArtifact artifact, PreparedOperationResources resources) {
        this.prepare = Objects.requireNonNull(prepare);
        this.drain = Objects.requireNonNull(drain);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive bounded drain required");
        this.artifact = artifact;
        this.resources = resources;
    }

    /** Unprotected lifecycle state characterization; not a production preparation capability. */
    static DomainLifecycle forTrustedHostCharacterization(Supplier<InvocationDispatcher> prepare,
            Function<Duration, DrainReport> drain, Duration timeout) {
        return new DomainLifecycle(prepare, drain, timeout, null, null);
    }

    @Override public synchronized void start() {
        if (state == State.READY) return;
        if (state != State.NEW) throw new IllegalStateException("Explicit new owner required after retirement: " + state);
        state = State.PREPARING;
        try {
            dispatcher = Objects.requireNonNull(prepare.get());
            state = State.READY;
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            Throwable primary = failure;
            try {
                if (resources != null) resources.close(); // Unclaimed owner closes; transferred owner stays with runtime.
            } catch (RuntimeException | Error cleanup) {
                primary = retainCleanup(primary, cleanup);
            }
            try {
                lastDrain = drain.apply(timeout);
                if (lastDrain instanceof DrainReport.Incomplete incomplete) {
                    primary.addSuppressed(new IncompleteDrainException(incomplete));
                }
            } catch (RuntimeException | Error cleanup) {
                primary = retainCleanup(primary, cleanup);
            }
            if (primary instanceof Error fatal) throw fatal;
            throw (RuntimeException) primary;
        }
    }

    @Override public synchronized void stop() {
        if (state == State.CLOSED) return;
        if (state == State.NEW && resources != null) resources.close();
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

    @Override public String operationId() {
        return artifact == null ? "unprotected-host-characterization" : artifact.getBinding().getOperationId();
    }

    /** Never waits for preparation/drain, starts an owner, or treats host characterization as protection. */
    @Override public ExecutionDiagnostics diagnostics() {
        State observed = state;
        InvocationDispatcher current = dispatcher;
        ExecutionDiagnostics owned = current instanceof ManagedRuntimeDispatcher managed
                ? managed.diagnostics() : ExecutionDiagnostics.UNAVAILABLE;
        DrainReport report = lastDrain;
        ExecutionDiagnosticState phase = switch (observed) {
            case NEW -> ExecutionDiagnosticState.UNKNOWN;
            case PREPARING -> ExecutionDiagnosticState.PREPARING;
            case READY -> owned.getState();
            case DRAINING -> owned.getState() == ExecutionDiagnosticState.FAILED
                    ? ExecutionDiagnosticState.FAILED : ExecutionDiagnosticState.DRAINING;
            case CLOSED -> owned.getState() == ExecutionDiagnosticState.FAILED
                    ? ExecutionDiagnosticState.FAILED : ExecutionDiagnosticState.CLOSED;
            case FAILED -> ExecutionDiagnosticState.FAILED;
        };
        if (report instanceof DrainReport.Incomplete incomplete && incomplete.getCleanupFailure() != null) {
            phase = ExecutionDiagnosticState.FAILED;
        }
        boolean actual = current instanceof ManagedRuntimeDispatcher;
        int live = actual ? owned.getLiveWorkers() : report instanceof DrainReport.Incomplete incomplete ? incomplete.getLiveWorkers() : 0;
        int pending = actual ? owned.getUnfinishedInvocations() : report instanceof DrainReport.Incomplete incomplete ? incomplete.getUnfinishedInvocations() : 0;
        boolean retired = (phase == ExecutionDiagnosticState.CLOSED || phase == ExecutionDiagnosticState.FAILED)
                && (actual ? owned.getRetirementComplete() : report == DrainReport.Complete.INSTANCE);
        return new ExecutionDiagnostics(phase, owned.getGeneration(), owned.getInvocations(), live, pending, retired);
    }

    synchronized InvocationDispatcher dispatcher() {
        if (state != State.READY) throw new InactiveDomainException(state.name());
        return dispatcher;
    }
    ReviewedOperationArtifact artifact() { return artifact; }
    PreparedOperationResources resources() { return Objects.requireNonNull(resources, "Production requires actual prepared resources"); }

    private static Throwable retainCleanup(Throwable primary, Throwable cleanup) {
        if (primary == cleanup) return primary;
        if (cleanup instanceof Error && !(primary instanceof Error)) {
            cleanup.addSuppressed(primary);
            return cleanup;
        }
        primary.addSuppressed(cleanup);
        return primary;
    }
}
