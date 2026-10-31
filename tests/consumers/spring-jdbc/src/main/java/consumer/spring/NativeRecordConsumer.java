package consumer.spring;

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
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Known synthetic native fixture, using published libraries only; approval is external to this JVM. */
public final class NativeRecordConsumer {
    public record Report(int count, String title, String detail) {}
    public interface Reports { Report generate(String mode); }
    public static class Service implements Reports {
        private final JdbcTemplate jdbc;
        volatile Thread worker;
        Service(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override @MazewallOperation("record-report") @Transactional(transactionManager = "jdbc")
        public Report generate(String mode) {
            worker = Thread.currentThread();
            check(!worker.isVirtual() && Mazewall.currentInvocationEvidence() != null, "Actual owned platform worker required");
            jdbc.update("insert into audit values (?)", mode);
            return new Report(1, "é".repeat(32_768), mode.equals("large") ? "x" : null);
        }
    }
    private static OperationBinding binding(String sha, String generation) {
        return new OperationBinding("record-report", OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", "reports", "className", Service.class.getName(), "methodDescriptor",
                        "generate(Ljava/lang/String;)Lconsumer/spring/NativeRecordConsumer$Report;"));
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 2 && !Thread.currentThread().isVirtual(), "measure|verify <private fixture directory>");
        Path root = Path.of(args[1]).toRealPath();
        Path jar = Path.of(Service.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(Files.isRegularFile(jar), "Finished ordinary JAR required");
        var profile = RuntimeProfile.HOTSPOT_JIT;
        var policy = WorkerPolicyBuilder.denyList(profile).allowFsRead(jar).build();
        try (var resources = PreparedOperationResources.resolve(profile, policy, Service.class, Set.of(jar))) {
            if (args[0].equals("measure")) {
                var document = new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration()),
                        CanonicalPolicyArtifact.worker(profile, policy), Set.of(), Map.of());
                Files.writeString(root.resolve("candidate.json"), document.toJson(), java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.writeString(root.resolve("generation.txt"), resources.getResourceGeneration(), java.nio.file.StandardOpenOption.CREATE_NEW);
                return;
            }
            check(args[0].equals("verify"), "Explicit verify required");
            var review = new Properties();
            try (var input = Files.newInputStream(root.resolve("trusted-review.properties"))) { review.load(input); }
            var artifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("candidate.json")),
                    new TrustedPolicyReview(review.getProperty("reviewer"), review.getProperty("documentSha256"), Set.of()),
                    binding(review.getProperty("jarSha256"), review.getProperty("resourceGeneration")));
            var data = new JdbcDataSource();
            data.setURL("jdbc:h2:mem:record;DB_CLOSE_DELAY=-1");
            var jdbc = new JdbcTemplate(data);
            jdbc.execute("create table audit(name varchar(20))");
            var target = new Service(jdbc);
            var budget = Duration.ofSeconds(10);
            try (var runtime = ManagedRuntime.open(profile, Policy.NO_EXEC_HOTSPOT, budget, budget)) {
                var lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50));
                lifecycle.start();
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
                var effects = new SpringTransactionEffects();
                var guard = new SpringTransactionGuard(beans, "jdbc", effects);
                var binder = new SpringOperationBinder(beans, lifecycle, guard);
                Reports reports = binder.createJdbcProxy(Reports.class, target, new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
                beans.registerSingleton("reports", reports);
                binder.bind(new SpringOperationBinder.Approval(artifact, reports, Service.class.getClassLoader(), profile,
                        resources.getResourceGeneration(), Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
                binder.validateRequiredBindings(List.of("reports"));
                check(reports.generate("good").title().getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 65_536, "Exact UTF8 bound");
                try { reports.generate("large"); throw new AssertionError("Oversized aggregate escaped"); }
                catch (IllegalArgumentException invalid) {
                    check(jdbc.queryForObject("select count(*) from audit where name='large'", Integer.class) == 0, "Actual SQL rollback required");
                    check(effects.lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK, "Real completion required");
                }
                check(reports.generate("again").count() == 1, "Same worker recovers");
            }
            target.worker.join(5000);
            check(!target.worker.isAlive(), "Actual record worker must retire");
            jdbc.execute("shutdown");
            System.out.println("NATIVE_RECORD_RESULT_OK shape=closed utf8=bounded jdbc=rollback-before-commit recovery=pass retirement=physical");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
