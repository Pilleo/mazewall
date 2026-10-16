package demo.boot;

import io.mazewall.spring.MazewallOperation;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Explicit negative fixture: final required methods are unsupported, never silently unprotected. */
public final class UnsupportedReportService extends ReportService {
    UnsupportedReportService(JdbcTemplate jdbc, Path root, ReportState state) { super(jdbc, root, state); }

    @Override @MazewallOperation("quarterly-report")
    @Transactional(transactionManager = "jdbc", rollbackFor = Exception.class)
    public final String generate(String mode) { throw new AssertionError("Unsupported required target must never enter"); }
}
