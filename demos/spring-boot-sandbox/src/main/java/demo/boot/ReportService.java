package demo.boot;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.spring.MazewallOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing singleton state and genuine JDBC/template/report work behind the retained Spring chain. */
public class ReportService implements Reports {
    private final JdbcTemplate jdbc;
    private final Path root;
    private final ReportState state;

    ReportService(JdbcTemplate jdbc, Path root, ReportState state) {
        this.jdbc = jdbc;
        this.root = root;
        this.state = state;
        if (Thread.currentThread().isVirtual() || Mazewall.currentInvocationEvidence() != null) {
            throw new IllegalStateException("Initialization is trusted platform bootstrap, not protected body");
        }
        jdbc.execute("create table effects(name varchar(40))");
        jdbc.update("insert into effects(name) values ('trusted-init')");
        jdbc.queryForList("select * from effects");
        jdbc.update("delete from effects");
        System.out.println("TRUSTED_INIT_JDBC_WARMUP thread=" + Thread.currentThread().getName()
                + " bodyEvidence=absent; warmup SQL removed, no protected-init claim");
    }

    @Override
    @MazewallOperation("quarterly-report")
    @Transactional(transactionManager = "jdbc", rollbackFor = Exception.class)
    public String generate(String mode) throws Exception {
        state.worker = WorkerMetadata.capture();
        state.bodyEntries++; // Heap state is not a JDBC transaction participant.
        if (!TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Required SAME datasource JDBC transaction absent");
        }
        jdbc.update("insert into effects(name) values (?)", mode);
        return switch (mode) {
            case "approved" -> {
                String report = Files.readString(root.resolve("template.txt")) + ": approved report";
                Files.writeString(root.resolve("reports/quarterly.txt"), report);
                yield jdbc.queryForObject("select name from effects where name = ? limit 1", String.class, mode) + ":" + report;
            }
            case "mutate" -> {
                Files.writeString(root.resolve("template.txt"), "forbidden mutation");
                throw new AssertionError("Template mutation unexpectedly permitted");
            }
            case "sibling" -> Files.readString(root.resolve("sibling.txt"));
            case "business" -> {
                Files.writeString(root.resolve("reports/quarterly.txt"), "external effect survives SQL rollback");
                throw new IllegalArgumentException("Ordinary business error after approved file effect");
            }
            case "cancel" -> {
                state.entered.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Cancellation wait returned normally");
                } catch (InterruptedException cancelled) {
                    state.cancellationSeen.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try { state.releaseCleanup.await(); break; }
                        catch (InterruptedException again) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    throw cancelled;
                } finally {
                    state.bodyEnded.countDown();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported synchronous report mode: " + mode);
        };
    }
}
