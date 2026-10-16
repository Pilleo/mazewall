package io.mazewall.spring;

import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/** Optional supported Spring Security delegation. Instantiate only when Security is present and explicitly enabled. */
public final class SpringSecurityContextBridge implements InvocationContextBridge {
    private final SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();

    public SpringSecurityContextBridge() {
        try {
            Class<?> supported = Class.forName("org.springframework.security.core.context.ThreadLocalSecurityContextHolderStrategy",
                    false, SecurityContextHolder.class.getClassLoader());
            if (strategy.getClass() != supported) {
                throw new IllegalStateException("Supported Security delegation requires the standard thread-local holder strategy");
            }
        } catch (ClassNotFoundException unsupported) {
            throw new IllegalStateException("Supported Security thread-local holder strategy is unavailable", unsupported);
        }
    }

    @Override public Callable<Object> capture(Callable<Object> invocation) {
        if (SecurityContextHolder.getContextHolderStrategy() != strategy) {
            throw new IllegalStateException("Security holder strategy changed after explicit bridge configuration");
        }
        // Capture the CURRENT caller context now, not at bridge/runtime construction.
        // Upstream's finally clears/restores the worker context on success, denial, Error and cancellation.
        var delegated = new DelegatingSecurityContextCallable<>(Objects.requireNonNull(invocation), strategy.getContext());
        delegated.setSecurityContextHolderStrategy(strategy);
        return delegated;
    }
}
