package io.mazewall.spring.diagnostics;

import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/** Passive observations only; endpoint exposure and authentication remain application configuration. */
@Endpoint(id = "mazewall", defaultAccess = Access.READ_ONLY)
public final class MazewallDiagnosticsEndpoint {
    private final ConfigurableListableBeanFactory beans;

    public MazewallDiagnosticsEndpoint(ConfigurableListableBeanFactory beans) {
        this.beans = Objects.requireNonNull(beans);
    }

    @ReadOperation
    public List<OperationDiagnostics> read() {
        return DiagnosticSnapshots.capture(beans);
    }
}
