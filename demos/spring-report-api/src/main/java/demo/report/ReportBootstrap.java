package demo.report;

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
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit deployment review, resource owner and pre-traffic singleton binding; never starter magic. */
final class ReportBootstrap implements AutoCloseable {
    private static final RuntimeProfile PROFILE = RuntimeProfile.HOTSPOT_JIT;
    private static final Duration BUDGET = Duration.ofSeconds(10);
    private final Path root;
    private final PreparedOperationResources resources;
    private final ReviewedOperationArtifact artifact;
    private final ManagedRuntime runtime;
    final DomainLifecycle lifecycle;
    private final SpringTransactionEffects effects = new SpringTransactionEffects();
    final AtomicReference<Thread> worker = new AtomicReference<>();
    private JdbcTemplate jdbc;

    private ReportBootstrap(Path root, PreparedOperationResources resources, ReviewedOperationArtifact artifact, ManagedRuntime runtime) {
        this.root = root;
        this.resources = resources;
        this.artifact = artifact;
        this.runtime = runtime;
        lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50),
                ReportRequestContext.bridge(effects, worker));
    }
    private static Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy(Path root) {
        return WorkerPolicyBuilder.denyList(PROFILE).allowFsRead(root.resolve("application.jar"), root.resolve("templates/approved.txt")).build();
    }
    private static PreparedOperationResources resolve(Path root) {
        return PreparedOperationResources.resolve(PROFILE, policy(root), ReportService.class,
                Set.of(root.resolve("application.jar"), root.resolve("templates/approved.txt")));
    }
    private static OperationBinding binding(String sha, String generation) {
        return new OperationBinding("render-report", OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", "reportService", "className", ReportService.class.getName(),
                        "methodDescriptor", "generate(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
    }
    static void measure(Path root) {
        try (var resources = resolve(root)) {
            System.out.println(new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration()),
                    CanonicalPolicyArtifact.worker(PROFILE, policy(root)), Set.of(), Map.of()).toJson());
        }
    }
    static ReportBootstrap open(Path root) throws Exception {
        var external = new Properties();
        try (var input = Files.newInputStream(root.resolve("trusted-review.properties"))) {
            byte[] bytes = input.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException("Review properties exceed16KiB");
            external.load(new java.io.ByteArrayInputStream(bytes));
        }
        String document;
        try (var input = Files.newInputStream(root.resolve("candidate.json"))) {
            byte[] bytes = input.readNBytes(2_097_153);
            if (bytes.length > 2_097_152) throw new IllegalArgumentException("Candidate exceeds2MiB");
            document = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        var expected = binding(external.getProperty("jarSha256"), external.getProperty("resourceGeneration"));
        var artifact = ReviewedOperationArtifact.load(document,
                new TrustedPolicyReview(external.getProperty("reviewer"), external.getProperty("documentSha256"), Set.of()), expected);
        var resources = resolve(root);
        try {
            if (!resources.getImplementationSha256().equals(expected.getImplementationSha256())
                    || !resources.getResourceGeneration().equals(expected.getResourceGeneration())) {
                throw new IllegalArgumentException("Finished deployment differs from external trusted review");
            }
            var runtime = ManagedRuntime.open(PROFILE, Policy.NO_EXEC_HOTSPOT, BUDGET, BUDGET);
            try {
                var bootstrap = new ReportBootstrap(root, resources, artifact, runtime);
                bootstrap.lifecycle.start();
                return bootstrap;
            } catch (RuntimeException | Error failure) {
                try { runtime.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            try { resources.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    void register(ConfigurableListableBeanFactory beans) throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:report;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data);
        jdbc.execute("create table report_audit(request_id varchar(36) primary key, template_name varchar(40))");
        var manager = new DataSourceTransactionManager(data);
        registerInfrastructure(beans, "dataSource", data);
        registerInfrastructure(beans, "jdbcTemplate", jdbc);
        registerInfrastructure(beans, "jdbc", manager);
        beans.registerSingleton("protectedDomain", lifecycle);
        var guard = new SpringTransactionGuard(beans, "jdbc", effects);
        var binder = new SpringOperationBinder(beans, lifecycle, guard);
        Reports proxy = binder.createJdbcProxy(Reports.class, new ReportService(jdbc, root.resolve("templates")),
                SpringSecurityMethodAdvice.authorizationAdvisor());
        beans.registerSingleton("reportService", proxy);
        binder.bind(new SpringOperationBinder.Approval(artifact, proxy, ReportService.class.getClassLoader(), PROFILE,
                resources.getResourceGeneration(), Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
        binder.validateRequiredBindings(List.of("reportService"));
    }
    private static void registerInfrastructure(ConfigurableListableBeanFactory beans, String name, Object value) {
        if (!(beans instanceof org.springframework.beans.factory.support.BeanDefinitionRegistry registry)) throw new IllegalArgumentException("Bean definition registry required");
        // Boot SQL initialization records dependencies on definitions, even for preinitialized singletons.
        registry.registerBeanDefinition(name, new org.springframework.beans.factory.support.RootBeanDefinition(value.getClass()));
        beans.registerSingleton(name, value);
    }
    @Override public void close() {
        try {
            runtime.close();
            lifecycle.stop();
            Thread actual = worker.get();
            if (actual != null) {
                try { actual.join(5000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Owner retirement interrupted", interrupted); }
                if (actual.isAlive()) throw new IllegalStateException("Actual report worker still alive");
            }
            var state = lifecycle.diagnostics();
            if (!state.getRetirementComplete() || state.getLiveWorkers() != 0 || lifecycle.lastDrain() != DrainReport.Complete.INSTANCE) {
                throw new IllegalStateException("Canonical complete report drain required");
            }
            if (jdbc != null) System.out.println("REPORT_AUDIT committed=" + jdbc.queryForObject("select count(*) from report_audit", Integer.class)
                    + " bodyEntries=" + ReportRequestContext.bodyEntries.get() + " cleanContexts=" + ReportRequestContext.cleanContexts.get());
            System.out.println("REPORT_RETIRED actualWorker=" + (actual == null ? "unused" : actual.threadId()));
        } finally {
            resources.close();
            if (jdbc != null && lifecycle.diagnostics().getRetirementComplete()) jdbc.execute("shutdown");
        }
    }
}
