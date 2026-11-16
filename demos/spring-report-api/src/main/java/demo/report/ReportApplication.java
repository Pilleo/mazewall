package demo.report;

import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner;
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus;
import io.mazewall.enforcer.api.execution.PreparedOperationResources;
import io.mazewall.enforcer.api.execution.PreparedResolutionOwner;
import io.mazewall.enforcer.api.execution.PreparedResolutionRetirement;
import io.mazewall.spring.DomainLifecycle;
import java.time.Duration;
import java.util.Optional;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

public final class ReportApplication {
    private static final Object ADMISSION = new Object();
    private static boolean spent;
    private static Thread originalStarter;
    private static StartupHandle retained;
    private static final Duration BUDGET = Duration.ofSeconds(10);

    /** A passive snapshot of the original application's retirement. */
    public enum StartupPhase { PENDING, UNKNOWN, COMPLETE }
    public record StartupObservation(StartupPhase phase, Throwable history) {
        public StartupObservation { java.util.Objects.requireNonNull(phase); }
    }

    /** Failure-only capability for the original platform-thread caller. */
    public static final class StartupHandle {
        private final Thread starter;
        private final Throwable[] cleanupFailures = new Throwable[8];
        private final boolean[] cleanupFatalDelivered = new boolean[8];
        private final boolean[] cleanupAttached = new boolean[8];
        private boolean overflowFatalDelivered;
        private Throwable firstFailure;
        private Throwable overflowFailure;
        private int failureCount;
        private boolean metadataUnknown;
        private boolean contextRunEntered;
        private boolean contextCloseAttempted;
        private boolean contextClosed;
        private boolean contextUnknown;
        private boolean lifecycleStopAttempted;
        private boolean resolutionUnknown;
        private boolean resolutionComplete;
        private boolean resolutionTransferred;
        private boolean coreComplete;
        private boolean coreUnknown;
        private boolean coreOpaque;
        private boolean retryInFlight;
        private boolean retirementOutputDone;
        private boolean failureReady;
        private boolean complete;
        private ConfigurableApplicationContext context;
        private PreparedResolutionOwner resolution;
        private PreparedOperationResources resources;
        private ManagedRuntimeAcquisitionOwner acquisition;
        private ManagedRuntime runtime;
        private ReportBootstrap bootstrap;
        private DomainLifecycle lifecycle;
        private DomainLifecycle.OwnerRetirementObservation lifecycleObservation;

