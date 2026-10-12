package demo

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.IncompleteDrainException
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Application-retained owners survive cancelled results and incomplete bounded drain. */
internal object OwnedDemoRuntime {
    private val retained = ConcurrentHashMap.newKeySet<ManagedRuntime>()
    private val timeout = Duration.ofSeconds(10)

    fun <T> run(baseline: Policy<PolicyScope.ProcessWideSafe, Uncompiled>, action: (ManagedRuntime) -> T): T {
        val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout)
        retained += runtime
        var primary: Throwable? = null
        try {
            return action(runtime)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val interrupted = Thread.interrupted()
            try {
                when (val report = runtime.drain(timeout)) {
                    DrainReport.Complete -> retained.remove(runtime)
                    is DrainReport.Incomplete -> {
                        val failure = IncompleteDrainException(report)
                        if (primary == null) throw failure else primary.addSuppressed(failure)
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    fun <T> await(invocation: InvocationHandle<T>): T = try {
        invocation.result().toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } catch (failure: TimeoutException) {
        invocation.requestCancellation()
        throw failure
    } catch (failure: InterruptedException) {
        invocation.requestCancellation()
        Thread.currentThread().interrupt()
        throw failure
    }

    fun drainRetained(): List<DrainReport.Incomplete> = retained.mapNotNull { runtime ->
        when (val report = runtime.drain(timeout)) {
            DrainReport.Complete -> { retained.remove(runtime); null }
            is DrainReport.Incomplete -> report
        }
    }
}
