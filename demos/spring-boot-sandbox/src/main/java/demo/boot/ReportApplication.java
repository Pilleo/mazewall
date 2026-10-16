package demo.boot;

import io.mazewall.enforcer.api.execution.DrainReport;
import io.mazewall.spring.SpringTransactionEffects;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Non-web executable Boot consumer. All native installation lives in a disposable java -jar JVM. */
public final class ReportApplication {
    public static void main(String[] args) throws Exception {
        check(args.length == 2, "measure|verify|stale <freshDeploymentDirectory>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        if (args[0].equals("measure") || args[0].equals("measure-unsupported")) CandidatePublication.rejectDirectoryAliases(root);
        root = root.toRealPath();
        check(!Thread.currentThread().isVirtual(), "Explicit bootstrap must run on a platform thread");
        if (args[0].equals("measure") || args[0].equals("measure-unsupported")) {
            ReportBootstrap.measure(root, args[0].equals("measure-unsupported")); return;
        }
        boolean unsupported = args[0].equals("unsupported");
        check(args[0].equals("verify") || args[0].equals("stale") || unsupported, "Unsupported application flow");
        try {
            Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            throw new AssertionError("This executable advertises Security absence only");
        } catch (ClassNotFoundException absent) { System.out.println("BOOT_SECURITY_ABSENT no forced linkage/auth changes"); }
        try (var bootstrap = ReportBootstrap.open(root, unsupported)) {
            var application = new SpringApplication(ReportConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of("spring.main.banner-mode", "off", "spring.main.register-shutdown-hook", "false"));
            application.addInitializers(context -> context.getBeanFactory().registerSingleton("reportBootstrap", bootstrap));
            try (var context = application.run()) {
                Reports report = context.getBean("reportService", Reports.class);
                var advisors = ((Advised) report).getAdvisors();
                int entries = bootstrap.state().bodyEntries;
                try {
                    bootstrap.binder().validateRequiredBindings(List.of("reportService"));
                    throw new AssertionError("Required unbound annotation became ready");
                } catch (IllegalStateException required) {
                    check(bootstrap.state().bodyEntries == entries, "Unbound readiness cannot run body");
                    System.out.println("REQUIRED_UNBOUND_REJECTED " + required.getMessage());
                }
                try {
                    report.generate("approved");
                    throw new AssertionError("Unbound required call ran unprotected");
                } catch (IllegalStateException required) {
                    check(bootstrap.state().bodyEntries == entries, "Unbound dispatch cannot run body");
                }
                if (unsupported) {
                    try { bootstrap.bind(report); throw new AssertionError("Final required method accepted"); }
                    catch (IllegalStateException rejected) {
                        check(bootstrap.state().bodyEntries == 0, "Unsupported annotation cannot enter body");
                        System.out.println("UNSUPPORTED_REQUIRED_REJECTED bodyEntries=0 " + rejected.getMessage());
                        throw rejected;
                    }
                }
                bootstrap.bind(report);
                check(context.getBean("reportService") == report, "Actual existing registered singleton retained");
                check(Arrays.equals(advisors, ((Advised) report).getAdvisors()), "Original advisors retained");
                verify(report, context.getBean(JdbcTemplate.class), bootstrap);
            }
        }
    }

    private static void verify(Reports report, JdbcTemplate jdbc, ReportBootstrap bootstrap) throws Exception {
        Path root = bootstrap.root();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().name("non-web-virtual-request-caller").start(() -> {
            try { result.set(report.generate("approved")); } catch (Throwable failure) { error.set(failure); }
        });
        caller.join(15000);
        check(!caller.isAlive() && error.get() == null, "Virtual call must complete successfully: " + error.get());
        check(result.get().equals("approved:Reviewed quarterly template: approved report"), "Real template/report/JDBC result");
        var metadata = bootstrap.state().worker;
        check(metadata.physicalThread() != caller && !metadata.virtual() && metadata.domainGeneration() > 0, "Owned platform domain metadata");
        System.out.println("VIRTUAL_CALLER_PLATFORM_BODY " + metadata);
        check(bootstrap.effects().lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.COMMITTED,
                "Real JDBC committed receipt");
        check(jdbc.queryForObject("select count(*) from effects where name='approved'", Integer.class) == 1, "Actual committed row");
        System.out.println("JDBC_COMMITTED actualRow=approved warmedInitRows=0");
        deny(report, "mutate", "DENIED_TEMPLATE_MUTATION", bootstrap);
        deny(report, "sibling", "DENIED_SIBLING_READ", bootstrap);
        check(Files.readString(root.resolve("template.txt")).equals("Reviewed quarterly template"), "Denied truncate preserved bytes");
        check(jdbc.queryForList("select name from effects", String.class).equals(List.of("approved")), "Denied SQL effects rolled back");
        try { report.generate("business"); throw new AssertionError("Business failure required"); }
        catch (IllegalArgumentException business) {
            check(Files.readString(root.resolve("reports/quarterly.txt")).equals("external effect survives SQL rollback"), "File is not retrospectively rolled back");
            check(jdbc.queryForObject("select count(*) from effects where name='business'", Integer.class) == 0, "SQL business rollback");
            check(bootstrap.state().bodyEntries == 4, "Heap entries survive both denial and business rollback");
            System.out.println("EXTERNAL_FILE_AND_HEAP_EFFECTS_SURVIVE_SQL_ROLLBACK bodyEntries=" + bootstrap.state().bodyEntries);
        }
        // Restore approved report through the protected operation, not an unprotected raw-target call.
        report.generate("approved");
        check(bootstrap.state().worker.physicalThread() == metadata.physicalThread(), "Same dedicated worker reused");
        cancelAndRetire(report, jdbc, bootstrap);
        System.out.println("BOOT_NATIVE_PASS non-web singleton/template/report/H2/required/denials/physical-retirement");
    }

    private static void deny(Reports report, String mode, String marker, ReportBootstrap bootstrap) throws Exception {
        Throwable failure;
        try { report.generate(mode); throw new AssertionError("Actual denied open required: " + mode); }
        catch (Exception denied) { failure = denied; }
        boolean actualDenied = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException) actualDenied = true;
        }
        check(actualDenied, "Actual JDK AccessDenied cause required: " + failure);
        check(bootstrap.effects().lastCompletion().transactions().getFirst().outcome() == SpringTransactionEffects.Outcome.ROLLED_BACK,
                "Actual JDBC rollback on denied open");
        System.out.println(marker + " actualOpen=AccessDeniedException diagnostic=" + bootstrap.effects().lastCompletion().diagnostic());
    }

    private static void cancelAndRetire(Reports report, JdbcTemplate jdbc, ReportBootstrap bootstrap) throws Exception {
        var state = bootstrap.state();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().name("non-web-virtual-cancel-caller").start(() -> {
            try { report.generate("cancel"); failure.set(new AssertionError("Cancellation returned success")); }
            catch (Throwable cancelled) { failure.set(cancelled); }
        });
        try {
            check(state.entered.await(5, TimeUnit.SECONDS), "Worker entered actual protected SQL/cancel body");
            caller.interrupt();
            caller.join(5000);
            check(!caller.isAlive() && failure.get() instanceof InterruptedException, "Caller interruption delivery");
            check(state.cancellationSeen.await(5, TimeUnit.SECONDS), "Actual cooperative worker cancellation");
            bootstrap.lifecycle().stop();
            check(bootstrap.lifecycle().lastDrain() instanceof DrainReport.Incomplete, "Held body cleanup must retain incomplete drain");
            check(state.worker.physicalThread().isAlive() && state.bodyEnded.getCount() == 1, "Actual physical body still live");
            bootstrap.resources().validate();
            System.out.println("CALLER_DELIVERY_NOT_PHYSICAL_END drain=" + bootstrap.lifecycle().lastDrain());
        } finally { state.releaseCleanup.countDown(); }
        check(state.bodyEnded.await(5, TimeUnit.SECONDS), "Lexical body cleanup ended");
        state.worker.physicalThread().join(5000);
        check(!state.worker.physicalThread().isAlive(), "Captured physical worker actually terminated");
        bootstrap.lifecycle().stop();
        check(bootstrap.lifecycle().lastDrain() == DrainReport.Complete.INSTANCE, "Exact runtime/resources physical retirement");
        try { bootstrap.resources().validate(); throw new AssertionError("Retired owner still available"); }
        catch (IllegalStateException retired) { /* Genuine public owner is physically retired. */ }
        check(jdbc.queryForObject("select count(*) from effects where name='cancel'", Integer.class) == 0, "Cancelled JDBC rollback");
        int entries = state.bodyEntries;
        try { report.generate("approved"); throw new AssertionError("Retired required binding ran body"); }
        catch (IllegalStateException inactive) { check(state.bodyEntries == entries, "Inactive dispatch must not enter target"); }
        System.out.println("PHYSICAL_WORKER_RETIRED actualThread=" + state.worker.threadId() + " ownerClosed=true");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
