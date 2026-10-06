package consumer;

import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.api.Policy;

public final class UncastCallable {
    public static String invoke(Policy<?, ?> policy) {
        return Mazewall.runContained(policy, () -> "contained");
    }
}
