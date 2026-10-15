package consumer;

public final class WrapScoping {
    void probe(java.util.concurrent.ExecutorService raw, io.mazewall.enforcer.api.StacktraceScopingPolicy scoping) {
        io.mazewall.enforcer.api.ContainedExecutors.INSTANCE.wrap(raw, io.mazewall.enforcer.api.Policy.NO_EXEC, scoping);
    }
}
