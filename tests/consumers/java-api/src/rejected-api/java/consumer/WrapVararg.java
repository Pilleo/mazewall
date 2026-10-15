package consumer;

public final class WrapVararg {
    void probe(java.util.concurrent.ExecutorService raw) {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.wrap(raw, new io.mazewall.enforcer.api.Policy[] {io.mazewall.enforcer.api.Policy.NO_EXEC});
    }
}
