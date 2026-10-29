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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Test-only held-cleanup service fixture, never included in the normal application JAR. */
public final class HeldReportLifecycleProbe {
    public static class HeldReportService extends ReportService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch cancellation = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        HeldReportService(JdbcTemplate jdbc, Path templates) { super(jdbc, templates); }
        @Override @MazewallOperation("render-report") @Transactional(transactionManager = "jdbc", rollbackFor = Exception.class)
        @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('APPROVER')")
        public String generate(String requestId, String templateName) throws Exception {
            String result = super.generate(requestId, templateName);
            entered.countDown();
            try { release.await(); return result; }
            catch (InterruptedException cancelled) {
                cancellation.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException repeated) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                throw cancelled;
            }
        }
    }
    private static OperationBinding binding(String sha, String generation) {
        return new OperationBinding("render-report", OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", "reportService", "className", HeldReportService.class.getName(),
                        "methodDescriptor", "generate(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 2 && !Thread.currentThread().isVirtual(), "measure|verify <fixtureDirectory>");
        Path root = Path.of(args[1]).toRealPath();
        var profile = RuntimeProfile.HOTSPOT_JIT;
        var policy = WorkerPolicyBuilder.denyList(profile).allowFsRead(root.resolve("application.jar"), root.resolve("templates/approved.txt")).build();
        try (var resources = PreparedOperationResources.resolve(profile, policy, HeldReportService.class,
                Set.of(root.resolve("application.jar"), root.resolve("templates/approved.txt")))) {
            if (args[0].equals("measure")) {
                System.out.println(new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration()),
                        CanonicalPolicyArtifact.worker(profile, policy), Set.of(), Map.of()).toJson());
                return;
            }
            check(args[0].equals("verify"), "Explicit verify required");
            var external = new Properties();
            try (var input = Files.newInputStream(root.resolve("trusted-review.properties"))) { external.load(input); }
            var artifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("candidate.json")),
                    new TrustedPolicyReview(external.getProperty("reviewer"), external.getProperty("documentSha256"), Set.of()),
                    binding(external.getProperty("jarSha256"), external.getProperty("resourceGeneration")));
            var data = new JdbcDataSource();
            data.setURL("jdbc:h2:mem:held;DB_CLOSE_DELAY=-1");
            var jdbc = new JdbcTemplate(data);
            jdbc.execute("create table report_audit(request_id varchar(36) primary key, template_name varchar(40))");
            var target = new HeldReportService(jdbc, root.resolve("templates"));
            var effects = new SpringTransactionEffects();
            var worker = new AtomicReference<Thread>();
            var budget = Duration.ofSeconds(10);
            DomainLifecycle lifecycle;
            try (var runtime = ManagedRuntime.open(profile, Policy.NO_EXEC_HOTSPOT, budget, budget)) {
                lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50), ReportRequestContext.bridge(effects, worker));
                lifecycle.start();
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
                var guard = new SpringTransactionGuard(beans, "jdbc", effects);
                var binder = new SpringOperationBinder(beans, lifecycle, guard);
                Reports proxy = binder.createJdbcProxy(Reports.class, target, SpringSecurityMethodAdvice.authorizationAdvisor());
                beans.registerSingleton("reportService", proxy);
                binder.bind(new SpringOperationBinder.Approval(artifact, proxy, HeldReportService.class.getClassLoader(), profile,
                        resources.getResourceGeneration(), Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
                var delivered = new AtomicReference<Throwable>();
                var receipt = new ReportRequestContext.Receipt();
                Thread caller = Thread.ofVirtual().start(() -> {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("approver", null, List.of(new SimpleGrantedAuthority("ROLE_APPROVER"))));
                    SecurityContextHolder.setContext(context);
                    try { ReportRequestContext.with(receipt, () -> proxy.generate(receipt.id, "approved")); }
                    catch (Exception failure) { delivered.set(failure); }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try {
                    check(target.entered.await(5, TimeUnit.SECONDS), "Actual protected report body not entered");
                    caller.interrupt();
                    caller.join(5000);
                    check(!caller.isAlive() && delivered.get() instanceof InterruptedException, "Caller cancellation must remain delivery only");
                    check(target.cancellation.await(5, TimeUnit.SECONDS) && worker.get().isAlive(), "Actual cleanup must still be held");
                    lifecycle.stop();
                    check(lifecycle.lastDrain() instanceof DrainReport.Incomplete, "Held worker must retain incomplete drain");
                    var incomplete = (DrainReport.Incomplete) lifecycle.lastDrain();
                    check(!lifecycle.isRunning() && !lifecycle.diagnostics().getRetirementComplete(), "Incomplete must refuse readiness");
                    resources.validate();
                    check(receipt.transaction.get() == null, "Caller interruption is not a transaction completion receipt");
                    target.release.countDown();
                    runtime.drain(budget);
                    lifecycle.stop();
                    worker.get().join(5000);
                    check(!worker.get().isAlive() && lifecycle.lastDrain() == DrainReport.Complete.INSTANCE, "Canonical physical retirement required");
                    check(incomplete.getLiveWorkers() > 0, "Earlier incomplete receipt must not upgrade itself");
                    check(jdbc.queryForObject("select count(*) from report_audit", Integer.class) == 0, "Actual cancellation rollback required");
                    check(receipt.transaction.get() != null && receipt.transactionOutcome().equals("ROLLED_BACK"), "Actual correlated completion required after physical end");
                } finally {
                    target.release.countDown();
                    if (caller.isAlive()) caller.interrupt();
                    caller.join(5000);
                }
            }
            jdbc.execute("shutdown");
            System.out.println("REPORT_HELD_LIFECYCLE_OK caller=cancelled drain=incomplete resources=retained rollback=actual retirement=physical");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
