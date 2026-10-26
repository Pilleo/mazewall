package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.RuntimeProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Real Spring/H2 host characterization, not native containment evidence. */
class JdbcProxyFactoryTest {
    interface Operation { String run(); }
    static class Service implements Operation {
        final JdbcTemplate jdbc;
        final AtomicInteger calls = new AtomicInteger();
        Service(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @MazewallOperation("write") @Transactional(transactionManager = "jdbc")
        public String run() { calls.incrementAndGet(); jdbc.update("insert into effects values ('one')"); return "committed"; }
    }

    private static final class Fixture implements AutoCloseable {
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final AdvisorPlacementTest.HostDispatch dispatch = new AdvisorPlacementTest.HostDispatch(new ArrayList<>());
        final JdbcTemplate jdbc;
        final Service target;
        final ReviewedOperationArtifact artifact;
        final SpringTransactionGuard guard;
        final SpringOperationBinder binder;
        final Advisor authorization = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed());
        Fixture() throws Exception {
            var data = new JdbcDataSource();
            data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            jdbc = new JdbcTemplate(data);
            jdbc.execute("create table effects(name varchar(20))");
            target = new Service(jdbc);
            artifact = OperationBindingTest.artifact(target, Service.class.getMethod("run"), "operation",
                    SpringOperationBinder.classResourceSha256(Service.class));
            beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
            guard = new SpringTransactionGuard(beans, "jdbc", new SpringTransactionEffects());
            binder = SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, artifact, guard);
        }
        SpringOperationBinder.Approval approval(Object proxy, ClassLoader loader, String generation) {
            return new SpringOperationBinder.Approval(artifact, proxy, loader, RuntimeProfile.HOTSPOT_JIT, generation,
                    Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION));
        }
        @Override public void close() { dispatch.close(); jdbc.execute("shutdown"); }
    }

    @Test void assembledProxyIsFrozenButUnapprovedUntilExactRegisteredBinding() throws Exception {
        try (var f = new Fixture()) {
            var manager = f.beans.getBean("jdbc");
            Operation proxy = f.binder.createJdbcProxy(Operation.class, f.target, f.authorization);
            Advised advised = (Advised) proxy;
            assertTrue(advised.isFrozen());
            assertTrue(java.lang.reflect.Proxy.isProxyClass(proxy.getClass()));
            assertSame(f.target, advised.getTargetSource().getTarget());
            assertArrayEquals(new Advisor[] {f.binder.dispatchAdvisor(), f.guard.transactionAdvisor(),
                    f.guard.diagnosisAdvisor(), f.authorization}, advised.getAdvisors());
            assertFalse(f.beans.containsSingleton("operation"), "Construction must not publish a bean");
            assertThrows(IllegalStateException.class, proxy::run);
            f.beans.registerSingleton("operation", proxy);
            assertThrows(IllegalStateException.class, () -> f.binder.validateRequiredBindings(List.of("operation")));
            assertEquals(0, f.target.calls.get());
            assertSame(proxy, f.binder.bind(f.approval(proxy, Service.class.getClassLoader(), "resources-v1")));
            f.binder.validateRequiredBindings(List.of("operation"));
            assertEquals("committed", proxy.run());
            assertSame(manager, f.beans.getBean("jdbc"));
            assertEquals(List.of("one"), f.jdbc.queryForList("select name from effects", String.class));
            assertThrows(IllegalStateException.class, () -> f.binder.createJdbcProxy(Operation.class, f.target, f.authorization));
        }
    }

    @Test void factoryRejectsClassContractsNestedProxiesAndMissingJdbcGuard() throws Exception {
        try (var f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.binder.createJdbcProxy(Service.class, f.target, f.authorization));
            Operation nested = (Operation) new ProxyFactory(f.target).getProxy();
            assertThrows(IllegalArgumentException.class, () -> f.binder.createJdbcProxy(Operation.class, nested, f.authorization));
            Operation plain = (Operation) java.lang.reflect.Proxy.newProxyInstance(Operation.class.getClassLoader(),
                    new Class<?>[] {Operation.class}, (proxy, method, args) -> "uncontained");
            assertThrows(IllegalArgumentException.class, () -> f.binder.createJdbcProxy(Operation.class, plain, f.authorization));
            var withoutJdbc = SpringOperationBinder.forTrustedHostCharacterization(f.beans, f.dispatch, f.artifact);
            assertThrows(IllegalStateException.class, () -> withoutJdbc.createJdbcProxy(Operation.class, f.target, f.authorization));
            assertEquals(0, f.target.calls.get());
        }
    }

    @Test void helperCannotBypassActualSingletonLoaderOrResourceApproval() throws Exception {
        try (var f = new Fixture()) {
            Operation proxy = f.binder.createJdbcProxy(Operation.class, f.target, f.authorization);
            f.beans.registerSingleton("operation", proxy);
            assertThrows(IllegalStateException.class, () -> f.binder.bind(f.approval(f.target, Service.class.getClassLoader(), "resources-v1")));
            assertThrows(IllegalStateException.class, () -> f.binder.bind(f.approval(proxy, new ClassLoader() {}, "resources-v1")));
            assertThrows(IllegalStateException.class, () -> f.binder.bind(f.approval(proxy, Service.class.getClassLoader(), "resources-v2")));
            assertThrows(IllegalStateException.class, proxy::run);
            assertEquals(0, f.target.calls.get());
            assertEquals(0, f.jdbc.queryForObject("select count(*) from effects", Integer.class));
        }
    }

    @Test void helperRetainsInvocationTimeAdvisorAndSingletonDriftChecks() throws Exception {
        for (boolean substituteBean : List.of(false, true)) {
            try (var f = new Fixture()) {
                Operation proxy = f.binder.createJdbcProxy(Operation.class, f.target, f.authorization);
                f.beans.registerSingleton("operation", proxy);
                f.binder.bind(f.approval(proxy, Service.class.getClassLoader(), "resources-v1"));
                if (substituteBean) {
                    f.beans.destroySingleton("operation");
                    f.beans.registerSingleton("operation", f.target);
                } else {
                    ((DefaultPointcutAdvisor) f.authorization).setAdvice((MethodInterceptor) invocation -> "bypass");
                }
                assertThrows(IllegalStateException.class, proxy::run);
                assertEquals(0, f.target.calls.get());
                assertEquals(0, f.jdbc.queryForObject("select count(*) from effects", Integer.class));
            }
        }
    }
}
