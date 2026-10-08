package demo.boot;

import io.mazewall.enforcer.api.*;
import io.mazewall.enforcer.api.execution.*;
import io.mazewall.spring.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** Application-owned review/bootstrap. Candidate measurement never supplies review authority. */
final class ReportBootstrap implements AutoCloseable {
    static final RuntimeProfile PROFILE = RuntimeProfile.HOTSPOT_JIT;
    static final Duration BUDGET = Duration.ofSeconds(10);
    private final Path root;
    private final PreparedOperationResources resources;
    private final ReviewedOperationArtifact artifact;
    private final ManagedRuntime runtime;
    private final DomainLifecycle lifecycle;
    private final boolean unsupported;
    private final ReportState state = new ReportState();
    private final SpringTransactionEffects effects = new SpringTransactionEffects();
    private SpringTransactionGuard guard;
    private SpringOperationBinder binder;

    private ReportBootstrap(Path root, PreparedOperationResources resources, ReviewedOperationArtifact artifact,
                            ManagedRuntime runtime, DomainLifecycle lifecycle, boolean unsupported) {
        this.root = root;
        this.resources = resources;
        this.artifact = artifact;
        this.runtime = runtime;
        this.lifecycle = lifecycle;
        this.unsupported = unsupported;
    }

    static OperationBinding binding(String sha, String generation, boolean unsupported) {
        return new OperationBinding("quarterly-report", OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", "reportService", "className", implementation(unsupported).getName(),
                        "methodDescriptor", "generate(Ljava/lang/String;)Ljava/lang/String;"));
    }

    private static Class<?> implementation(boolean unsupported) {
        return unsupported ? UnsupportedReportService.class : ReportService.class;
    }

    static PreparedOperationResources resolve(Path root, boolean unsupported) {
        Path jar = root.resolve("application.jar");
        var policy = WorkerPolicyBuilder.denyList(PROFILE)
                .allowFsRead(jar, root.resolve("template.txt"))
                .allowFsWrite(root.resolve("reports/quarterly.txt")).build();
        return PreparedOperationResources.resolve(PROFILE, policy, implementation(unsupported),
                Set.of(jar, root.resolve("template.txt")));
    }

    static void measure(Path root, boolean unsupported) throws Exception {
        try (var resources = resolve(root, unsupported)) {
            var policy = WorkerPolicyBuilder.denyList(PROFILE)
                    .allowFsRead(root.resolve("application.jar"), root.resolve("template.txt"))
                    .allowFsWrite(root.resolve("reports/quarterly.txt")).build();
            var document = new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration(), unsupported),
                    CanonicalPolicyArtifact.worker(PROFILE, policy), Set.of(), Map.of());
            CandidatePublication.publish(root, unsupported ? "unsupported-candidate.json" : "candidate.json", document.toJson(),
                    unsupported ? "unsupported-generation.txt" : "candidate-generation.txt", resources.getResourceGeneration());
            System.out.println("BOOT_CANDIDATE_ONLY wholeOuterJar=" + resources.getImplementationSha256()
                    + " generation=" + resources.getResourceGeneration() + " reviewAuthority=none");
        }
    }

    static ReportBootstrap open(Path root, boolean unsupported) throws Exception {
        Properties external = new Properties();
        try (var input = Files.newInputStream(root.resolve(unsupported ? "unsupported-review.properties" : "trusted-review.properties"))) { external.load(input); }
        var expected = binding(external.getProperty("jarSha256"), external.getProperty("resourceGeneration"), unsupported);
        var review = new TrustedPolicyReview(external.getProperty("reviewer"), external.getProperty("documentSha256"), Set.of());
        var artifact = ReviewedOperationArtifact.load(Files.readString(root.resolve(unsupported ? "unsupported-candidate.json" : "candidate.json")), review, expected);
        var resources = resolve(root, unsupported);
        try {
            if (!resources.getImplementationSha256().equals(external.getProperty("jarSha256"))
                    || !resources.getResourceGeneration().equals(external.getProperty("resourceGeneration"))) {
                throw new IllegalArgumentException("Actual finished Boot deployment differs from external trusted review");
            }
            if (resources.getImplementationSha256().equals(SpringOperationBinder.classResourceSha256(implementation(unsupported)))) {
                throw new IllegalStateException("Selected class hash cannot replace actual finished-JAR hash");
            }
            Class<?> selected = implementation(unsupported);
            System.out.println("BOOT_ACTUAL_ORIGIN loader=" + selected.getClassLoader().getClass().getName()
                    + " resource=" + selected.getResource(selected.getSimpleName() + ".class")
                    + " wholeOuterJar=" + resources.getImplementationSha256()
                    + " generation=" + resources.getResourceGeneration()
                    + " reviewer=" + external.getProperty("reviewer"));
            // Explicit operator-owned platform bootstrap; process baseline is permanent, not starter magic.
            var runtime = ManagedRuntime.open(PROFILE, Policy.NO_EXEC_HOTSPOT, BUDGET, BUDGET);
            try {
                var lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50));
                lifecycle.start();
                return new ReportBootstrap(root, resources, artifact, runtime, lifecycle, unsupported);
            } catch (RuntimeException | Error failure) {
                try { runtime.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            try { resources.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void configureBinding(ConfigurableListableBeanFactory beans) {
        guard = new SpringTransactionGuard(beans, "jdbc", effects);
        binder = new SpringOperationBinder(beans, lifecycle, guard);
    }
    void bind(Reports registered) throws Exception {
        binder.bind(new SpringOperationBinder.Approval(artifact, registered, ReportService.class.getClassLoader(),
                PROFILE, resources.getResourceGeneration(), Set.of(SpringOperationBinder.Phase.BODY,
                SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
        binder.validateRequiredBindings(List.of("reportService"));
    }
    Path root() { return root; }
    ReportState state() { return state; }
    boolean unsupported() { return unsupported; }
    SpringOperationBinder binder() { return binder; }
    SpringTransactionGuard guard() { return guard; }
    SpringTransactionEffects effects() { return effects; }
    DomainLifecycle lifecycle() { return lifecycle; }
    PreparedOperationResources resources() { return resources; }
    @Override public void close() {
        try { runtime.close(); } finally { resources.close(); }
    }
}
