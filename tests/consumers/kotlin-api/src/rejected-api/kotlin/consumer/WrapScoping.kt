package consumer

fun probe(raw: java.util.concurrent.ExecutorService, scoping: io.mazewall.enforcer.api.StacktraceScopingPolicy) =
    io.mazewall.enforcer.api.ContainedExecutors.wrap(raw, io.mazewall.enforcer.api.Policy.NO_EXEC, scoping)
