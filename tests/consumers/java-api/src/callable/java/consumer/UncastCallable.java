package consumer;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.api.execution.ManagedRuntime;
import io.mazewall.enforcer.api.execution.InProcessDomain;

public final class UncastCallable {
    public static String invoke(ManagedRuntime runtime, InProcessDomain domain) {
        return Mazewall.callContained(runtime, domain, () -> "contained");
    }
}
