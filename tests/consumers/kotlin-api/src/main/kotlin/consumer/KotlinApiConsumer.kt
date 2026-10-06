package consumer

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.runContained
import io.mazewall.enforcer.api.threadLocalBuilder
import io.mazewall.enforcer.api.noExec
import io.mazewall.platform.syscall.Syscall
import java.util.concurrent.Callable

fun callableExample(policy: Policy<*, *>): String =
    runContained(policy, Callable { "contained" })

fun main() {
    val policy: Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = threadLocalBuilder().apply {
        base(noExec())
        allowFsRead("/tmp/consumer-resource")
    }.build()
    check(policy.allowedFsReadPaths.size == 1)
    check(!policy.isSyscallAllowed(Syscall.EXECVE))
    val mappings = threadLocalBuilder().allowMmapExec(true)
    val enabled = mappings.build()
    mappings.allowMmapExec(false)
    check(enabled.allowMmapExec)
    check(!mappings.build().allowMmapExec)
    println("Packaged Kotlin policy construction passed.")
}
