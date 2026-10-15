package consumer;

public final class SingleFactory {
    void probe() {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.newSingleThreadExecutor(io.mazewall.enforcer.api.Policy.NO_EXEC);
    }
}
