package demo

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import java.util.concurrent.Callable

object SafeRunner {
    internal val policy =
        WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .base(Policy.NO_NETWORK)
            .build()

    fun run(payload: String) = runContained { VulnerableLogger.log(payload) }

    internal fun runContained(action: () -> String) {
        OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
            val domain = runtime.prepare(policy, DomainLimits(1, 1))
            OwnedDemoRuntime.await(runtime.submit(domain, Callable(action)))
        }
    }
}
