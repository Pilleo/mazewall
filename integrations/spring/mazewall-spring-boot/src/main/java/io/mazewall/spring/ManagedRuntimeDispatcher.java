package io.mazewall.spring;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.api.execution.InProcessDomain;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Uses an already explicitly bootstrapped runtime/domain; never installs a native baseline. */
public final class ManagedRuntimeDispatcher implements InvocationDispatcher {
    private final ManagedRuntime runtime;
    private final InProcessDomain domain;

    public ManagedRuntimeDispatcher(ManagedRuntime runtime, InProcessDomain domain) {
        this.runtime = Objects.requireNonNull(runtime);
        this.domain = Objects.requireNonNull(domain);
    }

    @Override
    public Object call(Body body) {
        return Mazewall.callContained(runtime, domain, callable(body));
    }

    long generation() { return domain.getGeneration(); }

    // Shared Callable adaptation is directly host-characterizable without native runtime setup.
    static Callable<Object> callable(Body body) {
        return () -> {
            try {
                return body.proceed();
            } catch (Throwable failure) {
                return ManagedRuntimeDispatcher.<Object, RuntimeException>raise(failure);
            }
        };
    }

    // Callable has a narrower throws clause than MethodInvocation; retain original Throwable identity.
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T raise(Throwable failure) throws E {
        throw (E) failure;
    }
}
