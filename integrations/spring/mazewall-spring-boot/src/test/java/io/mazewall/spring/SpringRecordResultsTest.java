package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Actual JDBC host tests. Record types are public because the admitted result is a public application contract. */
public class SpringRecordResultsTest {
    public record Report(int number, Integer optional, String title, String detail) {}
    public record Scalars(boolean a, Boolean b, byte c, Byte d, short e, Short f, int g, Integer h, long i, Long j, float k, Float l, double m, Double n, char o, Character p) {}
    public record Empty() {}
    public record Nested(Report report) {}
    public record ArrayResult(byte[] bytes) {}
    public record CollectionResult(List<String> values) {}
    public record TooMany(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, int k, int l, int m, int n, int o, int p, int q) {}
    public record Forged(String value) { public String value() { calls.incrementAndGet(); return value; } }
    public record Serialized(String value) implements java.io.Serializable { private Object writeReplace() { return value; } }
    private static final AtomicInteger calls = new AtomicInteger();
    public interface Operation { Report run(String mode); }
    public static class Service implements Operation {
        final JdbcTemplate jdbc;
        Operation nested;
        Service(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Transactional(transactionManager = "jdbc") public Report run(String mode) {
            jdbc.update("insert into effects values (?)", mode);
            if (mode.equals("catch")) {
                try { nested.run("large"); } catch (IllegalArgumentException expected) { /* Retained invalid-result evidence must veto commit. */ }
            }
            return new Report(1, null, mode.equals("large") ? "é".repeat(32_768) : "title", mode.equals("large") ? "x" : "detail");
        }
    }
    public static class NoRollback extends Service {
        NoRollback(JdbcTemplate jdbc) { super(jdbc); }
        @Override @Transactional(transactionManager = "jdbc", noRollbackFor = IllegalArgumentException.class)
        public Report run(String mode) { return super.run(mode); }
    }
    public static class NonTransactional extends Service {
        NonTransactional(JdbcTemplate jdbc) { super(jdbc); }
        @Override @Transactional(transactionManager = "jdbc", propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
        public Report run(String mode) { return super.run(mode); }
    }
    @Test void onlyClosedScalarRecordsWithConventionalAccessorsAreAdmitted() {
        SpringRecordResults.validateShape(Report.class, Service.class);
        SpringRecordResults.validateShape(Scalars.class, Service.class);
        SpringRecordResults.validateValue(Scalars.class, new Scalars(true, null, (byte) 1, null, (short) 1, null, 1, null, 1, null, 1, null, 1, null, 'a', null));
        for (Class<?> bad : List.of(Empty.class, Nested.class, ArrayResult.class, CollectionResult.class, TooMany.class, Forged.class, Serialized.class)) {
            assertThrows(IllegalStateException.class, () -> SpringRecordResults.validateShape(bad, Service.class), bad.getName());
        }
        assertEquals(0, calls.get(), "Binding must inspect bytes, never invoke a custom accessor");
        assertThrows(IllegalStateException.class, () -> SpringRecordResults.validateShape(Report.class, String.class), "Foreign deployment must reject");
    }
    @Test void aggregateUtf8ValuesAreBoundedIncludingSupplementaryUnicodeAndNulls() {
        SpringRecordResults.validateValue(Report.class, new Report(1, null, "é".repeat(32_768), null));
        SpringRecordResults.validateValue(Report.class, new Report(1, 3, "🙂".repeat(16_384), null));
        SpringRecordResults.validateValue(Report.class, null);
        assertThrows(IllegalArgumentException.class, () -> SpringRecordResults.validateValue(Report.class, new Report(1, null, "é".repeat(32_768), "x")));
        assertThrows(IllegalArgumentException.class, () -> SpringRecordResults.validateValue(Report.class, new Report(1, null, "\uD800", null)));
    }
    @Test void realWorkerTransactionRejectsOversizedRecordBeforeCommitAndCanRecover() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table effects(name varchar(20))");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var effects = new SpringTransactionEffects();
        var guard = new SpringTransactionGuard(beans, "jdbc", effects);
        try (var dispatch = new AdvisorPlacementTest.HostDispatch(new ArrayList<>())) {
            var boundary = BoundInvocationInterceptor.forTrustedHostCharacterization(dispatch, guard);
            var target = new Service(jdbc);
            Operation proxy = (Operation) boundary.createProxy(target, guard.transactionAdvisor(), guard.diagnosisAdvisor(),
                    new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()), List.of(Operation.class.getMethod("run", String.class)));
            target.nested = proxy;
            assertEquals(new Report(1, null, "title", "detail"), proxy.run("good"));
            assertThrows(IllegalArgumentException.class, () -> proxy.run("large"));
            assertEquals(0, jdbc.queryForObject("select count(*) from effects where name='large'", Integer.class));
            assertEquals(SpringTransactionEffects.Outcome.ROLLED_BACK, effects.lastCompletion().transactions().getFirst().outcome());
            assertNull(effects.lastCompletion().diagnostic(), "Invalid result is not kernel-denial evidence");
            assertThrows(IllegalArgumentException.class, () -> proxy.run("catch"));
            assertEquals(0, jdbc.queryForObject("select count(*) from effects where name in ('large','catch')", Integer.class));
            assertEquals(new Report(1, null, "title", "detail"), proxy.run("again"));
            var rejected = BoundInvocationInterceptor.forTrustedHostCharacterization(dispatch, guard);
            assertThrows(IllegalStateException.class, () -> rejected.createProxy(new NoRollback(jdbc), guard.transactionAdvisor(), guard.diagnosisAdvisor(),
                    new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()), List.of(Operation.class.getMethod("run", String.class))));
            var noPhysical = BoundInvocationInterceptor.forTrustedHostCharacterization(dispatch, guard);
            assertThrows(IllegalStateException.class, () -> noPhysical.createProxy(new NonTransactional(jdbc), guard.transactionAdvisor(), guard.diagnosisAdvisor(),
                    new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()), List.of(Operation.class.getMethod("run", String.class))));
        } finally { jdbc.execute("shutdown"); }
    }
}
