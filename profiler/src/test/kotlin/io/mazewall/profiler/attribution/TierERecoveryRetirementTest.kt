package io.mazewall.profiler.internal.attribution

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.tierE.daemon.TierEDaemonArtifactResolver
import io.mazewall.profiler.tierE.daemon.TierEDaemonResultSink
import io.mazewall.profiler.tierE.daemon.TierEDaemonService
import io.mazewall.profiler.tierE.daemon.TierEProcessIdentityVerifier
import io.mazewall.profiler.tierE.daemon.TierERegisteredTarget
import io.mazewall.profiler.tierE.daemon.TierETargetRegistrationResolver
import io.mazewall.profiler.tierE.daemon.TierETerminalReport
import io.mazewall.profiler.tierE.daemon.TierETerminalReportSink
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierERecoveryRetirementTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `daemon retains recovery memory and once only failure across timeout and shutdown retry`() {
        val source = RecoveryRetirementSource()
        val target = TierERegisteredTarget(
            "orders",
            4242,
            TAG,
            9876,
            Path.of("/agent.so"),
            0x20,
            "orders.mzse",
            markerProofFileName = "orders.marker-proof",
        )
        Files.writeString(root.resolve("orders.marker-proof"), "version=1\nsession_tag=4d5a\nprocess_starttime=9876\nmarker_version=v2\n")
        val reports = mutableListOf<TierETerminalReport>()
        var profiles = 0
        val daemon = TierEDaemonService(
            stopRequested = AtomicBoolean(false),
            artifactResolver = TierEDaemonArtifactResolver(root),
            registeredTargetResolver = TierETargetRegistrationResolver(mapOf("orders" to target)),
            processIdentityVerifier = TierEProcessIdentityVerifier { 9876L },
            terminalReportSink = TierETerminalReportSink(reports::add),
            resultSink = TierEDaemonResultSink { profiles++ },
            benchmarkMode = true,
            markerVersion = MarkerVersion.V2,
            recoveryMode = CoverageRecoveryMode.SHADOW,
            sessionFactory = { _, process, options, epoch -> TierEProfilerSession(source, process, options, configuredSessionEpoch = epoch) },
        )
        val attach = "ATTACH 4242 uprobe /agent.so 20 4d5a"
        try {
            assertTrue(daemon.executeCommand(attach).startsWith("OK"))
            assertTrue(source.entered.await(1, TimeUnit.SECONDS))

            assertTrue(daemon.executeCommand("DETACH").startsWith("ERR"))
            assertTrue(source.memoryAlive())
            assertEquals(0, source.closeCalls)
            assertEquals(1, reports.size)
            assertEquals("FINALIZATION_FAILED", reports.single().terminalReason)
            assertEquals("orders", reports.single().registrationId)
            assertEquals(0, profiles)
            assertTrue(daemon.executeCommand(attach).startsWith("ERR"))
            assertTrue(daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))

            source.release.countDown()
            val failure = assertFailsWith<IllegalStateException> { daemon.shutdown() }
            assertSame(failure, assertFailsWith<IllegalStateException> { daemon.shutdown() })
            assertTrue(source.readCompleted)
            assertEquals(1, source.closeCalls)
            assertFalse(source.memoryAlive())
            assertEquals(1, reports.size)
            assertEquals(0, profiles)
        } finally {
            source.release.countDown()
            runCatching { daemon.shutdown() }
        }
    }

    @Test
    fun `close timeout retains live reader and its source memory until retry joins`() {
        val source = RecoveryRetirementSource()
        val session = session(source)
        try {
            attach(session)
            assertTrue(source.entered.await(1, TimeUnit.SECONDS))

            assertFailsWith<IllegalStateException> { session.close() }
            assertEquals(0, source.closeCalls)
            assertTrue(source.memoryAlive())
            assertFailsWith<IllegalStateException> { session.close() }
            assertEquals(0, source.closeCalls)

            source.release.countDown()
            session.close()
            session.close()
            assertTrue(source.readCompleted)
            assertFalse(source.memoryAlive())
            assertEquals(1, source.closeCalls)
            assertEquals(1, source.scans.get())
        } finally {
            source.release.countDown()
            session.close()
        }
    }

    @Test
    fun `final scan timeout keeps ownership and freezes original failure through runtime retry`() {
        val source = RecoveryRetirementSource()
        val session = session(source)
        val runtime = TierECollectorRuntime(session)
        val artifact = TierEDefinitionArtifact(Path.of("ignored"), SessionEpoch(TAG.toLong()))
        try {
            attach(session)
            assertTrue(source.entered.await(1, TimeUnit.SECONDS))
            runtime.start()

            val failure = assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) }
            assertEquals(0, source.closeCalls)
            assertTrue(source.memoryAlive())
            assertFailsWith<IllegalStateException> { runtime.close() }
            assertEquals(0, source.closeCalls)

            source.release.countDown()
            runtime.close()
            runtime.close()
            assertSame(failure, assertFailsWith<IllegalStateException> { runtime.finish(artifact, TierEProducerClosure.PROVEN) })
            assertTrue(source.readCompleted)
            assertEquals(1, source.closeCalls)
            assertEquals(1, source.scans.get(), "no final scan may overlap a live reader or run on cleanup retry")
        } finally {
            source.release.countDown()
            runtime.close()
        }
    }

    @Test
    fun `interrupted reader join preserves source memory until a later close`() {
        val source = RecoveryRetirementSource()
        val session = session(source)
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val closer = Thread {
            try {
                session.close()
            } catch (cause: Throwable) {
                failure.set(cause)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            attach(session)
            assertTrue(source.entered.await(1, TimeUnit.SECONDS))
            closer.start()
            assertTrue(source.stopObserved.await(1, TimeUnit.SECONDS))
            closer.interrupt()
            closer.join(2_000)

            assertFalse(closer.isAlive)
            assertTrue(failure.get() is InterruptedException)
            assertTrue(interrupted.get())
            assertTrue(source.memoryAlive())
            assertEquals(0, source.closeCalls)
        } finally {
            source.release.countDown()
            closer.join(2_000)
            session.close()
        }
        assertTrue(source.readCompleted)
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun `terminated failed reader retires without losing incomplete recovery diagnostics`() {
        val source = RecoveryRetirementSource(failScan = true)
        val session = session(source)
        try {
            attach(session)
            assertTrue(source.entered.await(1, TimeUnit.SECONDS))
            val profile = session.finish(Path.of("ignored"), producerClosed = true)

            assertEquals("INCOMPLETE", profile.integrity.recoveryEvidence.recoveryVerdict)
            assertEquals(1, source.closeCalls)
            assertFalse(source.memoryAlive())
            assertEquals(1, source.scans.get(), "failed live scan must not become a successful exclusive scan")
            session.close()
            session.close()
            assertEquals(1, source.closeCalls)
        } finally {
            source.release.countDown()
            session.close()
        }
    }

    private fun session(source: RecoveryRetirementSource) =
        TierEProfilerSession(
            source,
            processEpoch = 1,
            options = TierEOptions(
                experimentalCoverage = true,
                markerVersion = MarkerVersion.V2,
                coverageRecoveryMode = CoverageRecoveryMode.SHADOW,
            ),
            configuredSessionEpoch = SessionEpoch(TAG.toLong()),
            definitionReader = { AgentDefinitionFile.Contents(emptyList(), emptyList(), 0) },
        )

    private fun attach(session: TierEProfilerSession) = session.installAndAttach(4242, TAG, Path.of("/agent.so"), 0x20)

    private companion object {
        const val TAG = 0x4d5a
    }
}

private class RecoveryRetirementSource(
    private val failScan: Boolean = false,
) : TierEEventSource {
    private val arena = Arena.ofShared()
    private val memory = arena.allocate(ValueLayout.JAVA_LONG).apply { set(ValueLayout.JAVA_LONG, 0, 42L) }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stopObserved = CountDownLatch(1)
    val scans = AtomicInteger()
    var closeCalls = 0
        private set

    @Volatile
    var readCompleted = false
        private set

    fun memoryAlive(): Boolean = arena.scope().isAlive

    override fun recoveryReader() =
        RecoveryMapReader { _, _ ->
            scans.incrementAndGet()
            entered.countDown()
            if (failScan) error("live map scan failed")
            var waiting = true
            while (waiting) {
                try {
                    release.await()
                    waiting = false
                } catch (_: InterruptedException) {
                    // A blocked native read need not return when the Java thread is interrupted.
                    stopObserved.countDown()
                }
            }
            check(memory.get(ValueLayout.JAVA_LONG, 0) == 42L)
            readCompleted = true
            RecoveryScanPage(emptyList(), null, true)
        }

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

    override fun drainEvents(): List<SyscallInvocationEvent> = emptyList()

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = true

    override fun close() {
        closeCalls++
        arena.close()
    }
}
