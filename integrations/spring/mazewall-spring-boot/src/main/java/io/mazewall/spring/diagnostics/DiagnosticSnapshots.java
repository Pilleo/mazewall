package io.mazewall.spring.diagnostics;

import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

final class DiagnosticSnapshots {
    private DiagnosticSnapshots() {}

    static List<OperationDiagnostics> capture(ConfigurableListableBeanFactory beans) {
        var snapshots = new ArrayList<OperationDiagnostics>();
        Arrays.stream(beans.getSingletonNames()).sorted().forEach(name -> {
            // Even getSingleton can invoke an early-reference factory before full publication.
            if (beans.containsSingleton(name) && beans.getSingleton(name) instanceof ExecutionDiagnosticsSource source) {
                snapshots.add(new OperationDiagnostics(name, source.operationId(), source.diagnostics()));
            }
        });
        return List.copyOf(snapshots);
    }
}
