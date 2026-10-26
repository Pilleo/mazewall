package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Composes reviewed binding with actual JDBC advice, rather than proving the slices only in isolation. */
class ReviewedJdbcBindingTest {
    interface Operation { String run(); }
    static class Outer implements Operation {
        final JdbcTemplate jdbc;
        Operation inner;
        long worker;
        RuntimeException failure;
        Outer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @MazewallOperation("outer") @Transactional(transactionManager = "jdbc")
        public String run() {
            worker = Thread.currentThread().threadId();
            jdbc.update("insert into effects values ('outer')");
            inner.run();
            if (failure != null) throw failure;
            return "outer";
        }
    }
    static class Inner implements Operation {
        final JdbcTemplate jdbc;
        long worker;
        Inner(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @MazewallOperation("inner") @Transactional(transactionManager = "jdbc", propagation = Propagation.REQUIRES_NEW)
        public String run() {
            worker = Thread.currentThread().threadId();
            jdbc.update("insert into effects values ('inner')");
            return "inner";
        }
    }
    static ReviewedOperationArtifact review(Object target, String name, CanonicalPolicyArtifact policy, String generation) throws Exception {
        var binding = new OperationBinding(name, OperationAdapter.SPRING_METHOD,
                SpringOperationBinder.classResourceSha256(target.getClass()), generation,
                Map.of("beanName", name, "className", target.getClass().getName(), "methodDescriptor", "run()Ljava/lang/String;"));
        var document = new OperationPolicyDocument(binding, policy, Set.of(), Map.of());
        return ReviewedOperationArtifact.load(document.toJson(), new TrustedPolicyReview("application review", document.sha256(), Set.of()), binding);
    }
    static Object register(DefaultListableBeanFactory beans, String name, Object target,
                           SpringOperationBinder binder) throws Exception {
        Object proxy = binder.createJdbcProxy(Operation.class, (Operation) target,
                new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
        beans.registerSingleton(name, proxy);
        return proxy;
    }
    static SpringOperationBinder.Approval approval(ReviewedOperationArtifact artifact, Object proxy) {
        return new SpringOperationBinder.Approval(artifact, proxy, Outer.class.getClassLoader(), RuntimeProfile.HOTSPOT_JIT,
                "resources-v1", Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION));
    }

    @Test void independentlyReviewedBeansExplicitlyShareOneFixedDomainAndRetainActualJdbcEffects() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table effects(name varchar(20))");
        try (var dispatch = new AdvisorPlacementTest.HostDispatch(new ArrayList<>())) {
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("jdbc", new DataSourceTransactionManager(ds));
            var effects = new SpringTransactionEffects();
            var guard = new SpringTransactionGuard(beans, "jdbc", effects);
            var policy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build());
            var outer = new Outer(jdbc);
            var inner = new Inner(jdbc);
            var outerReview = review(outer, "outer", policy, "resources-v1");
            var innerReview = review(inner, "inner", policy, "resources-v1");
            var outerBinder = SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, outerReview, guard);
            var innerBinder = SpringOperationBinder.forTrustedHostCharacterizationInCommonDomain(beans, dispatch,
                    outerReview, innerReview, guard);
            var outerProxy = (Operation) register(beans, "outer", outer, outerBinder);
            var innerProxy = (Operation) register(beans, "inner", inner, innerBinder);
            outerBinder.bind(approval(outerReview, outerProxy));
            innerBinder.bind(approval(innerReview, innerProxy));
            outerBinder.validateRequiredBindings(java.util.List.of("outer"));
            innerBinder.validateRequiredBindings(java.util.List.of("inner"));
            outer.inner = innerProxy;
            var failure = new IllegalArgumentException("outer business failure");
            outer.failure = failure;
            assertSame(failure, assertThrows(IllegalArgumentException.class, outerProxy::run));
            assertEquals(outer.worker, inner.worker);
            assertNotEquals(Thread.currentThread().threadId(), outer.worker);
            assertSame(outerProxy, beans.getBean("outer"));
            assertSame(innerProxy, beans.getBean("inner"));
            assertEquals(java.util.List.of("inner"), jdbc.queryForList("select name from effects", String.class));
            assertEquals(java.util.List.of(SpringTransactionEffects.Outcome.ROLLED_BACK, SpringTransactionEffects.Outcome.COMMITTED),
                    effects.lastCompletion().transactions().stream().map(SpringTransactionEffects.TransactionEffect::outcome).toList());
            var otherResources = review(inner, "inner", policy, "resources-v2");
            assertThrows(IllegalStateException.class, () -> SpringOperationBinder.forTrustedHostCharacterizationInCommonDomain(
                    beans, dispatch, outerReview, otherResources, guard));
            var otherPolicy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT,
                    WorkerPolicyBuilder.allowList(RuntimeProfile.HOTSPOT_JIT).build());
            var otherPermissions = review(inner, "inner", otherPolicy, "resources-v1");
            assertThrows(IllegalStateException.class, () -> SpringOperationBinder.forTrustedHostCharacterizationInCommonDomain(
                    beans, dispatch, outerReview, otherPermissions, guard));
        } finally { jdbc.execute("shutdown"); }
    }
}
