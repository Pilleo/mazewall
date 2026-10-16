package io.mazewall.spring.diagnostics;

import io.mazewall.enforcer.api.execution.ExecutionGeneration;
import java.util.Objects;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

final class MazewallDiagnosticsHealthIndicator implements HealthIndicator {
    private final ConfigurableListableBeanFactory beans;

    MazewallDiagnosticsHealthIndicator(ConfigurableListableBeanFactory beans) {
        this.beans = Objects.requireNonNull(beans);
    }

    @Override public Health health() {
        var operations = DiagnosticSnapshots.capture(beans);
        boolean ready = !operations.isEmpty();
        for (var operation : operations) {
            var snapshot = operation.execution();
            Status status = switch (snapshot.getState()) {
                case FAILED -> Status.DOWN;
                case UNKNOWN, PREPARING, DRAINING, CLOSED -> Status.OUT_OF_SERVICE;
                case READY -> snapshot.getGeneration().getKind() != ExecutionGeneration.Kind.UNAVAILABLE
                        && snapshot.getLiveWorkers() > 0 ? Status.UP : Status.OUT_OF_SERVICE;
            };
            if (Status.DOWN.equals(status)) return Health.down().withDetail("operations", operations).build();
            if (!Status.UP.equals(status)) ready = false;
        }
        return Health.status(ready ? Status.UP : Status.OUT_OF_SERVICE).withDetail("operations", operations).build();
    }
}
