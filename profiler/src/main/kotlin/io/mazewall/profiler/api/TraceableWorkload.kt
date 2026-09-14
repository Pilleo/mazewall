package io.mazewall.profiler.api

import io.mazewall.platform.lifecycle.MazewallInternal

/**
 * Marker for descendant-strace child JVMs (floor/lab). Not operator API.
 * Application profiling uses [MazewallProfiler.profile] with a lambda.
 */
@MazewallInternal
internal interface TraceableWorkload : Runnable
