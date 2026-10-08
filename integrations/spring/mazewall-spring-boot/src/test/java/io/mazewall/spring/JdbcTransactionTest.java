package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.ContainmentViolationEvidence;
import java.nio.file.AccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcTransactionTest {
    interface Operations {
        String outer() throws AccessDeniedException;
        String required();
        String requiresNew();
        String nested();
        String supports();
        String mandatory();
        String notSupported();
        String never();
        String denied() throws AccessDeniedException;
        String deniedRequired() throws AccessDeniedException;
        String deniedNew() throws AccessDeniedException;
        String deniedNested() throws AccessDeniedException;
        String incompatible();
        String checked() throws Exception;
    }

    static class Bean implements Operations {
        final JdbcTemplate jdbc;
        final List<Long> bodyThreads = new ArrayList<>();
        final List<Boolean> activeTransactions = new ArrayList<>();
        Runnable outerBody = () -> {};
        Runnable innerBody = () -> {};
        final AccessDeniedException denial = new AccessDeniedException("fixture-resource");
        final Exception checkedBusiness = new Exception("ordinary checked business failure");
        Bean(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        String insert(String name) {
            bodyThreads.add(Thread.currentThread().threadId());
            activeTransactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("insert into effects(name) values (?)", name);
            innerBody.run();
            return name;
        }
        @Transactional(transactionManager = "jdbc") public String outer() { insert("outer"); outerBody.run(); return "outer"; }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.REQUIRED) public String required() { return insert("required"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.REQUIRES_NEW) public String requiresNew() { return insert("requiresNew"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.NESTED) public String nested() { return insert("nested"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.SUPPORTS) public String supports() { return insert("supports"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.MANDATORY) public String mandatory() { return insert("mandatory"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.NOT_SUPPORTED) public String notSupported() { return insert("notSupported"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.NEVER) public String never() { return insert("never"); }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.NOT_SUPPORTED)
        public String denied() throws AccessDeniedException { throw denial; }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.REQUIRED)
        public String deniedRequired() throws AccessDeniedException { insert("deniedRequired"); throw denial; }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.REQUIRES_NEW)
        public String deniedNew() throws AccessDeniedException { insert("deniedNew"); throw denial; }
        @Transactional(transactionManager = "jdbc", propagation = Propagation.NESTED)
        public String deniedNested() throws AccessDeniedException { insert("deniedNested"); throw denial; }
        @Transactional(transactionManager = "jdbc", noRollbackFor = RuntimeException.class)
        public String incompatible() { return insert("incompatible"); }
        @Transactional(transactionManager = "jdbc")
        public String checked() throws Exception { insert("checked"); throw checkedBusiness; }
    }

    enum Fault { NONE, COMMIT_FAILURE, NO_SAVEPOINTS, EQUAL_DATA_SOURCE }

    static final class Fixture implements AutoCloseable {
        final AdvisorPlacementTest.HostDispatch dispatch = new AdvisorPlacementTest.HostDispatch(new ArrayList<>());
        final JdbcDataSource dataSource = new JdbcDataSource();
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final SpringTransactionEffects effects = new SpringTransactionEffects();
        final SpringTransactionGuard guard;
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final TransactionInterceptor transactionInterceptor;
        final java.util.concurrent.atomic.AtomicReference<Throwable> dispatchFailure = new java.util.concurrent.atomic.AtomicReference<>();
        final Bean bean;
        final Operations proxy;
        Fixture(boolean jdbcManager) throws Exception { this(jdbcManager, false, Fault.NONE); }
        Fixture(boolean jdbcManager, boolean includeIncompatible) throws Exception { this(jdbcManager, includeIncompatible, Fault.NONE); }
        Fixture(boolean jdbcManager, boolean includeIncompatible, Fault fault) throws Exception {
            dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table effects(name varchar(40))");
            javax.sql.DataSource actual = fault == Fault.NONE ? dataSource : new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException { return faultConnection(dataSource.getConnection(), fault); }
                @Override public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
                    return faultConnection(dataSource.getConnection(username, password), fault);
                }
                @Override public boolean equals(Object other) { return fault == Fault.EQUAL_DATA_SOURCE ? other instanceof javax.sql.DataSource : this == other; }
                @Override public int hashCode() { return fault == Fault.EQUAL_DATA_SOURCE ? 1 : System.identityHashCode(this); }
            };
            manager = jdbcManager ? new JdbcTransactionManager(actual) : new DataSourceTransactionManager(actual);
            beans.registerSingleton("jdbc", manager);
            guard = new SpringTransactionGuard(beans, "jdbc", effects);
            InvocationDispatcher recordingDispatch = body -> {
                try { return dispatch.call(body); }
                catch (Throwable failure) { dispatchFailure.set(failure); throw failure; }
            };
            var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(recordingDispatch, guard);
            var transaction = guard.transactionAdvisor();
            transactionInterceptor = (TransactionInterceptor) transaction.getAdvice();
            bean = new Bean(jdbc);
            List<java.lang.reflect.Method> approved = new ArrayList<>();
            for (var method : Operations.class.getMethods()) {
                if (includeIncompatible || !method.getName().equals("incompatible")) approved.add(method);
            }
            try {
                proxy = (Operations) boundary.createProxy(bean, transaction, guard.diagnosisAdvisor(),
                        new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()), approved);
            } catch (Exception | Error failure) {
                dispatch.close();
                jdbc.execute("shutdown");
                throw failure;
            }
        }
        int count(String name) { return jdbc.queryForObject("select count(*) from effects where name = ?", Integer.class, name); }
        @Override public void close() { dispatch.close(); jdbc.execute("shutdown"); }
    }

    /** Fault injection around a real H2 connection; real JDBC transaction advice remains in use. */
    static java.sql.Connection faultConnection(java.sql.Connection connection, Fault fault) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(JdbcTransactionTest.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                    if (fault == Fault.COMMIT_FAILURE && method.getName().equals("commit")) {
                        throw new java.sql.SQLException("driver commit acknowledgement failed");
                    }
                    try {
                        Object value = method.invoke(connection, arguments);
                        if (fault == Fault.NO_SAVEPOINTS && method.getName().equals("getMetaData")) {
                            return java.lang.reflect.Proxy.newProxyInstance(JdbcTransactionTest.class.getClassLoader(),
                                    new Class<?>[]{java.sql.DatabaseMetaData.class}, (metadataProxy, metadataMethod, metadataArguments) -> {
                                        if (metadataMethod.getName().equals("supportsSavepoints")) return false;
                                        try { return metadataMethod.invoke(value, metadataArguments); }
                                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                        }
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    static String inner(Operations proxy, Propagation propagation) {
        return switch (propagation) {
            case REQUIRED -> proxy.required();
            case REQUIRES_NEW -> proxy.requiresNew();
            case NESTED -> proxy.nested();
            case SUPPORTS -> proxy.supports();
            case MANDATORY -> proxy.mandatory();
            case NOT_SUPPORTED -> proxy.notSupported();
            case NEVER -> proxy.never();
        };
    }

    @ParameterizedTest @EnumSource(Propagation.class)
    void allSevenPropagationsUseActualJdbcAdviceOnOnePhysicalWorker(Propagation propagation) throws Exception {
        try (var fixture = new Fixture(false)) {
            long caller = Thread.currentThread().threadId();
            fixture.bean.outerBody = () -> {
                if (propagation == Propagation.NEVER) {
                    assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                            () -> inner(fixture.proxy, propagation));
                } else inner(fixture.proxy, propagation);
            };
            assertEquals("outer", fixture.proxy.outer());
            assertEquals(1, fixture.count("outer"));
            assertTrue(fixture.bean.bodyThreads.stream().allMatch(id -> id != caller && id.equals(fixture.bean.bodyThreads.getFirst())));
            if (propagation != Propagation.NEVER) {
                assertEquals(2, fixture.bean.bodyThreads.size());
                assertEquals(propagation != Propagation.NOT_SUPPORTED, fixture.bean.activeTransactions.getLast());
            } else {
                assertEquals(1, fixture.bean.bodyThreads.size());
                assertEquals("never", fixture.proxy.never()); // NEVER works without a transaction.
            }
            System.out.println("S2 HOST JDBC " + propagation + " worker=" + fixture.bean.bodyThreads + " effects=" + fixture.effects.lastCompletion());
        }
    }

    @Test void jdbcTransactionManagerAndNormalBusinessRollbackPreserveIdentity() throws Exception {
        try (var fixture = new Fixture(true)) {
            var business = new IllegalArgumentException("business");
            fixture.bean.outerBody = () -> { throw business; };
            assertSame(business, assertThrows(IllegalArgumentException.class, fixture.proxy::outer));
            assertEquals(0, fixture.count("outer"));
            assertEquals(SpringTransactionEffects.Outcome.ROLLED_BACK, fixture.effects.lastCompletion().transactions().getFirst().outcome());
            assertNull(fixture.effects.lastCompletion().diagnostic());
        }
    }

    @Test void independentlyCommittedInnerTransactionIsNotInventedlyRolledBack() throws Exception {
        try (var fixture = new Fixture(false)) {
            var business = new IllegalArgumentException("outer failed");
            fixture.bean.outerBody = () -> { fixture.proxy.requiresNew(); throw business; };
            assertSame(business, assertThrows(IllegalArgumentException.class, fixture.proxy::outer));
            assertEquals(0, fixture.count("outer"));
            assertEquals(1, fixture.count("requiresNew"));
            assertEquals(List.of(SpringTransactionEffects.Outcome.ROLLED_BACK, SpringTransactionEffects.Outcome.COMMITTED),
                    fixture.effects.lastCompletion().transactions().stream().map(SpringTransactionEffects.TransactionEffect::outcome).toList());
        }
    }

    @Test void caughtDiagnosedInnerFailurePersistsAndRollsBackStillActiveOuterTransaction() throws Exception {
        try (var fixture = new Fixture(false)) {
            fixture.bean.outerBody = () -> {
                try { fixture.proxy.denied(); } catch (AccessDeniedException expected) { assertSame(fixture.bean.denial, expected); }
            };
            assertSame(fixture.bean.denial, assertThrows(AccessDeniedException.class, fixture.proxy::outer));
            assertEquals(0, fixture.count("outer"));
            assertEquals(ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE, fixture.effects.lastCompletion().diagnostic().getEvidence());
        }
    }

    @Test void beforeCommitGateSeesRecordedFailureFromEarlierCallback() throws Exception {
        try (var fixture = new Fixture(false)) {
            fixture.bean.outerBody = () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public int getOrder() { return 0; }
                @Override public void beforeCommit(boolean readOnly) {
                    try { fixture.proxy.denied(); } catch (AccessDeniedException expected) { /* Library diagnosis retained it. */ }
                }
            });
            assertSame(fixture.bean.denial, assertThrows(AccessDeniedException.class, fixture.proxy::outer));
            assertEquals(0, fixture.count("outer"));
        }
    }

    @Test void afterCommitFailureLeavesActualCommittedOutcome() throws Exception {
        try (var fixture = new Fixture(false)) {
            var failure = new IllegalStateException("afterCommit failed");
            fixture.bean.outerBody = () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw failure; }
            });
            assertSame(failure, assertThrows(IllegalStateException.class, fixture.proxy::outer));
            assertEquals(1, fixture.count("outer"));
            assertEquals(SpringTransactionEffects.Outcome.COMMITTED, fixture.effects.lastCompletion().transactions().getFirst().outcome());
        }
    }

    @Test void externalActualTransactionSynchronizationAndResourcesFailBeforeHop() throws Exception {
        try (var fixture = new Fixture(false)) {
            new TransactionTemplate(fixture.manager).executeWithoutResult(status ->
                    assertThrows(IllegalStateException.class, fixture.proxy::outer));
            TransactionSynchronizationManager.initSynchronization();
            try { assertThrows(IllegalStateException.class, fixture.proxy::outer); }
            finally { TransactionSynchronizationManager.clearSynchronization(); }
            Object key = new Object();
            TransactionSynchronizationManager.bindResource(key, new Object());
            try { assertThrows(IllegalStateException.class, fixture.proxy::outer); }
            finally { TransactionSynchronizationManager.unbindResource(key); }
            assertEquals(0, fixture.bean.bodyThreads.size());
        }
    }

    @Test void incompatibleEffectiveRollbackOverrideIsRejectedBeforeTargetEntry() {
        assertThrows(IllegalStateException.class, () -> { try (var ignored = new Fixture(false, true)) {} });
    }

    @Test void ordinaryCheckedBusinessFailureKeepsDefaultSpringCommitSemantics() throws Exception {
        try (var fixture = new Fixture(false)) {
            assertSame(fixture.bean.checkedBusiness, assertThrows(Exception.class, fixture.proxy::checked));
            assertEquals(1, fixture.count("checked"));
            assertEquals(SpringTransactionEffects.Outcome.COMMITTED, fixture.effects.lastCompletion().transactions().getFirst().outcome());
            assertNull(fixture.effects.lastCompletion().diagnostic());
        }
    }

    @Test void nestedSavepointRollbackDoesNotRollBackSuccessfulOuterBusinessTransaction() throws Exception {
        try (var fixture = new Fixture(false)) {
            var business = new IllegalStateException("nested business failure");
            fixture.bean.outerBody = () -> {
                fixture.bean.innerBody = () -> { throw business; };
                try { assertSame(business, assertThrows(IllegalStateException.class, fixture.proxy::nested)); }
                finally { fixture.bean.innerBody = () -> {}; }
            };
            fixture.proxy.outer();
            assertEquals(1, fixture.count("outer"));
            assertEquals(0, fixture.count("nested"));
            var transactions = fixture.effects.lastCompletion().transactions();
            assertEquals(SpringTransactionEffects.Outcome.COMMITTED, transactions.getFirst().outcome());
            assertEquals(SpringTransactionEffects.Kind.SAVEPOINT, transactions.getLast().kind());
            assertEquals(SpringTransactionEffects.Outcome.ROLLED_BACK, transactions.getLast().outcome());
        }
    }

    @Test void unsupportedDriverSavepointsRejectNestedBeforeItsBody() throws Exception {
        try (var fixture = new Fixture(false, false, Fault.NO_SAVEPOINTS)) {
            fixture.bean.outerBody = () -> assertThrows(org.springframework.transaction.NestedTransactionNotSupportedException.class, fixture.proxy::nested);
            fixture.proxy.outer();
            assertEquals(1, fixture.bean.bodyThreads.size());
            assertEquals(0, fixture.count("nested"));
            assertEquals(1, fixture.count("outer"));
        }
    }

    @Test void driverCommitFailureIsUnknownRatherThanInventedRollback() throws Exception {
        try (var fixture = new Fixture(false, false, Fault.COMMIT_FAILURE)) {
            assertThrows(org.springframework.transaction.TransactionSystemException.class, fixture.proxy::outer);
            assertEquals(SpringTransactionEffects.Outcome.UNKNOWN, fixture.effects.lastCompletion().transactions().getFirst().outcome());
            assertNotNull(fixture.effects.lastCompletion().executionFailure());
        }
    }

    @Test void topLevelMandatoryFailsAndNonTransactionalModesKeepTheirSemantics() throws Exception {
        for (var propagation : List.of(Propagation.MANDATORY, Propagation.SUPPORTS, Propagation.NOT_SUPPORTED, Propagation.NEVER)) {
            try (var fixture = new Fixture(false)) {
                if (propagation == Propagation.MANDATORY) {
                    assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                            () -> inner(fixture.proxy, propagation));
                    assertTrue(fixture.bean.bodyThreads.isEmpty());
                } else {
                    inner(fixture.proxy, propagation);
                    assertFalse(fixture.bean.activeTransactions.getFirst());
                }
                assertTrue(fixture.effects.lastCompletion().transactions().isEmpty());
            }
        }
    }

    @Test void crossDomainAndManagerConfigurationDriftRejectBeforeNestedBody() throws Exception {
        try (var first = new Fixture(false); var second = new Fixture(false)) {
            first.bean.outerBody = () -> assertThrows(IllegalStateException.class, second.proxy::required);
            first.proxy.outer();
            assertEquals(0, second.count("required"));
            assertTrue(second.bean.bodyThreads.isEmpty());
            first.manager.setNestedTransactionAllowed(false);
            assertThrows(IllegalStateException.class, first.proxy::required);
            assertEquals(0, first.count("required"));
        }
    }

    @Test void callerDeliveryFailureDoesNotInventPhysicalTransactionEnd() throws Exception {
        try (var fixture = new Fixture(false)) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var delivery = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            fixture.bean.outerBody = () -> {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release deadline"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            };
            Thread caller = Thread.ofPlatform().start(() -> {
                try { fixture.proxy.outer(); } catch (Throwable failure) { delivery.set(failure); }
            });
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(5000);
                assertFalse(caller.isAlive());
                assertNotNull(delivery.get());
                assertNull(fixture.effects.lastCompletion());
            } finally { release.countDown(); }
            fixture.dispatch.worker.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, fixture.count("outer"));
            assertEquals(SpringTransactionEffects.Outcome.COMMITTED, fixture.effects.lastCompletion().transactions().getFirst().outcome());
        }
    }

    @Test void effectiveQualifiedManagerCannotBeSubstitutedThroughHiddenBeanFactory() throws Exception {
        try (var intended = new Fixture(false); var substitute = new Fixture(false)) {
            intended.transactionInterceptor.setBeanFactory(substitute.beans);
            var failure = new IllegalArgumentException("ordinary business rollback");
            intended.bean.outerBody = () -> { throw failure; };
            assertSame(failure, assertThrows(IllegalArgumentException.class, intended.proxy::outer));
            assertEquals(0, intended.count("outer"), "the actual selected manager must own this datasource transaction");
        }
    }

    @Test void equalButDifferentDatasourceCannotPassIdentitySnapshot() throws Exception {
        try (var fixture = new Fixture(false, false, Fault.EQUAL_DATA_SOURCE)) {
            fixture.manager.setDataSource(fixture.dataSource);
            assertThrows(IllegalStateException.class, fixture.proxy::required);
            assertEquals(0, fixture.count("required"));
        }
    }

    @ParameterizedTest @EnumSource(value = Propagation.class, names = {"REQUIRED", "REQUIRES_NEW", "NESTED"})
    void caughtCheckedDenialRollsBackInnerWorkAndStillActiveOuter(Propagation propagation) throws Exception {
        try (var fixture = new Fixture(false)) {
            fixture.bean.outerBody = () -> {
                try {
                    switch (propagation) {
                        case REQUIRED -> fixture.proxy.deniedRequired();
                        case REQUIRES_NEW -> fixture.proxy.deniedNew();
                        case NESTED -> fixture.proxy.deniedNested();
                        default -> throw new AssertionError("parameter fixture");
                    }
                } catch (AccessDeniedException failure) { assertSame(fixture.bean.denial, failure); }
            };
            assertSame(fixture.bean.denial, assertThrows(AccessDeniedException.class, fixture.proxy::outer));
            assertEquals(0, fixture.jdbc.queryForObject("select count(*) from effects", Integer.class));
            assertTrue(fixture.effects.lastCompletion().transactions().stream().allMatch(
                    effect -> effect.outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK));
        }
    }

    @Test void diagnosedFatalErrorReachesOwnedDispatcherAsOriginalErrorAfterRollback() throws Exception {
        try (var fixture = new Fixture(false)) {
            var fatal = new LinkageError("fatal worker failure");
            fatal.initCause(fixture.bean.denial);
            fixture.bean.outerBody = () -> { throw fatal; };
            assertSame(fatal, assertThrows(LinkageError.class, fixture.proxy::outer));
            assertSame(fatal, fixture.dispatchFailure.get(), "the runtime must see Error, not an unchecked rollback wrapper");
            assertEquals(0, fixture.count("outer"));
        }
    }
}
