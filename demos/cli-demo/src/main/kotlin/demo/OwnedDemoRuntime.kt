package demo

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.IncompleteDrainException
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.MazewallInternal
import java.time.Duration
import java.util.concurrent.CancellationException
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

    /** Delivery can precede lease release; this synchronous demo waits for both before another admission. */
    @OptIn(MazewallInternal::class)
    fun <T> await(invocation: InvocationHandle<T>): T {
        val started = System.nanoTime()
        val budget = timeout.toNanos()
        var delivery: Result<T>? = null
        try {
            delivery = try {
                Result.success(invocation.result().toCompletableFuture().get(budget, TimeUnit.NANOSECONDS))
            } catch (failure: ExecutionException) {
                Result.failure(failure.cause ?: failure)
            } catch (failure: CancellationException) {
                Result.failure(failure)
            }
            invocation.executionEnd().toCompletableFuture().get(
                (budget - (System.nanoTime() - started)).coerceAtLeast(0L), TimeUnit.NANOSECONDS,
            )
        } catch (failure: Throwable) {
            val cause = if (failure is ExecutionException) failure.cause ?: failure else failure
            val deliveredFailure = delivery?.exceptionOrNull()
            // A failed end wait must not look like a completed expected denial.
            var primary = if (deliveredFailure == null) cause else CleanupFailures.merge(cause, deliveredFailure)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is InterruptedException || failure is TimeoutException) {
                try {
                    invocation.requestCancellation()
                } catch (requestFailure: Throwable) {
                    primary = CleanupFailures.merge(primary, requestFailure)
                }
            }
            throw primary
        }
        return checkNotNull(delivery).getOrThrow()
    }

    fun drainRetained(): List<DrainReport.Incomplete> = retained.mapNotNull { runtime ->
        when (val report = runtime.drain(timeout)) {
            DrainReport.Complete -> { retained.remove(runtime); null }
            is DrainReport.Incomplete -> report
        }
    }
}
