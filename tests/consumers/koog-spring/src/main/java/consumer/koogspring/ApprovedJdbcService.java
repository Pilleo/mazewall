package consumer.koogspring;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.spring.MazewallOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public only for the actual Spring method/binding inspection in this finished test JAR. */
public final class ApprovedJdbcService implements JdbcService {
    private final JdbcTemplate jdbc;
    private final Path template;
    private final Path sibling;
    final AtomicInteger entries = new AtomicInteger();
    final AtomicReference<Thread> worker = new AtomicReference<>();
    final InterruptedException businessInterruption = new InterruptedException("original business interruption");
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch cancellationSeen = new CountDownLatch(1);
    final CountDownLatch cleanupRelease = new CountDownLatch(1);
    final CountDownLatch bodyEnded = new CountDownLatch(1);

    ApprovedJdbcService(JdbcTemplate jdbc, Path template, Path sibling) {
        this.jdbc = jdbc;
        this.template = template;
        this.sibling = sibling;
    }

    @Override @MazewallOperation("koog-spring-jdbc")
    @Transactional(transactionManager = "jdbc", rollbackFor = InterruptedException.class)
    @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('ADMIN')")
    public String run(String mode) throws Exception {
        Thread current = Thread.currentThread();
        worker.compareAndSet(null, current);
        require(worker.get() == current && !current.isVirtual(), "One actual owned platform worker required");
        require(Mazewall.currentInvocationEvidence() != null, "Actual managed invocation evidence required");
        require(SecurityContextHolder.getContext().getAuthentication() != null, "Captured caller identity required");
        require(TransactionSynchronizationManager.hasResource(jdbc.getDataSource()), "Exact manager datasource required");
        entries.incrementAndGet();
        jdbc.update("insert into effects(name) values (?)", mode);
        switch (mode) {
            case "approved", "postDenied", "afterDenied":
                return jdbc.queryForObject("select name from effects where name = ?", String.class, mode)
                        + ":" + Files.readString(template);
            case "business": throw businessInterruption;
            case "sibling": return Files.readString(sibling);
            case "cancel":
                entered.countDown();
                try {
                    require(new CountDownLatch(1).await(5, TimeUnit.SECONDS), "Cancellation did not arrive");
                    throw new AssertionError("Cancellation wait unexpectedly returned");
                } catch (InterruptedException cancelled) {
                    cancellationSeen.countDown();
                    holdCleanup();
                    throw cancelled;
                } finally { bodyEnded.countDown(); }
            default: throw new IllegalArgumentException("Unknown fixture mode");
        }
    }

    private void holdCleanup() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    require(cleanupRelease.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                            "Held lexical cleanup was not released");
                    return;
                } catch (InterruptedException again) {
                    // Drain may interrupt again; only the test controller releases physical cleanup.
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
