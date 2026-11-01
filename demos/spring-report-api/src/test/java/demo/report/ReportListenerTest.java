package demo.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ReportListenerTest {
    @Test void acceptsExactLoopbackAndConfiguredCliPorts() {
        for (int port : List.of(0, 8080, 65535)) {
            var environment = defaults(port);
            assertDoesNotThrow(() -> ReportApplication.requireEvaluationListener(environment, port));
            if (port != 0) {
                environment.getPropertySources().addFirst(new MapPropertySource("same-management-listener",
                        Map.of("management.server.port", port, "management.server.address", "127.0.0.1")));
                assertDoesNotThrow(() -> ReportApplication.requireEvaluationListener(environment, port));
            }
        }
    }

    @Test void rejectsHigherPriorityAddressPropertiesAndEnvironment() {
        for (var source : List.of(new MapPropertySource("system", Map.of("server.address", "0.0.0.0")),
                new SystemEnvironmentPropertySource("environment", Map.of("SERVER_ADDRESS", "0.0.0.0")))) {
            var environment = defaults(0);
            environment.getPropertySources().addFirst(source);
            assertThrows(IllegalStateException.class, () -> ReportApplication.requireEvaluationListener(environment, 0));
        }
    }

    @Test void rejectsHigherPriorityPortPropertiesAndEnvironment() {
        for (var source : List.of(new MapPropertySource("system", Map.of("server.port", 7777)),
                new SystemEnvironmentPropertySource("environment", Map.of("SERVER_PORT", "7777")))) {
            var environment = defaults(0);
            environment.getPropertySources().addFirst(source);
            assertThrows(IllegalStateException.class, () -> ReportApplication.requireEvaluationListener(environment, 0));
        }
    }

    @Test void rejectsSeparateManagementAddressOrPort() {
        for (Map<String, Object> values : List.<Map<String, Object>>of(Map.of("management.server.port", 0), Map.of("management.server.port", 8081),
                Map.of("management.server.port", -1), Map.of("management.server.address", "0.0.0.0"))) {
            var environment = defaults(0);
            environment.getPropertySources().addFirst(new MapPropertySource("management-override", values));
            assertThrows(IllegalStateException.class, () -> ReportApplication.requireEvaluationListener(environment, 0));
        }
    }

    @Test void rejectsMalformedOrMissingEffectiveListenerValues() {
        var malformed = defaults(0);
        malformed.getPropertySources().addFirst(new MapPropertySource("invalid-port", Map.of("server.port", "not-a-port")));
        assertThrows(RuntimeException.class, () -> ReportApplication.requireEvaluationListener(malformed, 0));
        var missing = defaults(0);
        missing.getPropertySources().remove("evaluation-defaults");
        assertThrows(IllegalStateException.class, () -> ReportApplication.requireEvaluationListener(missing, 0));
    }

    private static ConfigurableEnvironment defaults(int port) {
        var environment = new StandardEnvironment() {
            @Override protected void customizePropertySources(MutablePropertySources sources) { /* Hermetic test, not the operator's environment. */ }
        };
        environment.getPropertySources().addFirst(new MapPropertySource("evaluation-defaults", Map.of("server.address", "127.0.0.1", "server.port", port)));
        return environment;
    }
}
