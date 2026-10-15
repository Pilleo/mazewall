package consumer

fun probe() = io.mazewall.enforcer.api.ContainedExecutors.newCachedThreadPool(io.mazewall.enforcer.api.Policy.NO_EXEC)
