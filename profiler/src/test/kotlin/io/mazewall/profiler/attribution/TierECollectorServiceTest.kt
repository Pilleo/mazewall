package io.mazewall.profiler.internal.attribution

import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TierECollectorServiceTest {
    @Test
    fun `service starts once and caches one terminal profile`() {
        val source = RecordingSource()
        val service = service(source)

        service.start()
        assertFailsWith<IllegalStateException> { service.start() }

        val profile = service.finish(Path.of("ignored"), producerClosed = true)

        assertSame(profile, service.finish(Path.of("never-read"), producerClosed = true))
        assertEquals(TierECollectorServiceState.TERMINAL, service.metrics().state)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `service never treats an empty collector as producer closure`() {
        val service = service(RecordingSource())

        service.start()
        val profile = service.finish(Path.of("ignored"), producerClosed = false)

        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
        assertTrue(profile.integrity.lifecycleInvalidations > 0)
    }

    @Test
    fun `collection failure forces an incomplete terminal profile`() {
        val source = RecordingSource(drainFailure = IllegalStateException("drain failed"))
        val service = service(source)

        service.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        val profile = service.finish(Path.of("ignored"), producerClosed = true)

        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
        assertEquals(TierECollectorServiceFailure.COLLECTION, service.metrics().failure)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `delivery failure forces an incomplete terminal profile`() {
        val callbackEntered = CountDownLatch(1)
        val source = RecordingSource(events = ArrayDeque(listOf(listOf(event()))))
        val service = service(source) {
            callbackEntered.countDown()
            error("delivery failed")
        }

        service.start()
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
        val profile = service.finish(Path.of("ignored"), producerClosed = true)

        assertEquals(CaptureGuarantee.INCOMPLETE, profile.integrity.guarantee)
        assertEquals(TierECollectorServiceFailure.DELIVERY, service.metrics().failure)
    }

    @Test
    fun `close before start closes the source exactly once`() {
        val source = RecordingSource()
        val service = service(source)

        service.close()
        service.close()

        assertEquals(TierECollectorServiceState.TERMINAL, service.metrics().state)
        assertEquals(1, source.closeCalls)
        assertFailsWith<IllegalStateException> { service.start() }
    }

    @Test
    fun `close while collecting joins the worker before releasing the source`() {
        val source = RecordingSource()
        val service = service(source)

        service.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        service.close()

        assertEquals(TierECollectorServiceState.TERMINAL, service.metrics().state)
        assertFalse(service.metrics().workerAlive)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `finish and close serialize one terminal ownership release`() {
        val source = RecordingSource()
        val service = service(source)
        val begin = CountDownLatch(1)
        val finished = CountDownLatch(2)

        service.start()
        assertTrue(source.drainStarted.await(1, TimeUnit.SECONDS))
        val finishing = Thread {
            begin.await()
            runCatching { service.finish(Path.of("ignored"), producerClosed = true) }
            finished.countDown()
        }
        val closing = Thread {
            begin.await()
            service.close()
            finished.countDown()
        }
        finishing.start()
        closing.start()
        begin.countDown()

        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(TierECollectorServiceState.TERMINAL, service.metrics().state)
        assertEquals(1, source.closeCalls)
    }

    private fun service(
        source: RecordingSource,
        onResolved: (List<ResolvedSyscall>) -> Unit = {},
    ): TierECollectorService =
        TierECollectorService(
            session = TierEProfilerSession(
                source = source,
                processEpoch = 1,
                definitionReader = { AgentDefinitionFile.Contents(emptyList(), emptyList(), 0) },
            ),
            onResolved = onResolved,
            stopJoinMillis = 1_000,
        )

    private fun event() =
        SyscallInvocationEvent(
            ktimeNs = 1u,
            tgid = 1u,
            tid = 2u,
            syscallNr = 257,
            flags = 0,
            invocationId = 0u,
            sequence = 1u,
            args = List(6) { 0u },
        )
}

private class RecordingSource(
    private val events: ArrayDeque<List<SyscallInvocationEvent>> = ArrayDeque(),
    private val drainFailure: RuntimeException? = null,
) : TierEEventSource {
    val drainStarted = CountDownLatch(1)
    var closeCalls = 0

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

    override fun drainEvents(): List<SyscallInvocationEvent> {
        drainStarted.countDown()
        drainFailure?.let { throw it }
        return if (events.isEmpty()) emptyList() else events.removeFirst()
    }

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun close() {
        closeCalls++
    }
}
