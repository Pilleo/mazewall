package io.mazewall.spring;

import io.mazewall.enforcer.diagnostics.ContainmentDiagnostic;
import java.util.Objects;

/** Internal unchecked rollback bridge; the dispatch boundary restores the original caller cause. */
final class TransactionalFailureBridge extends RuntimeException {
    private final Throwable original;
    private final ContainmentDiagnostic diagnostic;

    TransactionalFailureBridge(Throwable original, ContainmentDiagnostic diagnostic) {
        super("Recorded protected invocation failure before transaction completion", Objects.requireNonNull(original));
        this.original = original;
        this.diagnostic = diagnostic;
    }
    Throwable original() { return original; }
    ContainmentDiagnostic diagnostic() { return diagnostic; }
}
