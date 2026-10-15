package consumer;

public final class CachedFactory {
    void probe() {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.newCachedThreadPool(io.mazewall.enforcer.api.Policy.NO_EXEC);
    }
}
