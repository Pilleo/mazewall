package io.mazewall.spring;

import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.execution.DomainLimits;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState;
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource;
import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.IncompleteDrainException;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner;
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus;
import io.mazewall.enforcer.api.execution.RuntimeAcquisitionUnknownException;
import io.mazewall.enforcer.api.execution.RuntimeAcquisitionPendingException;
import io.mazewall.enforcer.api.execution.InProcessDomain;
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
    private ManagedRuntime runtime;
    private ManagedRuntimeAcquisitionOwner acquisitionOwner;
    private DomainLimits limits;
    private InvocationContextBridge contexts;
    private Thread startActor;
    private InProcessDomain preparedDomain;
    private boolean effectInFlight;
    private boolean retirementRequested;
    private boolean retirementInFlight;
    private boolean automaticRetirementReserved;
    private ManagedRuntimeAcquisitionStatus ownerResult;
    private Throwable startupFailure;
    private Throwable cleanupFailure;
    private Throwable metadataFailure;
    private volatile Throwable retirementFailure;
    private Throwable firstOwnerHistory;
    private Throwable secondOwnerHistory;
    private ManagedRuntimeAcquisitionStatus unexpectedOwnerResult;
    private final IllegalStateException unexpectedRetirement;
    private Error deliveredFatal;
    private Error secondDeliveredFatal;

    /** A passive snapshot; constructing one does not grant lifecycle authority. */
    public sealed interface OwnerRetirementObservation {
        enum BeforeResult { NOT_ATTEMPTED, IN_FLIGHT }
        record Unobserved(BeforeResult phase) implements OwnerRetirementObservation {
            public Unobserved { Objects.requireNonNull(phase); }
        }
        record OwnerResult(ManagedRuntimeAcquisitionStatus status) implements OwnerRetirementObservation {
            public OwnerResult {
                Objects.requireNonNull(status);
                if (!(status instanceof ManagedRuntimeAcquisitionStatus.Pending)
                        && !(status instanceof ManagedRuntimeAcquisitionStatus.Unknown)
                        && !(status instanceof ManagedRuntimeAcquisitionStatus.Complete))
                    throw new IllegalArgumentException("Not a retirement result");
            }
        }
        record OpaqueFailure(Throwable failure) implements OwnerRetirementObservation {
            public OpaqueFailure { Objects.requireNonNull(failure); }
        }
    }

    /**
     * Associates the original active owner and its exact runtime. The caller must retain this lifecycle,
     * the original owner and the resolution owner after a pending stop callback until a real receipt
     * or retained unknown outcome is available. Owner readiness is a snapshot, not a lease.
     */
    public DomainLifecycle(ManagedRuntime runtime, ManagedRuntimeAcquisitionOwner owner,
            ReviewedOperationArtifact artifact, DomainLimits limits, PreparedOperationResources resources,
            Duration timeout, InvocationContextBridge contexts) {
        this.prepare = null;
        this.drain = null;
        this.unexpectedRetirement = new IllegalStateException("Unexpected owner retirement result");
        this.runtime = Objects.requireNonNull(runtime);
        this.acquisitionOwner = Objects.requireNonNull(owner);
        this.artifact = Objects.requireNonNull(artifact);
        this.limits = Objects.requireNonNull(limits);
        this.resources = Objects.requireNonNull(resources);
        this.timeout = validTimeout(timeout);
        this.contexts = Objects.requireNonNull(contexts);
        if (!owner.ownsRuntime(runtime) || !(owner.status() instanceof ManagedRuntimeAcquisitionStatus.Active))
            throw new IllegalArgumentException("An active original owner and runtime are required");
    }

    private static Duration validTimeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero() || timeout.toNanos() <= 0)
            throw new IllegalArgumentException("Positive bounded timeout required");
        return timeout;
    }

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
        this.unexpectedRetirement = null;
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

    @Override public void start() {
        if (acquisitionOwner != null) { startOwned(); return; }
        synchronized (this) { startLegacy(); }
    }

    private void startLegacy() {
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

    @Override public void stop() {
        if (acquisitionOwner != null) { stopOwned(); return; }
        synchronized (this) { stopLegacy(); }
    }

    private void stopLegacy() {
        if (state == State.CLOSED) return;
        if (state == State.NEW && resources != null) resources.close();
        state = State.DRAINING;
        // Retain prepare/drain closures (and their exact owner) even on timeout or failure.
        lastDrain = Objects.requireNonNull(drain.apply(timeout));
        if (lastDrain == DrainReport.Complete.INSTANCE) state = State.CLOSED;
    }

    private void startOwned() {
        synchronized (this) {
            if (state == State.READY) return;
            if (state != State.NEW) throw new IllegalStateException("Explicit new owner required: " + state);
            state = State.PREPARING;
            effectInFlight = true;
            startActor = Thread.currentThread();
        }
        InProcessDomain domain = null;
        InvocationDispatcher result = null;
        Throwable failure = null;
        try {
            domain = runtime.prepare(artifact, limits, resources);
            synchronized (this) {
                if (startActor != Thread.currentThread() || !effectInFlight)
                    throw new IllegalStateException("Invalid preparation actor");
                preparedDomain = domain;
            }
            result = new ManagedRuntimeDispatcher(runtime, domain, contexts);
        } catch (Throwable thrown) {
            failure = thrown;
        }
        boolean active = false;
        if (failure == null) {
            try { active = acquisitionOwner.status() instanceof ManagedRuntimeAcquisitionStatus.Active; }
            catch (Throwable thrown) { failure = thrown; }
        }
        boolean externallyRetired = false;
        synchronized (this) {
            if (startActor != Thread.currentThread() || !effectInFlight) throw new IllegalStateException("Invalid preparation actor");
            startupFailure = failure;
            if (result != null) dispatcher = result;
            effectInFlight = false;
            if (failure != null) state = State.FAILED;
            else if (retirementRequested) state = State.DRAINING;
            else if (active) state = State.READY;
            else { retirementRequested = true; state = State.DRAINING; externallyRetired = true; }
        }
        if (externallyRetired) {
            stopOwned();
            ManagedRuntimeAcquisitionStatus observed;
            synchronized (this) { observed = ownerResult; }
            if (observed instanceof ManagedRuntimeAcquisitionStatus.Pending pending)
                throw new RuntimeAcquisitionPendingException(pending);
            throw new IllegalStateException("Original owner retired during preparation");
        }
        if (failure != null) {
            try { stopOwned(); } catch (Throwable cleanup) {
                synchronized (this) { cleanupFailure = cleanup; }
                if (cleanup != failure) {
                    Throwable primary = cleanup instanceof Error && !(failure instanceof Error) ? cleanup : failure;
                    Throwable secondary = primary == cleanup ? failure : cleanup;
                    try { primary.addSuppressed(secondary); }
                    catch (Throwable metadata) { synchronized (this) { metadataFailure = metadata; } }
                    failure = primary;
                }
            }
            throwExact(failure);
        }
    }

    private void stopOwned() {
        ManagedRuntimeAcquisitionStatus.Unknown unknown = null;
        Throwable opaque = null;
        boolean dispatch = false;
        synchronized (this) {
            retirementRequested = true;
            if (state != State.CLOSED) state = State.DRAINING;
            if (effectInFlight) {
                automaticRetirementReserved = true;
                return;
            }
            if (retirementInFlight) return;
            opaque = metadataFailure != null ? metadataFailure : retirementFailure;
            if (ownerResult instanceof ManagedRuntimeAcquisitionStatus.Unknown value) unknown = value;
            if (opaque == null && unknown == null && !automaticRetirementReserved) {
                automaticRetirementReserved = true;
                retirementInFlight = true;
                dispatch = true;
            }
        }
        if (opaque != null) throw new IllegalStateException("Retirement outcome is opaque", opaque);
        if (unknown != null) throwUnknown(unknown);
        if (dispatch) retireOwned(timeout, true);
    }

    private void retireOwned(Duration budget, boolean throwOnUnknown) {
        ManagedRuntimeAcquisitionStatus result;
        try {
            result = acquisitionOwner.retire(budget);
        } catch (Throwable failure) {
            synchronized (this) { retirementFailure = failure; retirementInFlight = false; }
            throwExact(failure);
            return;
        }
        Throwable history = result instanceof ManagedRuntimeAcquisitionStatus.Pending pending ? pending.getHistory()
                : result instanceof ManagedRuntimeAcquisitionStatus.Unknown unknown ? unknown.getHistory()
                : result instanceof ManagedRuntimeAcquisitionStatus.Complete complete ? complete.getHistory() : null;
        Error fatalToDeliver = null;
        synchronized (this) {
            if (history != null && history != startupFailure && history != firstOwnerHistory && history != secondOwnerHistory) {
                if (firstOwnerHistory == null) firstOwnerHistory = history;
                else if (secondOwnerHistory == null) secondOwnerHistory = history;
                else retirementFailure = history;
            }
            if (result instanceof ManagedRuntimeAcquisitionStatus.Pending
                    || result instanceof ManagedRuntimeAcquisitionStatus.Unknown
                    || result instanceof ManagedRuntimeAcquisitionStatus.Complete) {
                ownerResult = result;
                if (result instanceof ManagedRuntimeAcquisitionStatus.Complete && retirementFailure == null) state = State.CLOSED;
            } else {
                unexpectedOwnerResult = result;
                retirementFailure = unexpectedRetirement;
            }
            if (result instanceof ManagedRuntimeAcquisitionStatus.Unknown && history instanceof Error fatal
                    && fatal != deliveredFatal && fatal != secondDeliveredFatal && fatal != startupFailure) {
                fatalToDeliver = fatal;
                if (deliveredFatal == null) deliveredFatal = fatal;
                else if (secondDeliveredFatal == null) secondDeliveredFatal = fatal;
                else retirementFailure = fatal;
            }
            retirementInFlight = false;
        }
        if (fatalToDeliver != null) throw fatalToDeliver;
        if (retirementFailure != null) throw new IllegalStateException("Retirement outcome is opaque", retirementFailure);
        if (throwOnUnknown && result instanceof ManagedRuntimeAcquisitionStatus.Unknown unknown)
            throw new RuntimeAcquisitionUnknownException(unknown);
    }

    private void throwUnknown(ManagedRuntimeAcquisitionStatus.Unknown unknown) {
        deliverUnknownFatal(unknown);
        throw new RuntimeAcquisitionUnknownException(unknown);
    }

    private void deliverUnknownFatal(ManagedRuntimeAcquisitionStatus.Unknown unknown) {
        Throwable history = unknown.getHistory();
        if (history instanceof Error fatal) {
            boolean deliver;
            synchronized (this) {
                deliver = fatal != deliveredFatal && fatal != secondDeliveredFatal && fatal != startupFailure;
                if (deliver) {
                    if (deliveredFatal == null) deliveredFatal = fatal;
                    else if (secondDeliveredFatal == null) secondDeliveredFatal = fatal;
                    else retirementFailure = fatal;
                }
            }
            if (deliver) throw fatal;
        }
    }

    /** Returns an inert snapshot without dispatching retirement or waiting for physical completion. */
    public OwnerRetirementObservation retirement() {
        Throwable failure;
        ManagedRuntimeAcquisitionStatus result;
        boolean inFlight;
        synchronized (this) {
            if (acquisitionOwner == null) throw new IllegalStateException("Not owner-bound");
            failure = metadataFailure != null ? metadataFailure : retirementFailure;
            result = ownerResult;
            inFlight = effectInFlight || retirementInFlight;
        }
        if (failure != null) return new OwnerRetirementObservation.OpaqueFailure(failure);
        if (inFlight) return new OwnerRetirementObservation.Unobserved(OwnerRetirementObservation.BeforeResult.IN_FLIGHT);
        if (result != null) return new OwnerRetirementObservation.OwnerResult(result);
        return new OwnerRetirementObservation.Unobserved(OwnerRetirementObservation.BeforeResult.NOT_ATTEMPTED);
    }

    /**
     * Requests one bounded pass through the original owner. An owner-reported Unknown may be retried
     * through that owner's guards; an opaque lifecycle failure forbids another dispatch.
     */
    public OwnerRetirementObservation retryRetirement(Duration budget) {
        validTimeout(budget);
        synchronized (this) {
            if (acquisitionOwner == null || !retirementRequested || effectInFlight || retirementInFlight
                    || retirementFailure != null || metadataFailure != null || state == State.CLOSED)
                throw new IllegalStateException("Retirement retry is not eligible");
            retirementInFlight = true;
        }
        retireOwned(budget, false);
        return retirement();
    }

    /** The callback reports only that bounded stop returned, not physical retirement. */
    @Override public void stop(Runnable callback) {
        stop();
        callback.run(); // A completed bounded stop is not a claim that live owners disappeared.
    }
    @Override public synchronized boolean isRunning() { return state == State.READY; }
    @Override public boolean isAutoStartup() { return false; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    /** Returns the legacy drain report; owner-bound instances return null. */
    public synchronized DrainReport lastDrain() { return lastDrain; }

    @Override public String operationId() {
        return artifact == null ? "unprotected-host-characterization" : artifact.getBinding().getOperationId();
    }

    /** Never waits for preparation/drain, starts an owner, or treats host characterization as protection. */
    @Override public ExecutionDiagnostics diagnostics() {
        State observed = state;
        InvocationDispatcher current = dispatcher;
        if (acquisitionOwner != null && !(current instanceof ManagedRuntimeDispatcher)) return ExecutionDiagnostics.UNAVAILABLE;
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
        if (acquisitionOwner != null) {
            boolean ownerComplete;
            synchronized (this) { ownerComplete = ownerResult instanceof ManagedRuntimeAcquisitionStatus.Complete; }
            retired = ownerComplete && actual && owned.getRetirementComplete();
        }
        return new ExecutionDiagnostics(phase, owned.getGeneration(), owned.getInvocations(), live, pending, retired);
    }

    synchronized InvocationDispatcher dispatcher() {
        if (state != State.READY) throw new InactiveDomainException(state.name());
        return dispatcher;
    }
    ReviewedOperationArtifact artifact() { return artifact; }
    PreparedOperationResources resources() { return Objects.requireNonNull(resources, "Production requires actual prepared resources"); }

    @SuppressWarnings("unchecked") // Preserve the exact original checked or unchecked failure from Kotlin boundaries.
    private static <T extends Throwable> void throwExact(Throwable failure) throws T { throw (T) failure; }

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
