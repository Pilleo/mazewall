package io.mazewall.profiler

import io.mazewall.profiler.engine.TraceEvent

/**
 * Internal value object for stack attribution data.
 *
 * Kotlin/JVM arrays are mutable and compare by identity, so this type owns all
 * copies and supplies content-based equality and hashing for profile results.
 */
internal class ImmutableStackProfile private constructor(
    private val entries: Map<TraceEvent, List<Array<StackTraceElement>>>,
) {
    fun snapshot(): Map<TraceEvent, List<Array<StackTraceElement>>> = java.util.Map.copyOf(entries.mapValues { (_, frames) -> java.util.List.copyOf(frames.map { it.copyOf() }) })

    override fun equals(other: Any?): Boolean =
        other is ImmutableStackProfile &&
            entries.size == other.entries.size &&
            entries.all { (event, frames) ->
                other.entries[event]?.let { otherFrames ->
                    frames.size == otherFrames.size &&
                        frames.indices.all { index -> frames[index].contentEquals(otherFrames[index]) }
                } == true
            }

    override fun hashCode(): Int =
        entries.entries.fold(0) { hash, entry ->
            hash +
                31 *
                entry.key.hashCode() +
                entry.value.fold(0) { framesHash, frame ->
                framesHash + frame.contentHashCode()
            }
        }

    companion object {
        fun copyOf(value: Map<TraceEvent, List<Array<StackTraceElement>>>): ImmutableStackProfile =
            ImmutableStackProfile(
                java.util.Map.copyOf(
                    value.mapValues { (_, frames) -> java.util.List.copyOf(frames.map { it.copyOf() }) },
                ),
            )
    }
}
