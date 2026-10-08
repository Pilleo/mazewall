package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual upstream method authorization + real H2 effects on an explicitly UNCONTAINED host worker. */
class SecurityJdbcPlacementTest {
    interface Operation { String run(String mode); }
    static final class Bean implements Operation {
        final JdbcTemplate jdbc;
        final LinkageError fatal = new LinkageError("original security fixture fatal");
        long worker;
        Bean(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override @Transactional(transactionManager = "jdbc")
        @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('ADMIN')")
        public String run(String mode) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            worker = Thread.currentThread().threadId();
            jdbc.update("insert into effects(name) values (?)", mode);
            if (mode.equals("fatal")) throw fatal;
            return SecurityContextHolder.getContext().getAuthentication().getName();
        }
    }
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    static final class IncompatibleRollback implements Operation {
        @Override @Transactional(transactionManager = "jdbc", noRollbackFor = AccessDeniedException.class)
        @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('ADMIN')")
        public String run(String mode) { throw new AssertionError("Incompatible authorization rollback must reject before entry"); }
    }

    static final class UnsupportedFiltering implements Operation {
        @Override @Transactional(transactionManager = "jdbc") @PreFilter("false")
        public String run(String mode) { throw new AssertionError("Unsupported filtering must reject before target entry"); }
    }

    static final class NonTransactionalPostAuthorization implements Operation {
        @Override @Transactional(transactionManager = "jdbc", propagation = Propagation.NOT_SUPPORTED)
        @PostAuthorize("hasRole('ADMIN')")
        public String run(String mode) { throw new AssertionError("Post-authorization without physical transaction must reject"); }
    }

    @Test void nonTransactionalPostAuthorizationRejectsAtBindingRatherThanPromiseAutocommitRollback() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var guard = new SpringTransactionGuard(beans, "jdbc", new SpringTransactionEffects());
        var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(body -> body.proceed(), guard);
        assertThrows(IllegalStateException.class, () -> boundary.createProxy(new NonTransactionalPostAuthorization(), guard.transactionAdvisor(),
                guard.diagnosisAdvisor(), SpringSecurityMethodAdvice.authorizationAdvisor(), List.of(Operation.class.getMethod("run", String.class))));
    }

    @Test void unsupportedFilteringRejectsAtBindingBeforeTarget() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var guard = new SpringTransactionGuard(beans, "jdbc", new SpringTransactionEffects());
        try (var host = new AdvisorPlacementTest.HostDispatch(new java.util.ArrayList<>())) {
            var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(host, guard);
            var rejected = assertThrows(IllegalStateException.class, () -> boundary.createProxy(new UnsupportedFiltering(),
                    guard.transactionAdvisor(), guard.diagnosisAdvisor(), SpringSecurityMethodAdvice.authorizationAdvisor(),
                    List.of(Operation.class.getMethod("run", String.class))));
            assertTrue(rejected.getMessage().contains("PreFilter"), "Reject unsupported feature, not an incidental worker or authentication check");
        }
    }

    @Test void explicitNoRollbackForActualSecurityDenialRejectsAtBinding() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var guard = new SpringTransactionGuard(beans, "jdbc", new SpringTransactionEffects());
        var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(body -> body.proceed(), guard);
        assertThrows(IllegalStateException.class, () -> boundary.createProxy(new IncompatibleRollback(), guard.transactionAdvisor(),
                guard.diagnosisAdvisor(), SpringSecurityMethodAdvice.authorizationAdvisor(), List.of(Operation.class.getMethod("run", String.class))));
    }

    @Test void actualPostAuthorizationRunsInsideCommitAndRestoresAcrossAlternatingPrincipalsAndFatal() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table effects(name varchar(40))");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var effects = new SpringTransactionEffects();
        var guard = new SpringTransactionGuard(beans, "jdbc", effects);
        var contexts = new SpringSecurityContextBridge();
        try (var worker = Executors.newSingleThreadExecutor()) {
            InvocationDispatcher host = body -> {
                try { return worker.submit(contexts.capture(ManagedRuntimeDispatcher.callable(body))).get(5, TimeUnit.SECONDS); }
                catch (ExecutionException failure) { throw failure.getCause(); }
            };
            var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(host, guard);
            var bean = new Bean(jdbc);
            Operation proxy = (Operation) boundary.createProxy(bean, guard.transactionAdvisor(), guard.diagnosisAdvisor(),
                    SpringSecurityMethodAdvice.authorizationAdvisor(), List.of(Operation.class.getMethod("run", String.class)));
            SecurityDelegationTest.principal("admin");
            assertEquals("admin", proxy.run("approved"));
            long physicalWorker = bean.worker;
            assertEquals(SpringTransactionEffects.Outcome.COMMITTED, effects.lastCompletion().transactions().getFirst().outcome());
            SecurityDelegationTest.principal("bob");
            assertThrows(AccessDeniedException.class, () -> proxy.run("postDenied"));
            assertEquals(physicalWorker, bean.worker); // PostAuthorize followed actual body on SAME worker.
            assertEquals(0, jdbc.queryForObject("select count(*) from effects where name = 'postDenied'", Integer.class));
            assertEquals(SpringTransactionEffects.Outcome.ROLLED_BACK, effects.lastCompletion().transactions().getFirst().outcome());
            assertNull(effects.lastCompletion().diagnostic(), "Authorization denial is not relabeled kernel containment");
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            SecurityDelegationTest.principal("admin");
            assertSame(bean.fatal, assertThrows(LinkageError.class, () -> proxy.run("fatal")));
            assertEquals(0, jdbc.queryForObject("select count(*) from effects where name = 'fatal'", Integer.class));
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            assertEquals("admin", proxy.run("afterError"));
            assertEquals(physicalWorker, bean.worker);
            assertEquals(List.of("approved", "afterError"), jdbc.queryForList("select name from effects order by name desc", String.class));
            assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        } finally { jdbc.execute("shutdown"); }
    }
}
