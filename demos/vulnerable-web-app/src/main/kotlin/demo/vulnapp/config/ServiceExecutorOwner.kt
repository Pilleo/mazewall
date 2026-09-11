package demo.vulnapp.config

import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface ExecutorDrain {
    data object Complete : ExecutorDrain

    sealed interface Incomplete : ExecutorDrain {
        val reason: String

        data class Timeout(
            val waited: Duration,
        ) : Incomplete {
            override val reason: String = "timeout after ${waited.toMillis()}ms"
        }

        data object Interrupted : Incomplete {
            override val reason: String = "interrupted"
        }

        data class ProfilerCleanup(
            val message: String,
        ) : Incomplete {
            override val reason: String = "profiler cleanup failed: $message"
        }

        data class CollectorCoverage(
            val message: String,
        ) : Incomplete {
            override val reason: String = "profiler coverage incomplete: $message"
        }

        data class Multiple(
            val failures: List<Incomplete>,
        ) : Incomplete {
            init {
                require(failures.size >= 2) { "multiple failures requires at least two causes" }
            }

            override val reason: String = failures.joinToString(separator = "; ", transform = Incomplete::reason)
        }
    }

    companion object {
        fun combine(
            first: ExecutorDrain,
            second: Incomplete,
        ): Incomplete =
            when (first) {
                Complete -> second
                is Incomplete.Multiple -> Incomplete.Multiple(first.failures + second)
                is Incomplete -> Incomplete.Multiple(listOf(first, second))
            }
    }
}

/** One admission and lifecycle boundary for every service-specific executor. */
internal class OwnedServiceExecutor(
    private val delegate: ExecutorService,
) {
    private val accepting = AtomicBoolean(true)

    fun <T> call(block: () -> T): T {
        if (!accepting.get()) throw RejectedExecutionException("service executor is draining")
        val accepted = delegate.submit(Callable(block))
        return try {
            accepted.get()
        } catch (failure: InterruptedException) {
            accepted.cancel(true)
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    fun execute(block: () -> Unit) {
        if (!accepting.get()) throw RejectedExecutionException("service executor is draining")
        delegate.execute(block)
    }

    internal fun stopAdmission() {
        accepting.set(false)
        delegate.shutdown()
    }

    internal fun awaitDrain(timeout: Duration): ExecutorDrain {
        return try {
            if (delegate.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ExecutorDrain.Complete
            } else {
                delegate.shutdownNow()
                ExecutorDrain.Incomplete.Timeout(timeout)
            }
        } catch (_: InterruptedException) {
            delegate.shutdownNow()
            Thread.currentThread().interrupt()
            ExecutorDrain.Incomplete.Interrupted
        }
    }

    internal fun forceStop() {
        accepting.set(false)
        delegate.shutdownNow()
    }
}

internal class ServiceExecutorOwner(
    private val drainTimeout: Duration,
) {
    private val executors = CopyOnWriteArrayList<OwnedServiceExecutor>()
    private val closed = AtomicBoolean()

    @Volatile private var result: ExecutorDrain? = null

    @Synchronized
    fun own(delegate: ExecutorService): OwnedServiceExecutor {
        check(!closed.get()) { "service executor owner is closed" }
        return OwnedServiceExecutor(delegate).also { executors += it }
    }

    @Synchronized
    fun stopAdmissionAndDrain(): ExecutorDrain {
        result?.let { return it }
        closed.set(true)
        executors.forEach(OwnedServiceExecutor::stopAdmission)
        val deadline = System.nanoTime() + drainTimeout.toNanos()
        var outcome: ExecutorDrain = ExecutorDrain.Complete
        for (executor in executors) {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
            val drained = executor.awaitDrain(Duration.ofNanos(remaining))
            if (drained is ExecutorDrain.Incomplete) {
                outcome = drained
                break
            }
        }
        if (outcome is ExecutorDrain.Incomplete) executors.forEach(OwnedServiceExecutor::forceStop)
        result = outcome
        return outcome
    }

    @PreDestroy
    fun close() {
        stopAdmissionAndDrain()
    }
}

@Configuration
internal class ServiceExecutionConfig {
    @Bean
    fun serviceExecutorOwner(): ServiceExecutorOwner {
        val seconds = System.getProperty("mazewall.executor-drain-seconds", "10").toLongOrNull()
        require(seconds != null && seconds in 1..300) { "mazewall.executor-drain-seconds must be in 1..300" }
        return ServiceExecutorOwner(Duration.ofSeconds(seconds))
    }
}
