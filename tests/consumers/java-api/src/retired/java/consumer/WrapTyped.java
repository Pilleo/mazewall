package consumer;

public final class WrapTyped {
    void probe(java.util.concurrent.ExecutorService raw) {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.wrapTyped(raw, io.mazewall.enforcer.api.Policy.NO_EXEC);
    }
}
