package demo.report;

import io.mazewall.spring.MazewallOperation;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ReportService implements Reports {
    private final JdbcTemplate jdbc;
    private final Path templates;
    ReportService(JdbcTemplate jdbc, Path templates) { this.jdbc = jdbc; this.templates = templates; }

    @Override @MazewallOperation("render-report")
    @Transactional(transactionManager = "jdbc", rollbackFor = Exception.class)
    @PreAuthorize("isAuthenticated()") @PostAuthorize("hasRole('APPROVER')")
    public String generate(String requestId, String templateName) throws Exception {
        if (requestId == null || requestId.length() != 36) throw new IllegalArgumentException("Server request identity required");
        UUID.fromString(requestId);
        if (templateName == null || !templateName.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Template name must contain1-40 ASCII letters, digits, underscores or hyphens");
        if (!TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw new IllegalStateException("Actual same-datasource transaction required");
        ReportRequestContext.bodyStarted(requestId);
        jdbc.update("insert into report_audit(request_id,template_name) values (?,?)", requestId, templateName);
        byte[] bytes;
        try (var input = Files.newInputStream(templates.resolve(templateName + ".txt"))) { bytes = input.readNBytes(65_537); }
        if (bytes.length > 65_536) throw new IllegalArgumentException("Template exceeds64KiB");
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
