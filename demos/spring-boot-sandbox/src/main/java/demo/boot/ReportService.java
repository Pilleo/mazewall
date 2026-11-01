package demo.boot;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.spring.MazewallOperation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Ordinary synchronous business code: the registered Spring proxy owns dispatch and advice. */
public class ReportService implements Reports {
    private final JdbcTemplate jdbc;
    private final Path template;
    private final Path report;

    ReportService(JdbcTemplate jdbc, Path root) {
        this.jdbc = jdbc;
        this.template = root.resolve("template.txt");
        this.report = root.resolve("reports/quarterly.txt");
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

    @Override @MazewallOperation("quarterly-report")
    @Transactional(transactionManager = "jdbc", rollbackFor = Exception.class)
    public String generate(String label) throws Exception {
        if (label == null || !label.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("Report label must contain 1-40 ASCII letters, digits, underscores or hyphens");
        }
        if (!TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Required SAME datasource JDBC transaction absent");
        }
        jdbc.update("insert into effects(name) values (?)", label);
        String content = Files.readString(template) + ": " + label + " report";
        Files.writeString(report, content);
        return jdbc.queryForObject("select name from effects where name = ? limit 1", String.class, label) + ":" + content;
    }
}
