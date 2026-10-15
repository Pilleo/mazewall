package consumer

fun probe() = io.mazewall.enforcer.api.ContainedExecutors.newSingleThreadExecutor(io.mazewall.enforcer.api.Policy.NO_EXEC)
