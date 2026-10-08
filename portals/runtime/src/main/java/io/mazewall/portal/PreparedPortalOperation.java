package io.mazewall.portal;

import io.mazewall.enforcer.api.execution.InvocationHandle;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runtime-created service binding; owns no independent pool and cannot outlive its runtime.
 * Host mapping and codecs run outside worker protection. Construction is package-private
 * at the JVM boundary, rather than merely internal in Kotlin metadata.
 */
public final class PreparedPortalOperation<A, R> {
    private final Function<A, InvocationHandle<R>> submitter;

    PreparedPortalOperation(Function<A, InvocationHandle<R>> submitter) {
        this.submitter = Objects.requireNonNull(submitter);
    }

    /** Caller delivery/cancellation and physical worker retirement are separate stages. */
    public InvocationHandle<R> submit(A arguments) {
        return submitter.apply(arguments);
    }
}