        private StartupHandle(Thread starter) { this.starter = starter; }
        private void requireStarter() {
            if (Thread.currentThread() != starter || Thread.currentThread().isVirtual())
                throw new IllegalStateException("Only the original platform starter owns recovery");
        }
        void attachContext(ConfigurableApplicationContext actual) {
            requireStarter();
            context = java.util.Objects.requireNonNull(actual);
        }
        private void noteOriginal(Throwable failure) {
            synchronized (ADMISSION) { if (firstFailure == null) firstFailure = failure; }
        }
        private void markAttached(Throwable failure) {
            synchronized (ADMISSION) {
                for (int i = 0; i < failureCount; i++) {
                    if (cleanupFailures[i] == failure) {
                        cleanupAttached[i] = true;
                        if (failure instanceof Error) cleanupFatalDelivered[i] = true;
                    }
                }
            }
        }
        private void markPropagated(Throwable failure) {
            synchronized (ADMISSION) {
                for (int i = 0; i < failureCount; i++)
                    if (cleanupFailures[i] == failure && failure instanceof Error) cleanupFatalDelivered[i] = true;
                if (overflowFailure == failure && failure instanceof Error) overflowFatalDelivered = true;
            }
        }
        void record(Throwable failure) {
            if (failure == null || failure == firstFailure) return;
            synchronized (ADMISSION) {
                for (int i = 0; i < failureCount; i++) if (cleanupFailures[i] == failure) return;
                if (failureCount == cleanupFailures.length) {
                    if (overflowFailure == null) overflowFailure = failure;
                    metadataUnknown = true;
                }
                else cleanupFailures[failureCount++] = failure;
            }
        }
        private Throwable history() {
            synchronized (ADMISSION) {
                if (firstFailure != null) return firstFailure;
                return failureCount == 0 ? overflowFailure : cleanupFailures[0];
            }
        }
        /** Observes stored state without starting retirement. */
        public StartupObservation status() {
            requireStarter();
            StartupPhase phase;
            Throwable history;
            synchronized (ADMISSION) {
                if (!failureReady && !complete) throw new IllegalStateException("Startup has not reached failure recovery");
                phase = complete ? StartupPhase.COMPLETE
                        : contextUnknown || metadataUnknown || resolutionUnknown || coreUnknown ? StartupPhase.UNKNOWN : StartupPhase.PENDING;
                history = firstFailure != null ? firstFailure : failureCount == 0 ? overflowFailure : cleanupFailures[0];
            }
            return new StartupObservation(phase, history);
        }
        /** Requests one eligible pass on the original owners; resolution close has no total wall-clock bound. */
        private Error claimUndeliveredFatal() {
            synchronized (ADMISSION) {
                for (int i = 0; i < failureCount; i++) {
                    if (!cleanupFatalDelivered[i] && cleanupFailures[i] instanceof Error value) {
                        cleanupFatalDelivered[i] = true;
                        return value;
                    }
                }
                if (!overflowFatalDelivered && overflowFailure instanceof Error value) {
                    overflowFatalDelivered = true;
                    return value;
                }
                return null;
            }
        }
        public StartupObservation retryRetirement(Duration runtimeBudget) {
            requireStarter();
            validateBudget(runtimeBudget);
            boolean alreadyComplete;
            synchronized (ADMISSION) {
                alreadyComplete = complete;
                if (!alreadyComplete) {
                    if (!failureReady || retained != this || retryInFlight)
                        throw new IllegalStateException("Original failure retirement is not available");
                    retryInFlight = true;
                }
            }
            if (alreadyComplete) {
                Error fatal = claimUndeliveredFatal();
                if (fatal != null) throw fatal;
                return status();
            }
            Error fatal;
            try {
                try { retire(runtimeBudget, true); }
                catch (Throwable failure) { record(failure); metadataUnknown = true; }
                if (settled()) clearCompleted();
                fatal = claimUndeliveredFatal();
            } finally {
                synchronized (ADMISSION) { retryInFlight = false; }
            }
            if (fatal != null) throw fatal;
            return status();
        }
        private void observeLifecycle() {
            try { lifecycleObservation = lifecycle.retirement(); }
            catch (Throwable failure) { record(failure); metadataUnknown = true; return; }
            if (lifecycleObservation instanceof DomainLifecycle.OwnerRetirementObservation.OpaqueFailure opaque) {
                record(opaque.failure()); metadataUnknown = true;
            } else if (lifecycleObservation instanceof DomainLifecycle.OwnerRetirementObservation.OwnerResult result) {
                observeCore(result.status());
            }
        }
        private void observeCore(ManagedRuntimeAcquisitionStatus status) {
            if (status instanceof ManagedRuntimeAcquisitionStatus.Complete done) {
                coreComplete = true; record(done.getHistory());
            } else if (status instanceof ManagedRuntimeAcquisitionStatus.Unknown unknown) {
                coreUnknown = true; record(unknown.getHistory());
            } else if (status instanceof ManagedRuntimeAcquisitionStatus.Pending pending) {
                record(pending.getHistory());
            }
        }
        private void observeResolution(PreparedResolutionRetirement status) {
            if (status instanceof PreparedResolutionRetirement.Complete done) {
                resolutionComplete = true; record(done.getHistory());
            } else if (status instanceof PreparedResolutionRetirement.Transferred transferred) {
                resolutionTransferred = true; record(transferred.getHistory());
            } else if (status instanceof PreparedResolutionRetirement.Unknown unknown) {
                resolutionUnknown = true; record(unknown.getHistory());
            } else if (status instanceof PreparedResolutionRetirement.Pending pending) record(pending.getHistory());
        }
        private void retire(Duration budget, boolean retry) {
            requireStarter();
            if (metadataUnknown) return;
            if (contextRunEntered && context == null) contextUnknown = true;
            if (context != null && !contextCloseAttempted) {
                contextCloseAttempted = true;
                try { context.close(); contextClosed = true; }
                catch (Throwable failure) { record(failure); contextUnknown = true; }
            }
            if (metadataUnknown) return;
            if (lifecycle != null) {
                if (!lifecycleStopAttempted) {
                    lifecycleStopAttempted = true;
                    try { lifecycle.stop(); } catch (Throwable failure) { record(failure); }
                    observeLifecycle();
                } else if (retry && !metadataUnknown && !coreComplete) {
                    observeLifecycle();
                    if (!(lifecycleObservation instanceof DomainLifecycle.OwnerRetirementObservation.OpaqueFailure)
                            && !(lifecycleObservation instanceof DomainLifecycle.OwnerRetirementObservation.Unobserved unobserved
                                && unobserved.phase() == DomainLifecycle.OwnerRetirementObservation.BeforeResult.IN_FLIGHT)) {
                        try { lifecycleObservation = lifecycle.retryRetirement(budget); }
                        catch (Throwable failure) { record(failure); }
                        observeLifecycle();
                    }
                }
            } else if (acquisition != null && !coreComplete && !metadataUnknown && !coreOpaque) {
                try { observeCore(acquisition.retire(budget)); }
                catch (Throwable failure) {
                    record(failure);
                    coreOpaque = true;
                    coreUnknown = true;
                }
            } else if (acquisition == null) coreComplete = true;
            if (metadataUnknown || coreOpaque) return;
            if (resolution != null && !resolutionComplete && !resolutionTransferred && !resolutionUnknown && !metadataUnknown) {
                try {
                    var observed = resolution.retirement();
                    observeResolution(observed);
                    if (observed instanceof PreparedResolutionRetirement.Pending) observeResolution(resolution.retryRetirement());
                } catch (Throwable failure) {
                    record(failure);
                    boolean pending = false;
                    try {
                        var observed = resolution.retirement();
                        observeResolution(observed);
                        pending = observed instanceof PreparedResolutionRetirement.Pending;
                    } catch (Throwable observationFailure) { record(observationFailure); resolutionUnknown = true; }
                    if (!resolutionComplete && !resolutionTransferred && !pending) resolutionUnknown = true;
                }
            } else if (resolution == null) resolutionComplete = true;
            if (metadataUnknown) return;
            if (bootstrap != null && coreComplete && !retirementOutputDone) {
                try {
                    Thread worker = bootstrap.worker.get();
                    if (worker != null) worker.join(1);
                    if (worker == null || !worker.isAlive()) {
                        bootstrap.reportRetired();
                        retirementOutputDone = true;
                    }
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    record(failure); metadataUnknown = true;
                }
            }
        }
        private boolean settled() {
            boolean contextDone = !contextRunEntered || contextClosed;
            boolean workerDone = bootstrap == null || retirementOutputDone;
            return contextDone && workerDone && coreComplete && !coreUnknown && !contextUnknown && !metadataUnknown
                    && (resolutionComplete || resolutionTransferred && coreComplete) && !resolutionUnknown;
        }
        private void clearCompleted() {
            synchronized (ADMISSION) {
                complete = true;
                resolution = null; resources = null; acquisition = null; runtime = null;
                bootstrap = null; lifecycle = null; context = null;
                if (retained == this) retained = null;
            }
        }
        private Throwable finish(Throwable original) {
            synchronized (ADMISSION) { if (firstFailure == null) firstFailure = original; }
            try { retire(BUDGET, false); }
            catch (Throwable failure) { record(failure); metadataUnknown = true; }
            boolean done = settled();
            Throwable selected = original;
            for (int i = 0; i < failureCount; i++) {
                Throwable next = cleanupFailures[i];
                if (cleanupAttached[i]) continue;
                if (selected == null) selected = next;
                else if (selected != next) {
                    Throwable primary = next instanceof Error && !(selected instanceof Error) ? next : selected;
                    Throwable secondary = primary == next ? selected : next;
                    selected = primary;
                    if (metadataUnknown) break;
                    attachFailure(primary, secondary, this);
                    if (metadataUnknown) break;
                }
            }
            if (metadataUnknown && !(selected instanceof Error)) {
                for (int i = 0; i < failureCount; i++) {
                    if (!cleanupFatalDelivered[i] && cleanupFailures[i] instanceof Error) {
                        selected = cleanupFailures[i];
                        break;
                    }
                }
            }
            if (!(selected instanceof Error) && overflowFailure instanceof Error) selected = overflowFailure;
            if (!done && selected == null) {
                selected = new IllegalStateException("Application retirement is not complete");
                synchronized (ADMISSION) { if (firstFailure == null) firstFailure = selected; }
            }
            markPropagated(selected);
            if (done && !metadataUnknown) clearCompleted();
            else synchronized (ADMISSION) { failureReady = true; }
            return selected;
        }
    }

