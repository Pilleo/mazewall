package consumer

fun probe() = io.mazewall.enforcer.api.ProcessPolicyBuilder.denyList(io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT).allowFsRead("/tmp")
