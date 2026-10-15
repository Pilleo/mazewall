package consumer;

public final class FixedFactory {
    void probe() {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.newFixedThreadPool(2, io.mazewall.enforcer.api.Policy.NO_EXEC);
    }
}
