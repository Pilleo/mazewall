package consumer

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.PolicyState
import io.mazewall.enforcer.api.Mazewall
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.platform.syscall.Syscall
import java.util.concurrent.Callable

fun callableExample(runtime: ManagedRuntime, domain: InProcessDomain): String =
    Mazewall.callContained(runtime, domain, Callable { "contained" })

fun main() {
    val policy: Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).apply {
        base(Policy.NO_EXEC_HOTSPOT)
        allowFsRead("/tmp/consumer-resource")
    }.build()
    check(policy.allowedFsReadPaths.size == 1)
    check(!policy.isSyscallAllowed(Syscall.EXECVE))
    val mappings = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowMmapExec(true)
    val enabled = mappings.build()
    mappings.allowMmapExec(false)
    check(enabled.allowMmapExec)
    check(!mappings.build().allowMmapExec)
    println("Packaged Kotlin policy construction passed.")
}
