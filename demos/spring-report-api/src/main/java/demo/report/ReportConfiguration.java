package demo.report;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration @EnableMethodSecurity @EnableTransactionManagement
public class ReportConfiguration {
    static boolean ready(io.mazewall.enforcer.api.execution.ExecutionDiagnosticState state) {
        return switch (state) {
            case READY -> true;
            case UNKNOWN, PREPARING, DRAINING, CLOSED, FAILED -> false;
        };
    }
    @Bean ReportController controller(Reports report, OrdinaryService ordinary, io.mazewall.spring.DomainLifecycle domain) { return new ReportController(report, ordinary, domain); }
    @Bean org.springframework.boot.health.contributor.HealthIndicator protectedReports(io.mazewall.spring.DomainLifecycle domain) {
        return () -> ready(domain.diagnostics().getState()) ? org.springframework.boot.health.contributor.Health.up().build()
                : org.springframework.boot.health.contributor.Health.outOfService().build();
    }
    @Bean OrdinaryService ordinaryService() { return new OrdinaryService(); }
    @Bean UserDetailsService users() {
        return new InMemoryUserDetailsManager(User.withUsername("approver").password("{noop}" + password("approver")).roles("APPROVER").build(),
                User.withUsername("reader").password("{noop}" + password("reader")).roles("READER").build());
    }
    private static String password(String user) {
        String value = System.getProperty("report." + user + "Password");
        if (value == null || value.length() < 12) throw new IllegalArgumentException("Explicit evaluation passwords of at least12 characters required");
        return value;
    }
    @Bean SecurityFilterChain httpSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/readiness").permitAll().anyRequest().authenticated())
                .httpBasic(org.springframework.security.config.Customizer.withDefaults()).build();
    }
    /** Unrelated beans keep normal Spring method-security and transaction postprocessing. */
    public static class OrdinaryService {
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('APPROVER')")
        @org.springframework.transaction.annotation.Transactional(transactionManager = "jdbc", readOnly = true)
        public String status() {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Ordinary infrastructure disappeared");
            return "ordinary Spring infrastructure active";
        }
    }
}
