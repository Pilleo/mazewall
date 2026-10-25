package demo.boot;

import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticState;
import io.mazewall.spring.SpringTransactionEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Non-web executable: explicitly select a reviewed normal service or the separate verification fixture. */
public final class ReportApplication {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: measure-report|generate|measure|verify|stale|measure-unsupported|unsupported <deploymentDirectory>");
        String mode = args[0];
        ReportBootstrap.Target target = switch (mode) {
            case "measure-report", "generate" -> ReportBootstrap.Target.REPORT;
            case "measure", "verify", "stale" -> ReportBootstrap.Target.VERIFICATION;
            case "measure-unsupported", "unsupported" -> ReportBootstrap.Target.UNSUPPORTED;
            default -> throw new IllegalArgumentException("Unsupported application flow: " + mode);
        };
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        boolean measurement = mode.equals("measure") || mode.equals("measure-report") || mode.equals("measure-unsupported");
        if (measurement) CandidatePublication.rejectDirectoryAliases(root);
        root = root.toRealPath();
        if (Thread.currentThread().isVirtual()) throw new IllegalStateException("Explicit bootstrap must run on a platform thread");
        if (measurement) {
            ReportBootstrap.measure(root, target);
            return;
        }
        try {
            Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            throw new AssertionError("This executable advertises Security absence only");
        } catch (ClassNotFoundException absent) { System.out.println("BOOT_SECURITY_ABSENT no forced linkage/auth changes"); }
        try {
            Class.forName("org.springframework.boot.actuate.endpoint.annotation.Endpoint");
            throw new AssertionError("The ordinary starter must not force optional Actuator linkage");
        } catch (ClassNotFoundException absent) { System.out.println("BOOT_ACTUATOR_ABSENT optional diagnostics linkage is not forced"); }

        var bootstrap = ReportBootstrap.open(root, target);
        try (bootstrap) {
            var application = new SpringApplication(ReportConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of("spring.main.banner-mode", "off", "spring.main.register-shutdown-hook", "false"));
            application.addInitializers(context -> context.getBeanFactory().registerSingleton("reportBootstrap", bootstrap));
            try (var context = application.run()) {
                Reports report = context.getBean("reportService", Reports.class);
                var jdbc = context.getBean(JdbcTemplate.class);
                if (target == ReportBootstrap.Target.REPORT) {
                    bootstrap.bind(report);
                    String generated = report.generate("quarterly");
                    String expected = "quarterly:" + Files.readString(root.resolve("template.txt")) + ": quarterly report";
                    if (!generated.equals(expected)
                            || jdbc.queryForObject("select count(*) from effects where name='quarterly'", Integer.class) != 1
                            || bootstrap.effects().lastCompletion().transactions().getFirst().outcome() != SpringTransactionEffects.Outcome.COMMITTED) {
                        throw new IllegalStateException("Actual reviewed report and exactly-once JDBC commit required");
                    }
                    System.out.println("REPORT_GENERATED " + generated);
                    System.out.println("JDBC_COMMITTED actualRow=quarterly warmedInitRows=0");
                    bootstrap.lifecycle().stop();
                } else {
                    ReportVerification.run(report, jdbc, bootstrap, context);
                }
            }
        }
        if (target == ReportBootstrap.Target.REPORT) {
            var worker = bootstrap.state().worker.physicalThread();
            worker.join(5000);
            var closed = bootstrap.lifecycle().diagnostics();
            if (worker.isAlive() || bootstrap.lifecycle().lastDrain() != DrainReport.Complete.INSTANCE
                    || closed.getState() != ExecutionDiagnosticState.CLOSED || !closed.getRetirementComplete()
                    || closed.getLiveWorkers() != 0 || closed.getUnfinishedInvocations() != 0) {
                throw new IllegalStateException("Normal report owner did not physically retire");
            }
            try {
                bootstrap.resources().validate();
                throw new AssertionError("Retired report resources stayed available");
            } catch (IllegalStateException retired) { /* Genuine owner is closed only after physical retirement. */ }
            System.out.println("REPORT_OWNER_RETIRED actualThread=" + worker.threadId());
        }
    }
}
