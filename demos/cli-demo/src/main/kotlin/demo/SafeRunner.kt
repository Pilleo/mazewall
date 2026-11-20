package demo

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.util.concurrent.Callable

object SafeRunner {
    internal val policy =
        WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_EXEC)
            .base(Policy.NO_NETWORK)
            .build()

    fun run(payload: String) = runContained { VulnerableLogger.log(payload) }

    internal fun runContained(action: () -> String) {
        withContainedDomain { runtime, domain ->
            OwnedDemoRuntime.await(runtime.submit(domain, Callable(action)))
        }
    }

    internal fun observeRefusal(name: String, operation: ControlledOperation, action: () -> String): ScenarioResult =
        withContainedDomain { runtime, domain ->
            observeControlledRefusal(name, operation) {
                OwnedDemoRuntime.await(runtime.submit(domain, Callable(action)))
            }
        }

    private fun <T> withContainedDomain(action: (ManagedRuntime, InProcessDomain) -> T): T =
        OwnedDemoRuntime.run(Policy.NO_EXEC_HOTSPOT) { runtime ->
            val domain = runtime.prepare(policy, DomainLimits(1, 1))
            println("\u001b[32;1m[STATUS] BEHAVIORAL PROTECTION: ACTIVE\u001b[0m")
            println("Contract: Policy.NO_EXEC + Policy.NO_NETWORK")
            val benign = "benign demo input"
            check(OwnedDemoRuntime.await(runtime.submit(domain, Callable { VulnerableLogger.log(benign) })) == "Logged: $benign") {
                "benign logging failed on the restricted worker"
            }
            println("CONTROL: benign logging succeeds on the restricted worker")
            action(runtime, domain)
        }
}
