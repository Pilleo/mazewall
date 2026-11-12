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
final class ReportBootstrap {
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

    ReportBootstrap(Path root, PreparedOperationResources resources, ReviewedOperationArtifact artifact, ManagedRuntime runtime,
                            ManagedRuntimeAcquisitionOwner owner) {
        this.root = root;
        this.resources = resources;
        this.artifact = artifact;
        this.runtime = runtime;
        lifecycle = new DomainLifecycle(runtime, owner, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50),
                ReportRequestContext.bridge(effects, worker));
    }
    static Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy(Path root) {
        return WorkerPolicyBuilder.denyList(PROFILE).allowFsRead(root.resolve("application.jar"), root.resolve("templates/approved.txt")).build();
    }
    static OperationBinding binding(String sha, String generation) {
        return new OperationBinding("render-report", OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", "reportService", "className", ReportService.class.getName(),
                        "methodDescriptor", "generate(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
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
    void reportRetired() {
        Thread actual = worker.get();
        if (actual != null && actual.isAlive()) throw new IllegalStateException("Actual report worker still alive");
        var state = lifecycle.diagnostics();
        if (actual == null && state == ExecutionDiagnostics.UNAVAILABLE) return;
        if (!state.getRetirementComplete() || state.getLiveWorkers() != 0) {
            throw new IllegalStateException("Canonical complete report drain required");
        }
        if (jdbc != null) {
            System.out.println("REPORT_AUDIT committed=" + jdbc.queryForObject("select count(*) from report_audit", Integer.class)
                    + " bodyEntries=" + ReportRequestContext.bodyEntries.get() + " cleanContexts=" + ReportRequestContext.cleanContexts.get());
            jdbc.execute("shutdown");
        }
        System.out.println("REPORT_RETIRED actualWorker=" + (actual == null ? "unused" : actual.threadId()));
    }
}
