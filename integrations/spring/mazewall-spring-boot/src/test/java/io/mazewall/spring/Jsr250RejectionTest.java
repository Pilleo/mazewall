package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual unsupported declarations on required entries, real JDBC, explicit uncontained test-only binder. */
class Jsr250RejectionTest {
    public interface Operation { String run(); }
    static class SqlBean implements Operation {
        final JdbcTemplate jdbc;
        final AtomicInteger entries = new AtomicInteger();
        SqlBean(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override @MazewallOperation("jsr250") @Transactional(transactionManager = "jdbc")
        public String run() {
            entries.incrementAndGet();
            assertTrue(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
            jdbc.update("insert into effects(name) values ('must-not-execute')");
            return "must-not-execute";
        }
    }
    static final class JakartaMethodDeny extends SqlBean {
        JakartaMethodDeny(JdbcTemplate jdbc) { super(jdbc); }
        @Override @jakarta.annotation.security.DenyAll @MazewallOperation("jsr250") @Transactional(transactionManager = "jdbc")
        public String run() { return super.run(); }
    }
    static final class JakartaMethodPermit extends SqlBean {
        JakartaMethodPermit(JdbcTemplate jdbc) { super(jdbc); }
        @Override @jakarta.annotation.security.PermitAll @MazewallOperation("jsr250") @Transactional(transactionManager = "jdbc")
        public String run() { return super.run(); }
    }
    static final class JavaxMethodDeny extends SqlBean {
        JavaxMethodDeny(JdbcTemplate jdbc) { super(jdbc); }
        @Override @javax.annotation.security.DenyAll @MazewallOperation("jsr250") @Transactional(transactionManager = "jdbc")
        public String run() { return super.run(); }
    }
    static final class JavaxMethodPermit extends SqlBean {
        JavaxMethodPermit(JdbcTemplate jdbc) { super(jdbc); }
        @Override @javax.annotation.security.PermitAll @MazewallOperation("jsr250") @Transactional(transactionManager = "jdbc")
        public String run() { return super.run(); }
    }
    @jakarta.annotation.security.DenyAll static final class JakartaTypeDeny extends SqlBean {
        JakartaTypeDeny(JdbcTemplate jdbc) { super(jdbc); }
    }
    @jakarta.annotation.security.PermitAll static final class JakartaTypePermit extends SqlBean {
        JakartaTypePermit(JdbcTemplate jdbc) { super(jdbc); }
    }
    @javax.annotation.security.DenyAll static final class JavaxTypeDeny extends SqlBean {
        JavaxTypeDeny(JdbcTemplate jdbc) { super(jdbc); }
    }
    @javax.annotation.security.PermitAll static final class JavaxTypePermit extends SqlBean {
        JavaxTypePermit(JdbcTemplate jdbc) { super(jdbc); }
    }

    record Declaration(String name, Function<JdbcTemplate, SqlBean> create) {
        @Override public String toString() { return name; }
    }
    static Stream<Declaration> declarations() {
        return Stream.of(new Declaration("jakarta method DenyAll", JakartaMethodDeny::new),
                new Declaration("jakarta method PermitAll", JakartaMethodPermit::new),
                new Declaration("javax method DenyAll", JavaxMethodDeny::new),
                new Declaration("javax method PermitAll", JavaxMethodPermit::new),
                new Declaration("jakarta type DenyAll", JakartaTypeDeny::new),
                new Declaration("jakarta type PermitAll", JakartaTypePermit::new),
                new Declaration("javax type DenyAll", JavaxTypeDeny::new),
                new Declaration("javax type PermitAll", JavaxTypePermit::new));
    }

    @ParameterizedTest @MethodSource("declarations")
    void unsupportedRequiredDeclarationRejectsBeforeBodyOrRealSql(Declaration declaration) throws Exception {
        var data = new org.h2.jdbcx.JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table effects(name varchar(40))");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", new DataSourceTransactionManager(data));
        var effects = new SpringTransactionEffects();
        var guard = new SpringTransactionGuard(beans, "jdbc", effects);
        try (var host = new AdvisorPlacementTest.HostDispatch(new java.util.ArrayList<>())) {
            SqlBean target = declaration.create().apply(jdbc);
            var binding = new OperationBinding("jsr250", OperationAdapter.SPRING_METHOD,
                    SpringOperationBinder.classResourceSha256(target.getClass()), "host-only",
                    Map.of("beanName", "operation", "className", target.getClass().getName(), "methodDescriptor", "run()Ljava/lang/String;"));
            var document = new OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT,
                    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()), Set.of(), Map.of());
            var artifact = ReviewedOperationArtifact.load(document.toJson(), new TrustedPolicyReview("test-only", document.sha256(), Set.of()), binding);
            var binder = SpringOperationBinder.forTrustedHostCharacterization(beans, host, artifact, guard);
            var factory = new ProxyFactory(target);
            factory.addAdvisor(binder.dispatchAdvisor());
            factory.addAdvisor(guard.transactionAdvisor());
            factory.addAdvisor(guard.diagnosisAdvisor());
            factory.addAdvisor(SpringSecurityMethodAdvice.authorizationAdvisor());
            factory.setFrozen(true);
            Operation proxy = (Operation) factory.getProxy();
            beans.registerSingleton("operation", proxy);
            IllegalStateException rejection = null;
            try {
                binder.bind(new SpringOperationBinder.Approval(artifact, proxy, target.getClass().getClassLoader(),
                        RuntimeProfile.HOTSPOT_JIT, "host-only", Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
                proxy.run(); // On old source, real DenyAll/PermitAll was ignored and this SQL committed.
            } catch (IllegalStateException unsupported) { rejection = unsupported; }
            IllegalStateException observed = rejection;
            assertAll(declaration.name(),
                    () -> assertNotNull(observed, "Unsupported declaration must reject"),
                    () -> assertEquals(0, target.entries.get(), "Required target body must never enter"),
                    () -> assertEquals(0, jdbc.queryForObject("select count(*) from effects", Integer.class), "Real SQL must have zero effects"));
            assertTrue(observed.getMessage().contains("Unsupported method-security annotation"), "No incidental rejection accepted");
            assertNull(effects.lastCompletion(), "Binding rejection precedes transaction entry, not a fictional rollback receipt");
        } finally { jdbc.execute("shutdown"); }
    }
}
