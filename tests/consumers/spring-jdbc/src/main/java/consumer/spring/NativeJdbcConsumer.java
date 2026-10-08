package consumer.spring;

import io.mazewall.enforcer.api.*;
import io.mazewall.enforcer.api.execution.*;
import io.mazewall.spring.*;
import java.nio.file.AccessDeniedException;
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
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real public Maven consumer; installation only in the disposable verify JVM. */
public final class NativeJdbcConsumer {
    private static final RuntimeProfile PROFILE = RuntimeProfile.HOTSPOT_JIT;
    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final String OPERATION = "jdbc-write";
    private static final String DESCRIPTOR = "run(Ljava/lang/String;)Ljava/lang/String;";

    public interface Operation { String run(String mode) throws Exception; }

    public static class JdbcOperation implements Operation {
        final JdbcTemplate jdbc;
        final Path template;
        final Path sibling;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch cancellationSeen = new CountDownLatch(1);
        final CountDownLatch cleanupRelease = new CountDownLatch(1);
        final CountDownLatch bodyEnded = new CountDownLatch(1);
        volatile Thread worker;
        volatile InvocationEvidence evidence;

        public JdbcOperation(JdbcTemplate jdbc, Path template, Path sibling) {
            this.jdbc = jdbc;
            this.template = template;
            this.sibling = sibling;
        }

