package demo.report;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ordinary business/real H2 transaction tests only; no native containment is claimed. */
class ReportServiceTest {
    @TempDir Path root;

    @Test void reportUsesActualTemplateAndCommitsSameDatasourceAudit() throws Exception {
        Files.writeString(root.resolve("approved.txt"), "Quarterly report é🙂");
        var data = data();
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table report_audit(request_id varchar(36) primary key, template_name varchar(40))");
        try {
            String id = UUID.randomUUID().toString();
            var service = new ReportService(jdbc, root);
            String result = new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status -> {
                try { return service.generate(id, "approved"); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            assertEquals("Quarterly report é🙂", result);
            assertEquals(1, jdbc.queryForObject("select count(*) from report_audit where request_id=?", Integer.class, id));
        } finally { jdbc.execute("shutdown"); }
    }

    @Test void invalidTemplateCannotWriteAuditRows() {
        var data = data();
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table report_audit(request_id varchar(36) primary key, template_name varchar(40))");
        try {
            var service = new ReportService(jdbc, root);
            for (String name : new String[] {"", "../private", "bad/name", "x".repeat(41)}) {
                assertThrows(IllegalArgumentException.class, () -> service.generate(UUID.randomUUID().toString(), name));
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from report_audit", Integer.class));
        } finally { jdbc.execute("shutdown"); }
    }

    @Test void differentActualTemplatesReturnDifferentContentWithDistinctAuditRequests() throws Exception {
        var data = data();
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("create table report_audit(request_id varchar(36) primary key, template_name varchar(40))");
        try {
            var service = new ReportService(jdbc, root);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
            String first = UUID.randomUUID().toString();
            String second = UUID.randomUUID().toString();
            Files.writeString(root.resolve("approved.txt"), "Checkout readiness: pool maximum=2");
            assertEquals("Checkout readiness: pool maximum=2", transaction.execute(status -> {
                try { return service.generate(first, "approved"); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            Files.writeString(root.resolve("approved.txt"), "Checkout readiness: pool maximum=20");
            assertEquals("Checkout readiness: pool maximum=20", transaction.execute(status -> {
                try { return service.generate(second, "approved"); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }));
            assertNotEquals(first, second);
            assertEquals(1, jdbc.queryForObject("select count(*) from report_audit where request_id=?", Integer.class, first));
            assertEquals(1, jdbc.queryForObject("select count(*) from report_audit where request_id=?", Integer.class, second));
        } finally { jdbc.execute("shutdown"); }
    }

    private static JdbcDataSource data() {
        var result = new JdbcDataSource();
        result.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return result;
    }
}
