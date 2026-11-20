package io.mazewall.profiler.hotspot

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession
import io.mazewall.profiler.api.StructuredProfileEvidence
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Collections
import java.util.IdentityHashMap

private const val MAX_OBSERVED_MAPS_BYTES = 16 * 1024 * 1024
private const val MAX_OBSERVED_FAILURES = 64

internal fun readObservedMaps(path: Path): ByteArray {
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_OBSERVED_MAPS_BYTES + 1) }
    require(bytes.isNotEmpty() && bytes.size <= MAX_OBSERVED_MAPS_BYTES) { "Missing or oversized target maps" }
    return bytes
}

internal fun retainObservedMaps(
    path: Path,
    destination: Path,
    identity: HotSpotObservationTarget,
    identify: () -> HotSpotObservationTarget,
) {
    require(identify() == identity) { "Target identity changed before maps read" }
    val bytes = readObservedMaps(path)
    require(identify() == identity) { "Target identity changed during maps read" }
    Files.write(destination, bytes, CREATE_NEW)
}

internal fun observedCompiledMarkedEvents(
    evidence: StructuredProfileEvidence,
    markedNames: Set<String>,
): Set<String> =
    evidence.records
        .filter { record ->
            record.event.filename.substringAfterLast('/') in markedNames &&
                !record.event.pathTruncated &&
                record.evidence.attribution == "event_correlated" &&
                record.evidence.frames.none { it.errorFrame } &&
                record.evidence.frames.any { frame ->
                    frame.scopeLookup in setOf("exact_pc", "preceding_pc") &&
                        frame.scopeWalk == "returned" &&
                        listOf("leaf", "routeA", "routeB").any { method -> frame.functionName.contains("ObservedWorkload.$method(") }
                }
        }.map { it.eventId }
        .toSet()

private fun observedHotSpotFailures(failure: Throwable): List<HotSpotProfilingException> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = mutableListOf<Throwable>()

    fun enqueue(value: Throwable?) {
        if (value != null && pending.size < MAX_OBSERVED_FAILURES && seen.add(value)) pending += value
    }
    enqueue(failure)
    var index = 0
    while (index < pending.size) {
        val current = pending[index++]
        enqueue(current.cause)
        // shortcut: at most 64 throwable identities, widen only when a real attempt needs deeper typed evidence.
        for (suppressed in current.suppressed) {
            if (pending.size == MAX_OBSERVED_FAILURES) break
            enqueue(suppressed)
        }
    }
    return pending.filterIsInstance<HotSpotProfilingException>()
}

internal fun retainObservedFailure(
    directory: Path,
    failure: Throwable,
): Throwable {
    var result = failure
    val files = mutableListOf(
        "failure.txt" to failure.stackTraceToString(),
        "root-failure-pending-ownership.txt" to if (failure is HotSpotProfilingException) failure.pendingOwnership.toString() else "unknown",
    )
    val nativeFailures = observedHotSpotFailures(failure)
    if (nativeFailures.isEmpty()) files += "pending-ownership.txt" to "unknown"
    for ((index, native) in nativeFailures.withIndex()) {
        val suffix = if (index == 0) "" else "-${index + 1}"
        files += "collector-failure$suffix.stderr" to native.diagnostics
        files += "collector-failure$suffix-discarded-bytes.txt" to native.discardedDiagnosticBytes.toString()
        files += "native-errnos$suffix.json" to native.nativeErrnos.joinToString(prefix = "[", postfix = "]")
        files += "pending-ownership$suffix.txt" to native.pendingOwnership.toString()
        try {
            native.partialResult?.structuredEvidence?.let { files += "partial-evidence$suffix.json" to it.toJson() }
        } catch (serialization: Throwable) {
            result = CleanupFailures.merge(result, serialization)
        }
    }
    for ((name, text) in files) {
        try {
            Files.writeString(directory.resolve(name), text, CREATE_NEW)
        } catch (write: Throwable) {
            result = CleanupFailures.merge(result, write)
        }
    }
    return result
}

internal fun closeObservedSession(
    directory: Path,
    collector: HotSpotProfilingSession,
    failure: Throwable?,
): Throwable? {
    var result = failure

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (caught: Throwable) {
            result = CleanupFailures.merge(result, caught)
        }
    }
    attempt { Files.writeString(directory.resolve("collector-before-close.stderr"), collector.diagnostics, CREATE_NEW) }
    attempt { Files.writeString(directory.resolve("collector-before-close-discarded-bytes.txt"), collector.discardedDiagnosticBytes.toString(), CREATE_NEW) }
    attempt { collector.close() }
    attempt { Files.writeString(directory.resolve("collector-after-close.stderr"), collector.diagnostics, CREATE_NEW) }
    attempt { Files.writeString(directory.resolve("collector-after-close-discarded-bytes.txt"), collector.discardedDiagnosticBytes.toString(), CREATE_NEW) }
    attempt { Files.writeString(directory.resolve("pending-ownership-after-close.txt"), collector.pendingOwnership.toString(), CREATE_NEW) }
    return result
}
