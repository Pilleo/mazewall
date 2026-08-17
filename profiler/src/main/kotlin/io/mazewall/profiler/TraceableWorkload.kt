package io.mazewall.profiler

import io.mazewall.MazewallInternal

/**
 * Marker for descendant-strace child JVMs (floor/lab). Not operator API.
 * Application profiling uses [MazewallProfiler.profile] with a lambda.
 */
@MazewallInternal
interface TraceableWorkload : Runnable