        @Override @MazewallOperation(OPERATION)
        @Transactional(transactionManager = "jdbc", rollbackFor = InterruptedException.class)
        public String run(String mode) throws Exception {
            worker = Thread.currentThread();
            check(!worker.isVirtual(), "Target must run on an owned platform worker");
            check(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()), "SQL uses manager's SAME datasource");
            evidence = Mazewall.currentInvocationEvidence();
            check(evidence != null, "Actual owned runtime evidence required");
            jdbc.update("insert into effects(name) values (?)", mode);
            switch (mode) {
                case "approved", "postDenied", "afterDenied": return jdbc.queryForObject("select name from effects where name = ?", String.class, mode)
                        + ":" + Files.readString(template);
                case "mutate": Files.writeString(template, "forbidden mutation"); return "UNEXPECTED";
                case "sibling": return Files.readString(sibling);
                case "business": throw new IllegalArgumentException("ordinary fixture business failure");
                case "cancel":
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("Cancellation wait unexpectedly returned");
                    } catch (InterruptedException cancelled) {
                        cancellationSeen.countDown();
                        // Deliberately held lexical cleanup proves caller delivery != physical end.
                        boolean interrupted = false;
                        for (;;) {
                            try { cleanupRelease.await(); break; }
                            catch (InterruptedException again) { interrupted = true; }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                        throw cancelled;
                    } finally { bodyEnded.countDown(); }
                default: throw new IllegalArgumentException("Unknown fixture mode");
            }
        }
    }

    /** Another implementation in the SAME actual JAR: repeating metadata cannot include this Class. */
    public static final class OtherOperation implements Operation {
        @Override @MazewallOperation(OPERATION) @Transactional(transactionManager = "jdbc")
        public String run(String mode) { throw new AssertionError("Uncaptured implementation must never execute"); }
    }

    /** Fixture-only optional collaborator; no Security type in the ordinary consumer's contract/linkage. */
    public interface SecurityFixture {
        Class<? extends JdbcOperation> implementation();
        JdbcOperation target(JdbcTemplate jdbc, Path template, Path sibling);
        InvocationContextBridge contexts();
        Advisor authorization();
        void principal(String name);
        void verifyAlternating(Operation proxy, JdbcOperation target, JdbcTemplate jdbc, SpringTransactionEffects effects) throws Exception;
        void verifyRestoration();
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "measure|verify <fixtureRoot>");
        Path root = Path.of(args[1]).toRealPath();
        boolean securityMode = args[0].equals("measure-security") || args[0].equals("verify-security");
        SecurityFixture security = securityMode ? (SecurityFixture) Class.forName("consumer.spring.SecurityNativeFixture")
                .getConstructor().newInstance() : null;
        Class<?> implementation = security == null ? JdbcOperation.class : security.implementation();
        Path jar = Path.of(implementation.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (args[0].equals("reject-exploded")) {
            check(Files.isDirectory(jar), "Actual exploded CodeSource directory required");
            var explodedPolicy = WorkerPolicyBuilder.denyList(PROFILE).allowFsRead(jar).build();
            try (var unsupported = PreparedOperationResources.resolve(PROFILE, explodedPolicy, JdbcOperation.class, Set.of())) {
                throw new AssertionError("Exploded implementation unexpectedly supported: " + unsupported);
            } catch (IllegalArgumentException unsupported) {
                check(unsupported.getMessage().contains("Nested/exploded implementation origins"),
                        "Actual reviewed core origin rejection required: " + unsupported);
            }
            System.out.println("ACTUAL_EXPLODED_ORIGIN_REJECTED no-fallback");
            return;
        }
        check(Files.isRegularFile(jar) && jar.toString().endsWith(".jar"), "Actual finished ordinary implementation JAR required");
        check(implementation.getClassLoader() == ClassLoader.getSystemClassLoader(), "Supported ordinary JDK app loader");
        if (!securityMode) {
            try {
                Class.forName("org.springframework.security.core.context.SecurityContextHolder");
                throw new AssertionError("Ordinary adapter/starter must not force Security at runtime");
            } catch (ClassNotFoundException absent) { System.out.println("PUBLISHED_SECURITY_ABSENCE_NO_LINKAGE"); }
        }
        var policy = WorkerPolicyBuilder.denyList(PROFILE).allowFsRead(jar, root.resolve("template.txt")).build();
        Class<?> foreignClass = Class.forName("consumer.spring.ForeignJdbcOperation");
        check(!foreignClass.getProtectionDomain().getCodeSource().getLocation().equals(
                JdbcOperation.class.getProtectionDomain().getCodeSource().getLocation()), "Actual second implementation origin required");
        var resources = PreparedOperationResources.resolve(PROFILE, policy, implementation, Set.of(jar, root.resolve("template.txt")));
        try (resources) { // Resolution/review/binding failures must close unconsumed acquisitions as well.
        if (args[0].equals("measure") || args[0].equals("measure-security")) {
            try (resources) {
                var binding = binding(resources.getImplementationSha256(), resources.getResourceGeneration(), implementation, "operation");
                var candidate = new OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(PROFILE, policy), Set.of(), Map.of());
                Files.writeString(root.resolve("candidate.json"), candidate.toJson());
                Files.writeString(root.resolve("candidate-generation.txt"), resources.getResourceGeneration());
                var other = new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration(),
                        OtherOperation.class, "other"), candidate.getPolicy(), Set.of(), Map.of());
                Files.writeString(root.resolve("other-candidate.json"), other.toJson());
                var foreign = new OperationPolicyDocument(binding(resources.getImplementationSha256(), resources.getResourceGeneration(),
                        foreignClass, "foreign"), candidate.getPolicy(), Set.of(), Map.of());
                Files.writeString(root.resolve("foreign-candidate.json"), foreign.toJson());
                var stale = new OperationPolicyDocument(binding("0".repeat(64), resources.getResourceGeneration(),
                        implementation, "operation"), candidate.getPolicy(), Set.of(), Map.of());
                Files.writeString(root.resolve("stale-candidate.json"), stale.toJson());
                System.out.println("MEASURED_CANDIDATE_ONLY jar=" + resources.getImplementationSha256()
                        + " generation=" + resources.getResourceGeneration());
            }
            return;
        }
        check(args[0].equals("verify") || args[0].equals("verify-security"), "Unsupported fixture mode");
        Properties authority = new Properties();
        try (var input = Files.newInputStream(root.resolve("trusted-review.properties"))) { authority.load(input); }
        String json = Files.readString(root.resolve("candidate.json"));
        var expected = binding(authority.getProperty("jarSha256"), authority.getProperty("resourceGeneration"), implementation, "operation");
        var review = new TrustedPolicyReview(authority.getProperty("reviewer"), authority.getProperty("documentSha256"), Set.of());
        var artifact = ReviewedOperationArtifact.load(json, review, expected);
        check(!resources.getImplementationSha256().equals(SpringOperationBinder.classResourceSha256(implementation)),
                "Finished JAR hash must not be substituted with selected class hash");
        check(resources.getImplementationSha256().equals(authority.getProperty("jarSha256")), "External finished JAR expectation");
        var otherReview = new TrustedPolicyReview(authority.getProperty("reviewer"), authority.getProperty("otherDocumentSha256"), Set.of());
        var otherArtifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("other-candidate.json")), otherReview,
                binding(authority.getProperty("jarSha256"), authority.getProperty("resourceGeneration"), OtherOperation.class, "other"));
        var foreignArtifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("foreign-candidate.json")),
                new TrustedPolicyReview(authority.getProperty("reviewer"), authority.getProperty("foreignDocumentSha256"), Set.of()),
                binding(authority.getProperty("jarSha256"), authority.getProperty("resourceGeneration"), foreignClass, "foreign"));
        var staleArtifact = ReviewedOperationArtifact.load(Files.readString(root.resolve("stale-candidate.json")),
                new TrustedPolicyReview(authority.getProperty("reviewer"), authority.getProperty("staleDocumentSha256"), Set.of()),
                binding("0".repeat(64), authority.getProperty("resourceGeneration"), implementation, "operation"));
        verify(root, resources, artifact, otherArtifact, foreignArtifact, foreignClass, security);
        verifyUnusedAndFailedOwnerCleanup(policy, Set.of(jar, root.resolve("template.txt")), artifact, staleArtifact, implementation);
        }
    }

    private static OperationBinding binding(String sha, String generation, Class<?> type, String bean) {
        return new OperationBinding(OPERATION, OperationAdapter.SPRING_METHOD, sha, generation,
                Map.of("beanName", bean, "className", type.getName(), "methodDescriptor", DESCRIPTOR));
    }

    private static void verify(Path root, PreparedOperationResources resources, ReviewedOperationArtifact artifact,
                               ReviewedOperationArtifact otherArtifact, ReviewedOperationArtifact foreignArtifact,
                               Class<?> foreignClass, SecurityFixture security) throws Exception {
        JdbcDataSource data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:ordinaryJar;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(data);
        jdbc.execute("create table effects(name varchar(40))");
        jdbc.update("insert into effects(name) values ('trusted-warmup')");
        jdbc.queryForList("select * from effects");
        jdbc.update("delete from effects");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var effects = new SpringTransactionEffects();
        var guard = new SpringTransactionGuard(beans, "jdbc", effects);
        // Explicit trusted application bootstrap: never installed by starter/binder/lifecycle.
        try (var runtime = ManagedRuntime.open(PROFILE, Policy.NO_EXEC_HOTSPOT, BUDGET, BUDGET)) {
            var lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 4), resources, Duration.ofMillis(50),
                    security == null ? InvocationContextBridge.none() : security.contexts());
            lifecycle.start();
            resources.close(); // Transferred close cannot revoke genuine live worker ownership.
            var binder = new SpringOperationBinder(beans, lifecycle, guard);
            var target = security == null ? new JdbcOperation(jdbc, root.resolve("template.txt"), root.resolve("sibling.txt"))
                    : security.target(jdbc, root.resolve("template.txt"), root.resolve("sibling.txt"));
            Operation proxy = proxy(beans, "operation", target, binder, guard, security == null ?
                    new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()) : security.authorization());
            var advisors = ((Advised) proxy).getAdvisors();
            binder.bind(approval(artifact, proxy));
            binder.validateRequiredBindings(List.of("operation"));
            check(beans.getBean("operation") == proxy, "Actual registered singleton retained");
            check(java.util.Arrays.equals(advisors, ((Advised) proxy).getAdvisors()), "Original advisors retained");
            if (security != null) security.principal("admin");
            check(proxy.run("approved").equals("approved:approved template"), "Actual native owned call and JdbcTemplate result");
            check(target.worker != Thread.currentThread(), "Worker offload");
            check(effects.lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.COMMITTED,
                    "Real JDBC commit receipt");
            verifyUncapturedClassRejected(beans, lifecycle, guard, otherArtifact, new OtherOperation(), "other");
            verifyUncapturedClassRejected(beans, lifecycle, guard, foreignArtifact,
                    (Operation) foreignClass.getConstructor().newInstance(), "foreign");
            deny(proxy, "mutate", jdbc, effects);
            deny(proxy, "sibling", jdbc, effects);
            check(Files.readString(root.resolve("template.txt")).equals("approved template"), "Denied template mutation left bytes intact");
            check(jdbc.queryForList("select name from effects", String.class).equals(List.of("approved")), "Denied SQL was rolled back");
            if (security != null) security.verifyAlternating(proxy, target, jdbc, effects);
            verifyCancellation(proxy, target, lifecycle, resources, jdbc, effects, security);
            if (security != null) security.verifyRestoration();
            System.out.println("NATIVE_ORDINARY_JAR_PASS approved/JDBC/template-denial/sibling-denial/cancel/physical-retirement");
        } finally {
            resources.close();
            jdbc.execute("shutdown");
        }
    }

    private static SpringOperationBinder.Approval approval(ReviewedOperationArtifact artifact, Object bean) {
        return new SpringOperationBinder.Approval(artifact, bean, JdbcOperation.class.getClassLoader(), PROFILE,
                artifact.getBinding().getResourceGeneration(), Set.of(SpringOperationBinder.Phase.BODY,
                SpringOperationBinder.Phase.TRANSACTION_COMPLETION));
    }

    private static Operation proxy(DefaultListableBeanFactory beans, String name, Operation target,
                                   SpringOperationBinder binder, SpringTransactionGuard guard) {
        return proxy(beans, name, target, binder, guard,
                new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
    }

    private static Operation proxy(DefaultListableBeanFactory beans, String name, Operation target,
                                   SpringOperationBinder binder, SpringTransactionGuard guard, Advisor authorization) {
        var factory = new ProxyFactory(target);
        factory.addAdvisor(binder.dispatchAdvisor());
        factory.addAdvisor(guard.transactionAdvisor());
        factory.addAdvisor(guard.diagnosisAdvisor());
        factory.addAdvisor(authorization);
        factory.setFrozen(true);
        Operation proxy = (Operation) factory.getProxy();
        beans.registerSingleton(name, proxy);
        return proxy;
    }

    private static void verifyUncapturedClassRejected(DefaultListableBeanFactory beans, DomainLifecycle lifecycle,
            SpringTransactionGuard guard, ReviewedOperationArtifact otherArtifact, Operation target, String name) throws Exception {
        var binder = SpringOperationBinder.inApprovedCommonDomain(beans, lifecycle, otherArtifact, guard);
        var other = proxy(beans, name, target, binder, guard);
        try {
            binder.bind(approval(otherArtifact, other));
            throw new AssertionError("Metadata equality admitted another actual implementation Class");
        } catch (IllegalStateException unsupported) {
            check(unsupported.getMessage().contains("not included"), "Reject missing actual owner, not incidental metadata: " + unsupported);
        }
        System.out.println("UNCAPTURED_ACTUAL_CLASS_REJECTED " + name + " same-policy/same-generation; actual origin="
                + target.getClass().getProtectionDomain().getCodeSource().getLocation());
    }

    private static void verifyUnusedAndFailedOwnerCleanup(Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy,
            Set<Path> strictPaths, ReviewedOperationArtifact artifact, ReviewedOperationArtifact staleArtifact, Class<?> implementation) {
        var unused = PreparedOperationResources.resolve(PROFILE, policy, implementation, strictPaths);
        try (var runtime = ManagedRuntime.open(PROFILE, Policy.NO_EXEC_HOTSPOT, BUDGET, BUDGET)) {
            var lifecycle = new DomainLifecycle(runtime, artifact, new DomainLimits(1, 1), unused, BUDGET);
            lifecycle.stop(); // Never claimed: lifecycle closes the genuine acquisitions.
            assertClosed(unused);
            check(lifecycle.lastDrain() == DrainReport.Complete.INSTANCE, "Never-started lifecycle drains explicit owner");
        } finally { unused.close(); }
        var failed = PreparedOperationResources.resolve(PROFILE, policy, implementation, strictPaths);
        try (var runtime = ManagedRuntime.open(PROFILE, Policy.NO_EXEC_HOTSPOT, BUDGET, BUDGET)) {
            var lifecycle = new DomainLifecycle(runtime, staleArtifact, new DomainLimits(1, 1), failed, BUDGET);
            try { lifecycle.start(); throw new AssertionError("Stale external implementation expectation accepted"); }
            catch (IllegalArgumentException mismatch) { /* Core compares genuine finished-JAR identity before readiness. */ }
            check(!lifecycle.isRunning(), "Failed preparation cannot be ready");
            assertClosed(failed);
            lifecycle.stop();
            check(lifecycle.lastDrain() == DrainReport.Complete.INSTANCE, "Preparation failure retired all actual resources");
        } finally { failed.close(); }
        System.out.println("UNCLAIMED_STOP_AND_FAILED_PREPARATION_CLOSE_PASS");
    }

    private static void assertClosed(PreparedOperationResources resources) {
        try { resources.validate(); throw new AssertionError("Unclaimed genuine resource owner leaked"); }
        catch (IllegalStateException closed) { /* Public owner has physically released its acquisitions. */ }
    }

    private static void deny(Operation proxy, String mode, JdbcTemplate jdbc, SpringTransactionEffects effects) throws Exception {
        Throwable failure;
        try { proxy.run(mode); throw new AssertionError("Kernel filesystem denial required: " + mode); }
        catch (Exception denied) { failure = denied; }
        boolean accessDenied = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException) accessDenied = true;
        }
        check(accessDenied, "Actual JDK AccessDenied from denied open, not a fabricated exception: " + failure);
        check(jdbc.queryForObject("select count(*) from effects where name = ?", Integer.class, mode) == 0, "Denied SQL rollback");
        check(effects.lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK,
                "Real denied transaction completion");
        System.out.println("OBSERVED_FILESYSTEM_OPEN_DENIED " + mode + "; library diagnostic=" + effects.lastCompletion().diagnostic());
    }

    private static void verifyCancellation(Operation proxy, JdbcOperation target, DomainLifecycle lifecycle,
            PreparedOperationResources resources, JdbcTemplate jdbc, SpringTransactionEffects effects, SecurityFixture security) throws Exception {
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().name("fixture-virtual-servlet-caller").start(() -> {
            if (security != null) security.principal("admin");
            try { proxy.run("cancel"); callerFailure.set(new AssertionError("Cancelled call returned success")); }
            catch (Throwable failure) { callerFailure.set(failure); }
        });
        try {
            check(target.entered.await(5, TimeUnit.SECONDS), "Actual worker entered SQL/cancel body");
            caller.interrupt();
            caller.join(5000);
            check(!caller.isAlive() && callerFailure.get() != null, "Caller delivery ended with interruption");
            check(target.cancellationSeen.await(5, TimeUnit.SECONDS), "Cooperative worker interrupt observed");
            lifecycle.stop();
            check(lifecycle.lastDrain() instanceof DrainReport.Incomplete, "Held cleanup must report Incomplete, not physical end");
            check(target.bodyEnded.getCount() == 1 && target.worker.isAlive(), "Actual physical worker/body remains live");
            resources.validate(); // Retained owner must still be usable throughout unfinished cleanup.
        } finally { target.cleanupRelease.countDown(); }
        check(target.bodyEnded.await(5, TimeUnit.SECONDS), "Lexical body cleanup finished");
        target.worker.join(5000);
        check(!target.worker.isAlive(), "Actual captured physical worker terminated");
        lifecycle.stop();
        check(lifecycle.lastDrain() == DrainReport.Complete.INSTANCE, "Actual owned runtime/manager/resource physical retirement");
        try { resources.validate(); throw new AssertionError("Retired resource owner stayed available"); }
        catch (IllegalStateException retired) { /* Exact public owner closes only after physical retirement. */ }
        check(jdbc.queryForObject("select count(*) from effects where name = 'cancel'", Integer.class) == 0, "Cancelled SQL rollback");
        check(effects.lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK,
                "Actual cancellation transaction completion");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
