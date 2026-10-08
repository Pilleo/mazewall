package consumer.spring;

import io.mazewall.spring.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.Advisor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Optional explicit Security native fixture; packaged in a finished ordinary JAR, not a Boot launch. */
public final class SecurityNativeFixture implements NativeJdbcConsumer.SecurityFixture {
    private final AtomicInteger restored = new AtomicInteger();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public static final class SecureJdbcOperation extends NativeJdbcConsumer.JdbcOperation {
        public SecureJdbcOperation(JdbcTemplate jdbc, Path template, Path sibling) { super(jdbc, template, sibling); }
        @Override @MazewallOperation("jdbc-write")
        @Transactional(transactionManager = "jdbc", rollbackFor = InterruptedException.class)
        @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('ADMIN')")
        public String run(String mode) throws Exception {
            check(SecurityContextHolder.getContext().getAuthentication() != null, "Caller Security context captured on actual owned worker");
            return super.run(mode); // Ordinary same-target implementation code; adapter still invokes retained proxy chain.
        }
    }

    @Override public Class<? extends NativeJdbcConsumer.JdbcOperation> implementation() { return SecureJdbcOperation.class; }
    @Override public NativeJdbcConsumer.JdbcOperation target(JdbcTemplate jdbc, Path template, Path sibling) {
        return new SecureJdbcOperation(jdbc, template, sibling);
    }
    @Override public InvocationContextBridge contexts() {
        var supported = new SpringSecurityContextBridge();
        return invocation -> {
            var delegated = supported.capture(invocation); // Capture per caller invocation, using actual public adapter bridge.
            return () -> {
                Thread current = Thread.currentThread();
                worker.compareAndSet(null, current);
                check(worker.get() == current && !current.isVirtual(), "All actual Security calls use one owned platform worker");
                check(SecurityContextHolder.getContext().getAuthentication() == null, "Worker context initially clean");
                try { return delegated.call(); }
                finally {
                    check(SecurityContextHolder.getContext().getAuthentication() == null, "Upstream delegate restored clean native worker context");
                    SecurityContextHolder.clearContext();
                    restored.incrementAndGet();
                }
            };
        };
    }
    @Override public Advisor authorization() { return SpringSecurityMethodAdvice.authorizationAdvisor(); }
    @Override public void principal(String name) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase(java.util.Locale.ROOT)))));
        SecurityContextHolder.setContext(context);
    }
    @Override public void verifyAlternating(NativeJdbcConsumer.Operation proxy, NativeJdbcConsumer.JdbcOperation target,
                                           JdbcTemplate jdbc, SpringTransactionEffects effects) throws Exception {
        principal("bob");
        try { proxy.run("postDenied"); throw new AssertionError("Actual PostAuthorize denial required"); }
        catch (Exception denied) {
            check(hasCause(denied, AccessDeniedException.class), "Actual upstream authorization denial: " + denied);
        }
        check(target.worker == worker.get(), "Bob's post-denied body ran on same actual worker");
        check(jdbc.queryForObject("select count(*) from effects where name = 'postDenied'", Integer.class) == 0,
                "Post-authorization denial preceded real JDBC commit and rolled back SQL");
        check(effects.lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK,
                "Actual post-denied JDBC outcome");
        check(effects.lastCompletion().diagnostic() == null, "Security denial must not be labeled kernel denial");
        principal("admin");
        check(proxy.run("afterDenied").equals("afterDenied:approved template"), "Admin reused same clean worker after Bob denial");
        try { proxy.run("business"); throw new AssertionError("Actual ordinary business failure required"); }
        catch (Exception failure) { check(hasCause(failure, IllegalArgumentException.class), "Original business failure retained"); }
        check(jdbc.queryForObject("select count(*) from effects where name = 'business'", Integer.class) == 0, "Business SQL rollback");
        check(target.worker == worker.get(), "Same worker retained after ordinary error");
    }
    @Override public void verifyRestoration() {
        check(restored.get() >= 7, "Success/filesystem-denial/authorization-denial/error/cancel all restored actual native context");
        check(worker.get() != null && !worker.get().isAlive(), "Captured actual Security worker physically ended");
        check(SecurityContextHolder.getContext().getAuthentication().getName().equals("admin"), "Caller context unchanged by native delegation");
        SecurityContextHolder.clearContext();
        System.out.println("NATIVE_SECURITY_DELEGATION_PASS alternating-principals/postauth-real-SQL-rollback/error/cancel/clean-context/physical-end");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
