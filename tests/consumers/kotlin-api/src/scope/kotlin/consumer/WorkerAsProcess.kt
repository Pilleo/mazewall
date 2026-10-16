package consumer

fun probe() = io.mazewall.enforcer.api.ContainedExecutors.installOnProcess(io.mazewall.enforcer.api.WorkerPolicyBuilder.denyList(io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT).build())
