package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Explicit uncontained host characterization of supported context capture/restoration. */
class SecurityDelegationTest {
    @AfterEach void clearCaller() { SecurityContextHolder.clearContext(); }

    static void principal(String name) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase(java.util.Locale.ROOT)))));
        SecurityContextHolder.setContext(context);
    }

    @Test void globalAndInheritableStrategiesAndStrategyReplacementRejectRatherThanBleedWorkerContext() {
        var original = SecurityContextHolder.getContextHolderStrategy();
        var bridge = new SpringSecurityContextBridge();
        try {
            for (String mode : List.of(SecurityContextHolder.MODE_GLOBAL, SecurityContextHolder.MODE_INHERITABLETHREADLOCAL)) {
                SecurityContextHolder.setStrategyName(mode); // Fixture-only application configuration; restored below.
                assertThrows(IllegalStateException.class, SpringSecurityContextBridge::new);
                assertThrows(IllegalStateException.class, () -> bridge.capture(() -> "must never execute"));
            }
        } finally { SecurityContextHolder.setContextHolderStrategy(original); }
    }

    @Test void capturesEachInvocationAndRestoresCleanSingleWorkerAfterSuccessAndError() throws Exception {
        var bridge = new SpringSecurityContextBridge();
        try (var worker = Executors.newSingleThreadExecutor()) {
            principal("alice");
            Callable<Object> alice = bridge.capture(() -> SecurityContextHolder.getContext().getAuthentication().getName());
            principal("bob");
            Callable<Object> bob = bridge.capture(() -> SecurityContextHolder.getContext().getAuthentication().getName());
            assertEquals("alice", worker.submit(alice).get(5, TimeUnit.SECONDS));
            assertEquals("bob", worker.submit(bob).get(5, TimeUnit.SECONDS));
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            var fatal = new LinkageError("original delegated fatal");
            var failed = bridge.capture(() -> { assertEquals("bob", SecurityContextHolder.getContext().getAuthentication().getName()); throw fatal; });
            assertSame(fatal, assertThrows(ExecutionException.class, () -> worker.submit(failed).get(5, TimeUnit.SECONDS)).getCause());
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            assertEquals("bob", SecurityContextHolder.getContext().getAuthentication().getName());
        }
    }

    @Test void cooperativeCancellationRestoresContextBeforeSameWorkerReuse() throws Exception {
        principal("alice");
        var bridge = new SpringSecurityContextBridge();
        var entered = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var future = worker.submit(bridge.capture(() -> {
                assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
                entered.countDown();
                try { new CountDownLatch(1).await(); return "unexpected"; }
                finally { ended.countDown(); }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            // The next actual worker task executes only after the supported delegate's finally.
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            principal("bob");
            assertEquals("bob", worker.submit(bridge.capture(() -> SecurityContextHolder.getContext().getAuthentication().getName()))
                    .get(5, TimeUnit.SECONDS));
        }
    }

    @Test void supportedSecurityDelegateExcludesActualCallerTransactionResource() throws Exception {
        var data = new org.h2.jdbcx.JdbcDataSource();
        data.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID());
        var transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(data);
        var callerTransaction = transactions.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
        var bridge = new SpringSecurityContextBridge();
        principal("alice");
        var resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(data);
        assertNotNull(resource, "Caller owns an actual JDBC transaction resource");
        try (var worker = Executors.newSingleThreadExecutor()) {
            var captured = bridge.capture(() -> {
                assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(data));
                return "no-transaction-transfer";
            });
            principal("bob"); // Replace the context reference; do not mutate captured Authentication.
            assertEquals("no-transaction-transfer", worker.submit(captured).get(5, TimeUnit.SECONDS));
            assertNull(worker.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get(5, TimeUnit.SECONDS));
            assertSame(resource, org.springframework.transaction.support.TransactionSynchronizationManager.getResource(data));
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        } finally { transactions.rollback(callerTransaction); }
        assertNull(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(data));
    }
}
