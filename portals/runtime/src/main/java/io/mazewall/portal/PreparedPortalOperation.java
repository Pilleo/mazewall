package io.mazewall.portal;

import io.mazewall.enforcer.api.execution.InvocationHandle;
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runtime-created service binding; owns no independent pool and cannot outlive its runtime.
 * Host mapping and codecs run outside worker protection. Construction is package-private
 * at the JVM boundary, rather than merely internal in Kotlin metadata.
 */
public final class PreparedPortalOperation<A, R> {
    private final Function<A, InvocationHandle<R>> submitter;
    private final Supplier<ExecutionDiagnostics> diagnostics;

    PreparedPortalOperation(Function<A, InvocationHandle<R>> submitter) {
        this(submitter, () -> ExecutionDiagnostics.UNAVAILABLE);
    }

    PreparedPortalOperation(Function<A, InvocationHandle<R>> submitter, Supplier<ExecutionDiagnostics> diagnostics) {
        this.submitter = Objects.requireNonNull(submitter);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    /** Caller delivery/cancellation and physical worker retirement are separate stages. */
    public InvocationHandle<R> submit(A arguments) {
        return submitter.apply(arguments);
    }

    /** Observes the exact owned broker without submitting work or changing its lifecycle. */
    public ExecutionDiagnostics diagnostics() {
        return Objects.requireNonNull(diagnostics.get());
    }
}
