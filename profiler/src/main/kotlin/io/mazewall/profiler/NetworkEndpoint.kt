package io.mazewall.profiler

/** A connect() or equivalent destination observed during profiling. */
public data class NetworkEndpoint(
    val host: String,
    val port: Int? = null,
) {
    override fun toString(): String = if (port != null) "$host:$port" else host
}
