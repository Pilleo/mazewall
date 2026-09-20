package io.mazewall.profiler.internal.attribution

import java.nio.file.Path

/** Producer-closure proof supplied by the lifecycle owner, never inferred from a quiet ring. */
internal enum class TierEProducerClosure {
    PROVEN,
    UNPROVEN,
}

/** Definition artifact paired with the session identity that is allowed to admit it. */
internal data class TierEDefinitionArtifact(
    val path: Path,
    val expectedSessionEpoch: SessionEpoch,
)

/** Immutable terminal outcome owned by one [TierECollectorRuntime]. */
internal data class TierECollectorRuntimeResult(
    val profile: TierEProfile,
    val metrics: TierECollectorServiceMetrics,
    val terminalReason: TierETerminalReason,
)

internal enum class TierETerminalReason {
    COMPLETE,
    PRODUCER_CLOSURE_UNPROVEN,
    ARTIFACT_SESSION_MISMATCH,
}

/**
 * Production-facing lifecycle owner for a [TierEProfilerSession] and its dedicated collector.
 *
 * The runtime intentionally owns no attribution or dictionary logic. It only serializes start and
 * terminalization, verifies that a caller's definition artifact belongs to this session, and keeps
 * exactly one immutable terminal result.
 */
internal class TierECollectorRuntime(
    private val session: TierEProfilerSession,
    policy: TierECollectorLoopPolicy = TierECollectorLoopPolicy.CONTINUOUS,
    onResolved: (List<ResolvedSyscall>) -> Unit = {},
    stopJoinMillis: Long = DEFAULT_STOP_JOIN_MILLIS,
    threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "mazewall-tier-e-collector").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val service = TierECollectorService(
        session = session,
        policy = policy,
        onResolved = onResolved,
        stopJoinMillis = stopJoinMillis,
        threadFactory = threadFactory,
    )

    private var state = TierECollectorRuntimeState.NEW
    private var terminalRequest: TierETerminalRequest? = null
    private var terminalResult: TierECollectorRuntimeResult? = null

    @Synchronized
    fun start() {
        check(state == TierECollectorRuntimeState.NEW) { "Tier E collector runtime can only start once" }
        check(session.isCollectorStartable()) { "Tier E profiler session is not collecting" }
        service.start()
        state = TierECollectorRuntimeState.RUNNING
    }

    @Synchronized
    fun finish(
        artifact: TierEDefinitionArtifact,
        producerClosure: TierEProducerClosure,
    ): TierECollectorRuntimeResult {
        val request = TierETerminalRequest(artifact, producerClosure)
        terminalResult?.let { existing ->
            check(terminalRequest == request) { "Tier E collector runtime already has a different terminal request" }
            return existing
        }
        check(state == TierECollectorRuntimeState.RUNNING) {
            "Tier E collector runtime must be running before finish"
        }

        val artifactMatchesSession = artifact.expectedSessionEpoch == session.currentSessionEpoch()
        val completeLifecycle = producerClosure == TierEProducerClosure.PROVEN && artifactMatchesSession
        val profile = service.finish(artifact.path, producerClosed = completeLifecycle)
        val reason = when {
            !artifactMatchesSession -> TierETerminalReason.ARTIFACT_SESSION_MISMATCH
            producerClosure == TierEProducerClosure.UNPROVEN -> TierETerminalReason.PRODUCER_CLOSURE_UNPROVEN
            else -> TierETerminalReason.COMPLETE
        }
        return TierECollectorRuntimeResult(profile, service.metrics(), reason).also {
            terminalRequest = request
            terminalResult = it
            state = TierECollectorRuntimeState.TERMINAL
        }
    }

    @Synchronized
    fun metrics(): TierECollectorServiceMetrics = service.metrics()

    @Synchronized
    override fun close() {
        if (state == TierECollectorRuntimeState.TERMINAL) return
        service.close()
        state = TierECollectorRuntimeState.TERMINAL
    }

    private companion object {
        const val DEFAULT_STOP_JOIN_MILLIS = 1_000L
    }
}

private enum class TierECollectorRuntimeState {
    NEW,
    RUNNING,
    TERMINAL,
}

private data class TierETerminalRequest(
    val artifact: TierEDefinitionArtifact,
    val producerClosure: TierEProducerClosure,
)
