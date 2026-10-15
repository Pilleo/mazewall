package consumer

fun probe(raw: java.util.concurrent.ExecutorService) =
    io.mazewall.enforcer.api.ContainedExecutors.wrapTyped(raw, io.mazewall.enforcer.api.Policy.NO_EXEC)
