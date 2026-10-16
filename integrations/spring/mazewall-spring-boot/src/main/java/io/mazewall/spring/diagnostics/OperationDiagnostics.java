package io.mazewall.spring.diagnostics;

import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
import java.util.Objects;

/** Public record accessors support Actuator serialization without reflective access overrides. */
public record OperationDiagnostics(String source, String operationId, ExecutionDiagnostics execution) {
    public OperationDiagnostics {
        Objects.requireNonNull(source);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(execution);
    }
}
