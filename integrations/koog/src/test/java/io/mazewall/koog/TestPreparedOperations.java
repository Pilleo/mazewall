package io.mazewall.koog;

import io.mazewall.enforcer.api.execution.InvocationHandle;
import io.mazewall.portal.PreparedPortalOperation;
import java.util.function.Function;

/** Test-only fake handle binding; not a protected runtime or production fallback. */
final class TestPreparedOperations {
    private TestPreparedOperations() { }

    static <A, R> PreparedPortalOperation<A, R> create(Function<A, InvocationHandle<R>> submit) {
        return new PreparedPortalOperation<>(submit::apply);
    }
}
