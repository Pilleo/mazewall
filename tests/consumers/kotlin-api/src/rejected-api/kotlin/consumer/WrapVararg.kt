package consumer

fun probe(raw: java.util.concurrent.ExecutorService) =
    io.mazewall.enforcer.api.ContainedExecutors.wrap(raw, *arrayOf(io.mazewall.enforcer.api.Policy.NO_EXEC))
