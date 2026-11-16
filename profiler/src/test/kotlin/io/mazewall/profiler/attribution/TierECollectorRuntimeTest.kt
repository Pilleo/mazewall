package io.mazewall.profiler.internal.attribution

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.tierE.ringbuf.BpfRingBufferReader
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TierECollectorRuntimeTest {
    @Test
    fun `every immutable contents field participates in terminal identity`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val publication = SnapshotPublication(1, 0, true, session.currentSessionEpoch())
        val original = AgentDefinitionFile.Contents(emptyList(), emptyList(), 0, publication = publication)
        val variants = listOf(
            AgentDefinitionFile.Contents(
                listOf(StackDefinition(StackTraceId(1), emptyList(), StackCaptureQuality.COMPLETE)),
                emptyList(),
                0,
                publication = publication,
            ),
            AgentDefinitionFile.Contents(
                emptyList(),
                listOf(InvocationDefinition(InvocationId(1), null, null, CaptureFailure.STACK_WALK_FAILED)),
                0,
                publication = publication,
            ),
            AgentDefinitionFile.Contents(
                emptyList(),
                emptyList(),
                0,
                listOf(ExecutionContext(ExecutionId(1), ExecutionKind.VIRTUAL)),
                publication = publication,
            ),
            AgentDefinitionFile.Contents(emptyList(), emptyList(), 0, virtualPinnedScopes = 1, publication = publication),
            AgentDefinitionFile.Contents(emptyList(), emptyList(), 0, publication = publication, sourceByteLength = 1),
            AgentDefinitionFile.Contents(emptyList(), emptyList(), 0, publication = publication, sourceSha256 = "different"),
        )
        runtime.start()
        val first = runtime.finish(original, TierEProducerClosure.PROVEN)
        for (variant in variants) {
            assertFailsWith<IllegalStateException> { runtime.finish(variant, TierEProducerClosure.PROVEN) }
            assertSame(first, runtime.finish(original, TierEProducerClosure.PROVEN))
        }
        runtime.close()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `failed in memory finish keeps original failure across conflicting retries`() {
        val source = RuntimeSource()
        val failure = IllegalStateException("terminal counter failure")
        source.counterFailure = failure
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val original = AgentDefinitionFile.Contents(
            emptyList(),
            emptyList(),
            0,
            publication = SnapshotPublication(1, 0, true, session.currentSessionEpoch()),
        )
        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(original, TierEProducerClosure.PROVEN) })
        source.counterFailure = null
        val operations = source.operations.toList()
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
            runtime.finish(AgentDefinitionFile.Contents(emptyList(), emptyList(), 1), TierEProducerClosure.UNPROVEN)
        },
        )
        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(original, TierEProducerClosure.PROVEN) })
        assertEquals(operations, source.operations)
        runtime.close()
        runtime.close()
        assertEquals(operations + "close", source.operations)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `wrong session first admission remains incomplete and cannot be replaced`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val wrong = AgentDefinitionFile.Contents(
            emptyList(),
            emptyList(),
            0,
            publication = SnapshotPublication(1, 0, true, SessionEpoch(session.currentSessionEpoch().value + 1)),
        )
        runtime.start()
        val first = runtime.finish(wrong, TierEProducerClosure.PROVEN)
        assertEquals(TierETerminalReason.ARTIFACT_SESSION_MISMATCH, first.terminalReason)
        assertEquals(CaptureGuarantee.INCOMPLETE, first.profile.integrity.guarantee)
        assertFailsWith<IllegalStateException> {
            runtime.finish(
                AgentDefinitionFile.Contents(
                    emptyList(),
                    emptyList(),
                    0,
                    publication = wrong.publication!!.copy(sessionEpoch = session.currentSessionEpoch()),
                ),
                TierEProducerClosure.PROVEN,
            )
        }
        assertSame(first, runtime.finish(wrong, TierEProducerClosure.PROVEN))
        runtime.close()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `in memory terminal identity rejects conflicts without repeating effects`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val epoch = session.currentSessionEpoch()
        val publication = SnapshotPublication(1, 0, true, epoch)

        fun contents(
            metadata: SnapshotPublication = publication,
            losses: Long = 0,
            pinned: Long = 0,
        ) = AgentDefinitionFile.Contents(
            emptyList(),
            emptyList(),
            losses,
            virtualPinnedNanos = pinned,
            publication = metadata,
            sourceSha256 = "same claimed digest",
        )
        runtime.start()
        val first = runtime.finish(contents(), TierEProducerClosure.PROVEN)
        val operations = source.operations.toList()
        assertSame(first, runtime.finish(contents(), TierEProducerClosure.PROVEN))
        for (conflict in listOf(
            contents(publication.copy(sessionEpoch = SessionEpoch(epoch.value + 1))),
            contents(publication.copy(publicationId = 2)),
            contents(publication.copy(watermark = 1)),
            contents(publication.copy(closed = false)),
            contents(losses = 1),
            contents(pinned = 1),
        )) {
            assertFailsWith<IllegalStateException> { runtime.finish(conflict, TierEProducerClosure.PROVEN) }
            assertSame(first, runtime.finish(contents(), TierEProducerClosure.PROVEN))
        }
        assertFailsWith<IllegalStateException> { runtime.finish(contents(), TierEProducerClosure.UNPROVEN) }
        assertFailsWith<IllegalStateException> {
            runtime.finish(TierEDefinitionArtifact(Path.of("must-not-open"), epoch), TierEProducerClosure.PROVEN)
        }
        runtime.close()
        assertEquals(operations, source.operations)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `caller mutation cannot change admitted contents or terminal evidence`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val frames = mutableListOf(ManagedFrame("loader", "Example", "call", "()V", 0, ManagedFrameKind.JAVA))
        val stacks = mutableListOf(StackDefinition(StackTraceId(1), frames, StackCaptureQuality.COMPLETE))
        val invocations = mutableListOf(InvocationDefinition(InvocationId(1), null, StackTraceId(1)))
        val executions = mutableListOf(ExecutionContext(ExecutionId(1), ExecutionKind.PLATFORM))
        val definitions = AgentDefinitionFile.Contents(
            stacks,
            invocations,
            0,
            executions,
            publication = SnapshotPublication(1, 1, true, session.currentSessionEpoch()),
        )
        runtime.start()
        val first = runtime.finish(definitions, TierEProducerClosure.PROVEN)
        val mutation = Thread {
            frames.clear()
            stacks.clear()
            invocations.clear()
            executions.clear()
        }
        mutation.start()
        mutation.join()
        assertEquals(
            1,
            definitions.stacks
            .single()
            .frames.size,
        )
        assertEquals(1, definitions.invocations.size)
        assertEquals(1, definitions.executions.size)
        assertSame(first, runtime.finish(definitions, TierEProducerClosure.PROVEN))
        assertFailsWith<IllegalStateException> {
            runtime.finish(
                AgentDefinitionFile.Contents(stacks, invocations, 0, executions, publication = definitions.publication),
                TierEProducerClosure.PROVEN,
            )
        }
        assertSame(first, runtime.finish(definitions, TierEProducerClosure.PROVEN))
        runtime.close()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `blocked poll cannot hold terminal metrics past the bounded join`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = java.util.concurrent.atomic
            .AtomicReference<TierECollectorRuntimeResult?>()
        val failure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val source = RuntimeSource(drain = {
            entered.countDown()
            var waiting = true
            while (waiting) {
                try {
                    release.await()
                    waiting = false
                } catch (_: InterruptedException) {
                    // Native drain may ignore interruption while holding the real poll monitor.
                }
            }
        })
        val session = session(source)
        val runtime = TierECollectorRuntime(session, stopJoinMillis = 10)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
        val finishing = Thread {
            try {
                result.set(runtime.finish(artifact, TierEProducerClosure.PROVEN))
            } catch (cause: Throwable) {
                failure.set(cause)
            } finally {
                finished.countDown()
            }
        }
        try {
            runtime.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            finishing.start()
            assertTrue(finished.await(1, TimeUnit.SECONDS), "terminal metrics must not wait for the blocked poll monitor")
            assertEquals(null, failure.get())
            assertEquals(
                CaptureGuarantee.INCOMPLETE,
                result
                .get()!!
                .profile.integrity.guarantee,
            )
            assertEquals(0, source.closeCalls)
            assertTrue(runtime.metrics().workerAlive)
            assertFailsWith<IllegalStateException> { runtime.close() }
            assertEquals(0, source.closeCalls)
        } finally {
            release.countDown()
            finishing.join(2_000)
            runtime.close()
        }
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `terminal join timeout retains physical ownership and cached incomplete evidence`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(
            session,
            stopJoinMillis = 10,
            threadFactory = { action ->
                Thread {
                    entered.countDown()
                    try {
                        var waiting = true
                        while (waiting) {
                            try {
                                release.await()
                                waiting = false
                            } catch (_: InterruptedException) {
                                // Simulate a collector boundary which has not returned despite interrupt.
                            }
                        }
                        action.run()
                    } finally {
                        stopped.countDown()
                    }
                }
            },
        )
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
        runtime.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val result = runtime.finish(artifact, TierEProducerClosure.PROVEN)
            assertEquals(CaptureGuarantee.INCOMPLETE, result.profile.integrity.guarantee)
            assertTrue(runtime.metrics().workerAlive)
            assertFailsWith<IllegalStateException> { runtime.close() }
            assertEquals(0, source.closeCalls)
            release.countDown()
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            runtime.close()
            assertSame(result, runtime.finish(artifact, TierEProducerClosure.PROVEN))
        } finally {
            release.countDown()
            assertTrue(stopped.await(1, TimeUnit.SECONDS))
            runtime.close()
        }
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `real ring unmap failure is retried through terminal runtime without unmapping released region twice`() {
        Arena.ofShared().use { arena ->
            val metadata = arena.allocate(4096, 4096)
            val data = arena.allocate(4096 + 2 * 256, 4096)
            val unmaps = mutableListOf<Long>()
            var mappings = 0
            val native = object : MockNativeEngine() {
                override fun syscall(
                    nr: Long,
                    a1: NativeArg,
                    a2: NativeArg,
                    a3: NativeArg,
                    a4: NativeArg,
                    a5: NativeArg,
                    a6: NativeArg,
                ): SyscallResult<Long, SyscallHandledState.Unhandled> =
                    when (nr) {
                    Syscall.MMAP.numberFor(Arch.current()).toLong() -> SyscallResult.Success<Long, SyscallHandledState.Unhandled>(
                        if (mappings++ == 0) metadata.address() else data.address(),
                    )
                    Syscall.MUNMAP.numberFor(Arch.current()).toLong() -> {
                        val address = (a1 as NativeArg.LongArg).value
                        unmaps += address
                        if (address == data.address() && unmaps.count { it == address } == 1) {
                            SyscallResult.Error<SyscallHandledState.Unhandled>(22, -1L)
                        } else {
                            SyscallResult.Success<Long, SyscallHandledState.Unhandled>(0L)
                        }
                    }
                    else -> error("unexpected syscall $nr")
                }
            }
            val reader = BpfRingBufferReader(native, 7, 256, 8)
            val source = RuntimeSource(retire = reader::close)
            val session = session(source)
            val runtime = TierECollectorRuntime(session)
            val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
            runtime.start()

            val failure = assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) }
            runtime.close()
            runtime.close()

            assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) })
            assertEquals(2, source.closeCalls)
            assertEquals(1, unmaps.count { it == metadata.address() })
            assertEquals(2, unmaps.count { it == data.address() })
        }
    }

    @Test
    fun `failed finish retains failure while close retries the outstanding source`() {
        val source = RuntimeSource()
        val failure = IllegalStateException("terminal counters failed")
        val cleanup = IllegalStateException("unmap failed")
        source.counterFailure = failure
        source.closeFailure = cleanup
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))

        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) })
        assertSame(cleanup, assertFailsWith<IllegalStateException> { runtime.close() })
        runtime.close()
        runtime.close()

        assertEquals(2, source.closeCalls)
        assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) })
    }

    @Test
    fun `runtime starts once and caches one terminal result`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        assertFailsWith<IllegalStateException> { runtime.start() }

        val first = runtime.finish(artifact, TierEProducerClosure.PROVEN)

        assertSame(first, runtime.finish(artifact, TierEProducerClosure.PROVEN))
        assertFailsWith<IllegalStateException> {
            runtime.finish(AgentDefinitionFile.Contents(emptyList(), emptyList(), 0), TierEProducerClosure.PROVEN)
        }
        assertSame(first, runtime.finish(artifact, TierEProducerClosure.PROVEN))
        assertEquals(TierECollectorServiceState.TERMINAL, runtime.metrics().state)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `runtime rejects a mismatched artifact as incomplete evidence`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val wrongArtifact = TierEDefinitionArtifact(
            Path.of("ignored"),
            SessionEpoch(session.currentSessionEpoch().value + 1),
        )

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))

        val result = runtime.finish(wrongArtifact, TierEProducerClosure.PROVEN)

        assertEquals(CaptureGuarantee.INCOMPLETE, result.profile.integrity.guarantee)
        assertFailsWith<IllegalStateException> {
            runtime.finish(
                TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch()),
                TierEProducerClosure.PROVEN,
            )
        }
    }

    @Test
    fun `runtime terminal barrier detaches then drains before it closes the source`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        runtime.finish(artifact, TierEProducerClosure.UNPROVEN)

        val detach = source.operations.indexOf("detach")
        val finalDrain = source.operations.lastIndexOf("drain")
        val close = source.operations.indexOf("close")
        assertTrue(detach >= 0)
        assertTrue(detach < finalDrain)
        assertTrue(finalDrain < close)
    }

    @Test
    fun `terminal sink failure makes retained result transport incomplete`() {
        val source = RuntimeSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), session.currentSessionEpoch())
        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        val result = runtime.finish(artifact, TierEProducerClosure.PROVEN)

        val incomplete = result.withTerminalDeliveryFailure()

        assertEquals(1, incomplete.profile.integrity.terminalDeliveryFailures)
        assertFalse(incomplete.profile.integrity.verdictDimensions.transportComplete)
        assertEquals(CaptureGuarantee.INCOMPLETE, incomplete.profile.integrity.guarantee)
    }

    @Test
    fun `runtime can finish from a prevalidated artifact without reopening its path`() {
        val source = RuntimeSource()
        val session = TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { error("prevalidated artifact must not reopen its path") },
            configuredSessionEpoch = SessionEpoch(1),
        )
        val runtime = TierECollectorRuntime(session)

        runtime.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))

        val result = runtime.finish(
            AgentDefinitionFile.Contents(
                emptyList(),
                emptyList(),
                0,
                publication = SnapshotPublication(1, 0, closed = true, sessionEpoch = SessionEpoch(1)),
            ),
            TierEProducerClosure.PROVEN,
        )

        assertEquals(CaptureGuarantee.FULL_STREAM, result.profile.integrity.guarantee)
    }

    private fun session(source: RuntimeSource) =
        TierEProfilerSession(
            source = source,
            processEpoch = 1,
            definitionReader = { AgentDefinitionFile.Contents(emptyList(), emptyList(), 0) },
        )
}

private class RuntimeSource(
    private val retire: () -> Unit = {},
    private val drain: () -> Unit = {},
) : TierEEventSource {
    val drainStarted = CountDownLatch(1)
    val operations = mutableListOf<String>()
    var closeCalls = 0
    var counterFailure: Throwable? = null
    var closeFailure: Throwable? = null

    override fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    ) = Unit

    override fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) = Unit

    override fun detachMarker() {
        synchronized(operations) { operations += "detach" }
    }

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = true

    override fun drainEvents(): List<SyscallInvocationEvent> {
        drain()
        drainStarted.countDown()
        synchronized(operations) { operations += "drain" }
        return emptyList()
    }

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long {
        counterFailure?.let { throw it }
        return 0
    }

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
        synchronized(operations) { operations += "close" }
        retire()
        closeFailure?.let {
            closeFailure = null
            throw it
        }
    }
}
