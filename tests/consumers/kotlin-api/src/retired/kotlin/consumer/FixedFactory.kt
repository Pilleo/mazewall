package consumer

fun probe() = io.mazewall.enforcer.api.ContainedExecutors.newFixedThreadPool(2, io.mazewall.enforcer.api.Policy.NO_EXEC)
