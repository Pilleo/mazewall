package io.mazewall.portal;

import io.mazewall.enforcer.api.execution.InvocationHandle;
import java.util.function.Function;

/** Test-only fake handle binding; not a protected runtime or production fallback. */
public final class TestPreparedOperations {
    private TestPreparedOperations() { }

    public static <A, R> PreparedPortalOperation<A, R> create(Function<A, InvocationHandle<R>> submit) {
        return new PreparedPortalOperation<>(submit::apply);
    }
}
