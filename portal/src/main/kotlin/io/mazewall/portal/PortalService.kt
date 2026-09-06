package io.mazewall.portal

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Configuration for one long-lived, process-isolated portal service. */
public data class PortalWorkerConfig(
    val classpath: List<Path>,
    val implementationClassName: String,
    val concurrency: Int = 4,
    val callTimeout: Duration = Duration.ofSeconds(30),
    val startupTimeout: Duration = Duration.ofSeconds(30),
    val maxHeap: String = "64m",
) {
    init {
        require(classpath.isNotEmpty()) { "portal worker classpath is required" }
        require(implementationClassName.isNotBlank()) { "portal worker implementation class is required" }
        require(concurrency >= 1) { "portal worker concurrency must be >= 1" }
        require(!callTimeout.isZero && !callTimeout.isNegative) { "portal call timeout must be positive" }
        require(!startupTimeout.isZero && !startupTimeout.isNegative) { "portal startup timeout must be positive" }
        require(maxHeap.isNotBlank()) { "portal worker max heap is required" }
    }
}

/** Owns one worker process and its generated host API. */
public class PortalService<T : Any> internal constructor(
    public val api: T,
    private val client: PortalClient,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            (client as? AutoCloseable)?.close()
        }
    }
}
