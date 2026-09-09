package io.mazewall.profiler.engine

/**
 * Canonical, process-independent behavioral identity for [TraceEvent].
 *
 * This deliberately excludes [TraceEvent.tid], matching the event equality
 * contract used to deduplicate observations across JVM worker threads.
 */
internal data class TraceEventKey(
    val kind: String,
    val values: List<Any?>,
)

/** Pure conversion from a mutable-runtime event to its immutable behavioral key. */
internal fun TraceEvent.behavioralKey(): TraceEventKey =
    when (this) {
        is TraceEvent.Generic -> TraceEventKey("Generic", listOf(syscallName, args, paths, stackTrace, pathResolution))
        is TraceEvent.Open -> TraceEventKey("Open", listOf(syscallName, path, flags, mode, stackTrace, pathResolution))
        is TraceEvent.Exec -> TraceEventKey("Exec", listOf(syscallName, path, stackTrace, pathResolution))
        is TraceEvent.Mmap -> TraceEventKey("Mmap", listOf(addr, len, protection, mappingFlags, fileDescriptor, offset, stackTrace, pathResolution))
        is TraceEvent.Socket -> TraceEventKey("Socket", listOf(domain, type, protocol, stackTrace, pathResolution))
        is TraceEvent.FsMutation -> TraceEventKey("FsMutation", listOf(syscallName, paths, stackTrace, pathResolution))
    }
