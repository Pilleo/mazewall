package demo.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {
    @TempDir Path root;

    @Test
    void ordinaryLabelGeneratesReportAndCommitsOneRow() throws Exception {
        fixture();
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:reportNormal;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        try {
            var service = new ReportService(jdbc, root);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
            String generated = transaction.execute(status -> {
                try { return service.generate("quarterly"); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Exception unexpected) { throw new AssertionError(unexpected); }
            });
            assertEquals("quarterly:Reviewed quarterly template: quarterly report", generated);
            assertEquals(1, jdbc.queryForObject("select count(*) from effects where name = 'quarterly'", Integer.class));
            assertEquals("Reviewed quarterly template: quarterly report", Files.readString(root.resolve("reports/quarterly.txt")));
        } finally { jdbc.execute("shutdown"); }
    }

    @Test
    void invalidLabelsRejectBeforeAnySqlOrFileEffect() throws Exception {
        fixture();
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:reportInvalid;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        try {
            var service = new ReportService(jdbc, root);
            for (String invalid : Arrays.asList(null, "", " ", "../secret", "a\u0000b", "é", "x".repeat(41))) {
                assertThrows(IllegalArgumentException.class, () -> service.generate(invalid));
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from effects", Integer.class));
            assertEquals("", Files.readString(root.resolve("reports/quarterly.txt")));
        } finally { jdbc.execute("shutdown"); }
    }

    private void fixture() throws Exception {
        Files.writeString(root.resolve("template.txt"), "Reviewed quarterly template");
        Files.createDirectory(root.resolve("reports"));
        Files.createFile(root.resolve("reports/quarterly.txt"));
    }
}