    /** Returns the original retained failure capability only to its original platform starter. */
    public static Optional<StartupHandle> failedStartup() {
        StartupHandle observed;
        synchronized (ADMISSION) {
            if (originalStarter != null && (Thread.currentThread() != originalStarter || Thread.currentThread().isVirtual()))
                throw new IllegalStateException("Only the original platform starter owns recovery");
            observed = retained != null && retained.failureReady ? retained : null;
        }
        return Optional.ofNullable(observed);
    }
    private static void validateBudget(Duration budget) {
        java.util.Objects.requireNonNull(budget);
        if (budget.isZero() || budget.isNegative() || budget.toNanos() <= 0) throw new IllegalArgumentException("Positive bounded runtime budget required");
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwExact(Throwable failure) throws T { throw (T) failure; }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || Thread.currentThread().isVirtual()) throw new IllegalArgumentException("Usage: measure <deploymentDirectory> | serve <deploymentDirectory> <loopbackPort>");
        boolean measure = args[0].equals("measure") && args.length == 2;
        if (!measure && (!args[0].equals("serve") || args.length != 3)) throw new IllegalArgumentException("Explicit measure or serve command required");
        int port = measure ? -1 : Integer.parseInt(args[2]);
        if (!measure && (port < 0 || port > 65535)) throw new IllegalArgumentException("Invalid loopback port");
        var handle = new StartupHandle(Thread.currentThread());
        synchronized (ADMISSION) {
            if (spent) throw new IllegalStateException("Application startup was already admitted");
            spent = true; originalStarter = Thread.currentThread(); retained = handle;
        }
        Throwable failure = null;
        try {
            var root = java.nio.file.Path.of(args[1]).toRealPath();
            if (measure) measure(root, handle);
            else serve(root, port, handle);
        } catch (Throwable thrown) { failure = thrown; }
        Throwable result = handle.finish(failure);
        if (result != null) throwExact(result);
    }

    private static PreparedOperationResources resolve(java.nio.file.Path root, StartupHandle handle) {
        handle.resolution = PreparedResolutionOwner.create();
        var resources = handle.resolution.resolve(io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT,
                ReportBootstrap.policy(root), ReportService.class,
                java.util.Set.of(root.resolve("application.jar"), root.resolve("templates/approved.txt")));
        handle.resources = resources;
        return resources;
    }

    private static void measure(java.nio.file.Path root, StartupHandle handle) {
        var resources = resolve(root, handle);
        var document = new io.mazewall.enforcer.api.OperationPolicyDocument(
                ReportBootstrap.binding(resources.getImplementationSha256(), resources.getResourceGeneration()),
                io.mazewall.enforcer.api.CanonicalPolicyArtifact.worker(io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT,
                        ReportBootstrap.policy(root)), java.util.Set.of(), java.util.Map.of());
        System.out.println(document.toJson());
    }

    private static void attachFailure(Throwable primary, Throwable secondary, StartupHandle handle) {
        try {
            primary.addSuppressed(secondary);
            boolean present = false;
            for (Throwable observed : primary.getSuppressed()) {
                if (observed == secondary) { present = true; break; }
            }
            if (present) handle.markAttached(secondary);
            else handle.metadataUnknown = true;
        } catch (Throwable metadata) {
            handle.record(metadata);
            handle.metadataUnknown = true;
        }
    }

    private static java.io.InputStream openReviewInput(java.nio.file.Path path, StartupHandle handle) throws Exception {
        try { return java.nio.file.Files.newInputStream(path); }
        catch (Throwable failure) { handle.noteOriginal(failure); throwExact(failure); return null; }
    }

    private static void closeReviewInput(java.io.InputStream input, Throwable bodyFailure, StartupHandle handle) throws Exception {
        Throwable closeFailure = null;
        try { input.close(); }
        catch (Throwable failure) {
            if (bodyFailure == null) handle.noteOriginal(failure); else handle.record(failure);
            closeFailure = failure;
        }
        if (bodyFailure != null && closeFailure != null && bodyFailure != closeFailure) {
            Throwable primary = closeFailure instanceof Error && !(bodyFailure instanceof Error) ? closeFailure : bodyFailure;
            Throwable secondary = primary == closeFailure ? bodyFailure : closeFailure;
            attachFailure(primary, secondary, handle);
            throwExact(primary);
            return;
        }
        if (bodyFailure != null) { throwExact(bodyFailure); return; }
        if (closeFailure != null) throwExact(closeFailure);
    }

    private static java.util.Properties loadReviewProperties(java.nio.file.Path path, StartupHandle handle) throws Exception {
        var external = new java.util.Properties();
        var input = openReviewInput(path, handle);
        Throwable failure = null;
        try {
            byte[] bytes = input.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException("Review properties exceed16KiB");
            external.load(new java.io.ByteArrayInputStream(bytes));
        } catch (Throwable body) { handle.noteOriginal(body); failure = body; }
        closeReviewInput(input, failure, handle);
        return external;
    }

    private static String loadReviewDocument(java.nio.file.Path path, StartupHandle handle) throws Exception {
        var input = openReviewInput(path, handle);
        Throwable failure = null;
        String document = null;
        try {
            byte[] bytes = input.readNBytes(2_097_153);
            if (bytes.length > 2_097_152) throw new IllegalArgumentException("Candidate exceeds2MiB");
            document = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable body) { handle.noteOriginal(body); failure = body; }
        closeReviewInput(input, failure, handle);
        return document;
    }

    private static ReportBootstrap open(java.nio.file.Path root, StartupHandle handle) throws Exception {
        var external = loadReviewProperties(root.resolve("trusted-review.properties"), handle);
        String document = loadReviewDocument(root.resolve("candidate.json"), handle);
        var expected = ReportBootstrap.binding(external.getProperty("jarSha256"), external.getProperty("resourceGeneration"));
        var artifact = io.mazewall.enforcer.api.ReviewedOperationArtifact.load(document,
                new io.mazewall.enforcer.api.TrustedPolicyReview(external.getProperty("reviewer"),
                        external.getProperty("documentSha256"), java.util.Set.of()), expected);
        var resources = resolve(root, handle);
        if (!resources.getImplementationSha256().equals(expected.getImplementationSha256())
                || !resources.getResourceGeneration().equals(expected.getResourceGeneration()))
            throw new IllegalArgumentException("Finished deployment differs from external trusted review");
        handle.acquisition = ManagedRuntimeAcquisitionOwner.create();
        var runtime = handle.acquisition.start(BUDGET, BUDGET);
        handle.runtime = runtime;
        var bootstrap = new ReportBootstrap(root, resources, artifact, runtime, handle.acquisition);
        handle.bootstrap = bootstrap;
        handle.lifecycle = bootstrap.lifecycle;
        bootstrap.lifecycle.start();
        if (!bootstrap.lifecycle.isRunning()) throw new IllegalStateException("Protected lifecycle did not become ready");
        return bootstrap;
    }

    private static void serve(java.nio.file.Path root, int port, StartupHandle handle) throws Exception {
        var bootstrap = open(root, handle);
        var application = new org.springframework.boot.SpringApplication(ReportConfiguration.class);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(java.util.Map.of("server.address", "127.0.0.1", "server.port", port,
                "spring.main.banner-mode", "off", "management.endpoint.health.probes.enabled", "true",
                "management.endpoint.health.group.readiness.include", "readinessState,protectedReports",
                "management.endpoints.web.exposure.include", "health", "server.shutdown", "graceful"));
        application.addInitializers(context -> {
            handle.attachContext(context);
            try {
                requireEvaluationListener(context.getEnvironment(), port);
                bootstrap.register(context.getBeanFactory());
            } catch (Exception failure) { throw new IllegalStateException("Required listener/protected singleton setup failed before refresh", failure); }
        });
        handle.contextRunEntered = true;
        ConfigurableApplicationContext context;
        try { context = application.run(); }
        catch (Throwable failure) {
            handle.contextCloseAttempted = true;
            handle.contextUnknown = true;
            throwExact(failure);
            return;
        }
        if (handle.context != context) throw new IllegalStateException("Original application context changed");
        if (!bootstrap.lifecycle.isRunning()) throw new IllegalStateException("Protected lifecycle retired before readiness");
        var web = (org.springframework.boot.web.server.context.WebServerApplicationContext) context;
        System.out.println("REPORT_READY port=" + web.getWebServer().getPort());
        var commands = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        for (String command; ; ) {
            command = commands.readLine();
            if ("stop".equals(command)) break;
            if (!"drain".equals(command)) throw new IllegalArgumentException("Send drain or stop on owned stdin; EOF is not forced-termination evidence");
            org.springframework.boot.availability.AvailabilityChangeEvent.publish(context, org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
            if (!handle.lifecycleStopAttempted) {
                handle.lifecycleStopAttempted = true;
                try { bootstrap.lifecycle.stop(); } finally { handle.observeLifecycle(); }
            } else {
                handle.observeLifecycle();
                if (!(handle.lifecycleObservation instanceof DomainLifecycle.OwnerRetirementObservation.OwnerResult result
                        && result.status() instanceof ManagedRuntimeAcquisitionStatus.Complete)) {
                    bootstrap.lifecycle.retryRetirement(BUDGET);
                    handle.observeLifecycle();
                }
            }
            System.out.println("REPORT_DRAIN " + bootstrap.lifecycle.diagnostics().getState());
        }
    }

    static void requireEvaluationListener(ConfigurableEnvironment environment, int requestedPort) {
        String address = environment.getRequiredProperty("server.address");
        int port = environment.getRequiredProperty("server.port", Integer.class);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        String managementAddress = environment.getProperty("management.server.address");
        if (!"127.0.0.1".equals(address) || port != requestedPort
                || (managementPort != null && (managementPort == 0 || managementPort != requestedPort))
                || (managementAddress != null && !"127.0.0.1".equals(managementAddress))) {
            throw new IllegalStateException("Evaluation requires 127.0.0.1 and the CLI port, without a separate management listener");
        }
    }
}
