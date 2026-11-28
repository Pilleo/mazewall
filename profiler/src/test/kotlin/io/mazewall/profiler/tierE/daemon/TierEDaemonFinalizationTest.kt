package io.mazewall.profiler.tierE.daemon

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.internal.attribution.SnapshotEnvelope
import io.mazewall.profiler.internal.attribution.TierEEventSource
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEDaemonFinalizationTest {
    @Test
    fun `shutdown during engine factory owns late engine without constructing a session`() {
        lateinit var daemon: TierEDaemonService
        val stopped = AtomicBoolean(false)
        var sessions = 0
        var pending: Throwable? = null
        daemon = TierEDaemonService(
            stopRequested = stopped,
            engineFactory = {
                assertEquals("ERR STATE", daemon.executeCommand(ATTACH))
                pending = runCatching { daemon.shutdown() }.exceptionOrNull()
                TierEbpfEngine(MockNativeEngine())
            },
            sessionFactory = { _, _, _, _ ->
                sessions++
                error("terminal acquisition must not continue")
            },
        )
        assertEquals("ERR STATE", daemon.executeCommand(ATTACH))
        assertTrue(pending is IllegalStateException)
        assertEquals(0, sessions)
        assertTrue(stopped.get())
        val original = assertFailsWith<IllegalStateException> { daemon.shutdown() }
        assertSame(original, assertFailsWith<IllegalStateException> { daemon.shutdown() })
    }

    @Test
    fun `shutdown during session construction defers retirement and retains late owner for retry`() {
        val source = FinalizationSource()
        lateinit var daemon: TierEDaemonService
        val stopped = AtomicBoolean(false)
        var pending: Throwable? = null
        var nestedReply = ""
        daemon = TierEDaemonService(
            stopRequested = stopped,
            sessionFactory = { _, process, options, epoch ->
                pending = runCatching { daemon.shutdown() }.exceptionOrNull()
                nestedReply = if (pending != null) daemon.executeCommand(ATTACH) else "shutdown falsely succeeded"
                assertEquals(0, source.closeAttempts)
                TierEProfilerSession(source, process, options, configuredSessionEpoch = epoch)
            },
        )

        assertEquals("ERR STATE", daemon.executeCommand(ATTACH))
        assertTrue(pending is IllegalStateException, "in-flight shutdown must explicitly report incomplete retirement")
        assertEquals("ERR STATE", nestedReply)
        assertEquals(1, source.closeAttempts)
        assertTrue(!stopped.get())
        assertEquals("ERR STATE", daemon.executeCommand("STATUS"))
        val original = assertFailsWith<IllegalStateException> { daemon.shutdown() }
        assertSame(original, assertFailsWith<IllegalStateException> { daemon.shutdown() })
        assertEquals(2, source.closeAttempts)
        assertTrue(stopped.get())
        assertEquals(0, source.attachAttempts)
    }

    @Test
    fun `shutdown during marker attach never closes source still used by acquisition`() {
        val source = FinalizationSource()
        source.detachFailure = null
        source.closeFailure = null
        val fixture = fixture(source)
        var pending: Throwable? = null
        source.attachAction = {
            pending = runCatching { fixture.daemon.shutdown() }.exceptionOrNull()
            assertEquals(0, source.closeAttempts)
        }

        assertEquals("ERR STATE", fixture.daemon.executeCommand(ATTACH))
        assertTrue(pending is IllegalStateException)
        assertEquals(1, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
        assertTrue(fixture.stopped.get())
        runCatching { fixture.daemon.shutdown() }
        assertEquals(1, fixture.reports.size)
        assertEquals(1, source.closeAttempts)
    }

    @Test
    fun `cleanup failure cannot reopen terminal admission`() {
        val source = FinalizationSource()
        val fixture = fixture(source)
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        for (command in listOf(ATTACH, "DETACH", "STATUS", "SHUTDOWN", "", "nonsense")) {
            assertEquals("ERR STATE", fixture.daemon.executeCommand(command))
        }
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        assertEquals(listOf(source.cleanupFailure), source.finishFailure.suppressed.toList())
        assertEquals(2, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
    }

    @Test
    fun `direct shutdown rejects attach already queued on service monitor`() {
        var engines = 0
        var sessions = 0
        val daemon = TierEDaemonService(
            stopRequested = AtomicBoolean(false),
            engineFactory = {
                engines++
                error("engine must not be created")
            },
            sessionFactory = { _, _, _, _ ->
                sessions++
                error("session must not be created")
            },
        )
        val entering = CountDownLatch(1)
        val task = java.util.concurrent.FutureTask {
            entering.countDown()
            daemon.executeCommand(ATTACH)
        }
        val worker = Thread(task)
        synchronized(daemon) {
            worker.start()
            assertTrue(entering.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(Thread.State.BLOCKED, worker.state)
            daemon.shutdown()
        }
        assertTrue(task.get(1, TimeUnit.SECONDS).startsWith("ERR"))
        assertEquals(0, engines)
        assertEquals(0, sessions)
    }

    @Test
    fun `blocked real poll leaves detach status and replacement rejection bounded without closing live source`() {
        val source = FinalizationSource()
        source.detachFailure = null
        source.closeFailure = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        source.drainAction = {
            entered.countDown()
            var waiting = true
            while (waiting) {
                try {
                    release.await()
                    waiting = false
                } catch (_: InterruptedException) {
                    // Keep the real loop/session poll monitors occupied until explicit release.
                }
            }
        }
        val fixture = fixture(source)

        fun command(line: String): String {
            val pending = java.util.concurrent.FutureTask { fixture.daemon.executeCommand(line) }
            Thread(pending).start()
            return pending.get(4, TimeUnit.SECONDS)
        }
        try {
            assertTrue(command(ATTACH).startsWith("OK"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(command("DETACH").startsWith("ERR"))
            assertEquals(0, source.closeAttempts)
            assertTrue(command("STATUS").contains("FINALIZATION_FAILED"))
            assertTrue(command(ATTACH).startsWith("ERR"))
            assertEquals(1, fixture.reports.size)
        } finally {
            release.countDown()
            runCatching { fixture.daemon.shutdown() }
        }
        assertEquals(1, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
    }

    @TempDir
    lateinit var root: Path

    @Test
    fun `existing durable event destination rejects detach preserves original failure and reports once`() {
        writeClosedArtifact()
        val source = FinalizationSource().apply {
            detachFailure = null
            closeFailure = null
            quiescent = true
        }
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        val existing = Files.writeString(root.resolve("orders-19802.events.jsonl"), "existing evidence")
        val reports = mutableListOf<TierETerminalReport>()
        var publicationFailure: Throwable? = null
        var eventAttempts = 0
        val fixture = fixture(
            source,
            allTargetCapture = true,
            sink = TierETerminalReportSink {
            reports.add(it)
            TierETerminalReportFileSink(root).accept(it)
        },
            resultSink = TierEDaemonResultSink {
            eventAttempts++
            assertEquals(io.mazewall.profiler.internal.attribution.TierETerminalReason.COMPLETE, it.terminalReason)
            assertEquals(EvidenceIntegrity.COMPLETE, it.profile.integrity.evidenceIntegrity)
            try {
                TierEEventJsonlFileSink(root).publish(target, it.profile, it.terminalReason)
            } catch (failure: Throwable) {
                publicationFailure = failure
                throw failure
            }
        },
        )
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))
        val reply = fixture.daemon.executeCommand("DETACH")
        assertTrue(reply.startsWith("ERR"), reply)
        assertTrue(fixture.daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))
        assertSame(publicationFailure, assertFailsWith<IllegalArgumentException> { fixture.daemon.shutdown() })
        assertSame(publicationFailure, assertFailsWith<IllegalArgumentException> { fixture.daemon.shutdown() })
        assertEquals("existing evidence", Files.readString(existing))
        assertEquals(1, eventAttempts)
        assertEquals(1, source.closeAttempts)
        assertEquals(1, reports.size)
        assertEquals("FINALIZATION_FAILED", reports.single().terminalReason)
        assertEquals(EvidenceIntegrity.INCOMPLETE, reports.single().evidenceIntegrity)
        assertEquals(false, reports.single().transportCompleteness)
        assertTrue(Files.readString(root.resolve("orders-19802.tier-e.json")).contains("\"terminal_reason\":\"FINALIZATION_FAILED\""))
    }

    @Test
    fun `fatal real event publication preserves identity through shutdown and still writes incomplete terminal`() {
        writeClosedArtifact()
        val source = FinalizationSource().apply {
            detachFailure = null
            closeFailure = null
            quiescent = true
        }
        val fatal = LinkageError("fatal event publication")
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        val reports = mutableListOf<TierETerminalReport>()
        val events = TierEEventJsonlFileSink(root, publishTemporary = { _, _ -> throw fatal })
        val fixture = fixture(
            source,
            allTargetCapture = true,
            sink = TierETerminalReportSink {
            reports.add(it)
            TierETerminalReportFileSink(root).accept(it)
        },
            resultSink = TierEDaemonResultSink { events.publish(target, it.profile, it.terminalReason) },
        )
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.executeCommand("SHUTDOWN") })
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertEquals(1, source.closeAttempts)
        assertTrue(fixture.stopped.get())
        assertEquals(1, reports.size)
        assertEquals("FINALIZATION_FAILED", reports.single().terminalReason)
        assertEquals(EvidenceIntegrity.INCOMPLETE, reports.single().evidenceIntegrity)
        assertTrue(!Files.exists(root.resolve("orders-19802.events.jsonl")))
        assertTrue(Files.readString(root.resolve("orders-19802.tier-e.json")).contains("\"transport_completeness\":false"))
    }

    @Test
    fun `both durable sink failures retain fatal first order and retired resources without retry`() {
        writeClosedArtifact()
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        val failures = listOf(
            java.io.IOException("event") to java.io.IOException("terminal"),
            LinkageError("fatal event") to java.io.IOException("terminal"),
            java.io.IOException("event") to LinkageError("fatal terminal"),
        )
        for ((index, pair) in failures.withIndex()) {
            val (eventFailure, terminalFailure) = pair
            val directory = Files.createDirectory(root.resolve("sink-failures-$index"))
            val existing = Files.writeString(directory.resolve("orders-19802.tier-e.json"), "prior terminal")
            val source = FinalizationSource().apply {
                detachFailure = null
                closeFailure = null
                quiescent = true
            }
            var eventAttempts = 0
            var terminalAttempts = 0
            val events = TierEEventJsonlFileSink(directory, publishTemporary = { _, _ ->
                eventAttempts++
                throw eventFailure
            })
            val terminal = TierETerminalReportFileSink(directory, replaceTemporary = { _, _ ->
                terminalAttempts++
                throw terminalFailure
            })
            val fixture = fixture(
                source,
                allTargetCapture = true,
                sink = TierETerminalReportSink(terminal::accept),
                resultSink = TierEDaemonResultSink { events.publish(target, it.profile, it.terminalReason) },
            )
            assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))
            val primary = if (terminalFailure is Error) terminalFailure else eventFailure
            val secondary = if (primary === eventFailure) terminalFailure else eventFailure
            if (primary is Error) {
                assertSame(primary, assertFailsWith<Error> { fixture.daemon.executeCommand("SHUTDOWN") })
            } else {
                assertTrue(fixture.daemon.executeCommand("SHUTDOWN").startsWith("ERR"))
            }
            assertSame(primary, assertFailsWith<Throwable> { fixture.daemon.shutdown() })
            assertSame(primary, assertFailsWith<Throwable> { fixture.daemon.shutdown() })
            assertEquals(listOf(secondary), primary.suppressed.toList())
            assertEquals(1, eventAttempts)
            assertEquals(1, terminalAttempts)
            assertEquals(1, source.closeAttempts)
            assertTrue(fixture.stopped.get())
            assertEquals("prior terminal", Files.readString(existing))
            assertTrue(!Files.exists(directory.resolve("orders-19802.events.jsonl")))
        }
    }

    private fun writeClosedArtifact() {
        Files.write(
            root.resolve("orders.mzse"),
            SnapshotEnvelope.encode(
            byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 2, 0),
            publicationId = 1,
                watermark = 0,
                closed = true,
                sessionEpoch = SessionEpoch(0x4d5a),
        ),
        )
    }

    @Test
    fun `terminal publication failure returns error after successful retirement and is not retried`() {
        val source = FinalizationSource()
        source.detachFailure = null
        source.closeFailure = null
        val failure = IllegalStateException("terminal publication failed")
        var attempts = 0
        val fixture = fixture(
            source,
            sink = TierETerminalReportSink {
            attempts++
            throw failure
        },
        )
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))

        val reply = fixture.daemon.executeCommand("DETACH")

        assertTrue(reply.startsWith("ERR"), reply)
        assertTrue(fixture.daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))
        assertSame(failure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        assertEquals(1, attempts)
        assertEquals(1, source.closeAttempts)
    }

    @Test
    fun `fatal local acquisition failure still attempts independent cleanup and retains the owner`() {
        val source = FinalizationSource()
        val fatal = LinkageError("fatal attach failure")
        source.attachFailure = fatal
        val fixture = fixture(source)

        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.executeCommand(ATTACH) })
        assertEquals(1, source.closeAttempts)
        assertEquals(listOf(source.cleanupFailure), fatal.suppressed.toList())
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertEquals(2, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
    }

    @Test
    fun `legacy finalization failure returns error without fabricating registered report`() {
        val source = FinalizationSource()
        val reports = mutableListOf<TierETerminalReport>()
        val daemon = TierEDaemonService(
            stopRequested = AtomicBoolean(false),
            artifactResolver = TierEDaemonArtifactResolver(root),
            terminalReportSink = TierETerminalReportSink(reports::add),
            sessionFactory = { _, process, options, epoch -> TierEProfilerSession(source, process, options, configuredSessionEpoch = epoch) },
        )
        assertTrue(daemon.executeCommand(ATTACH).startsWith("OK"))

        assertTrue(daemon.executeCommand("DETACH").startsWith("ERR"))
        assertTrue(daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))
        runCatching { daemon.shutdown() }
        assertTrue(reports.isEmpty())
        assertEquals(2, source.closeAttempts)
    }

    @Test
    fun `command shutdown returns error and retains cleanup owner until direct shutdown retry`() {
        val source = FinalizationSource()
        val fixture = fixture(source)
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))

        val reply = fixture.daemon.executeCommand("SHUTDOWN")

        assertTrue(reply.startsWith("ERR"), reply)
        assertTrue(!fixture.stopped.get())
        assertEquals("ERR STATE", fixture.daemon.executeCommand(ATTACH))
        assertEquals("ERR STATE", fixture.daemon.executeCommand("STATUS"))
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        assertTrue(fixture.stopped.get())
        assertEquals(2, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
    }

    @Test
    fun `direct shutdown preserves fatal cleanup identity and original finish evidence`() {
        val source = FinalizationSource()
        val fatal = LinkageError("fatal cleanup")
        source.closeFailure = fatal
        val fixture = fixture(source)
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))

        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })

        assertEquals(listOf(source.finishFailure), fatal.suppressed.toList())
        assertEquals(2, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
        assertEquals("FINALIZATION_FAILED", fixture.reports.single().terminalReason)
    }

    @Test
    fun `failed terminal report write remains observable and is never attempted again`() {
        val source = FinalizationSource()
        val writeFailure = IllegalStateException("evidence root write failed")
        var attempts = 0
        val fixture = fixture(
            source,
            sink = TierETerminalReportSink {
            attempts++
            throw writeFailure
        },
        )
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))

        assertTrue(fixture.daemon.executeCommand("DETACH").startsWith("ERR"))
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })

        assertEquals(1, attempts)
        assertEquals(listOf(source.cleanupFailure, writeFailure), source.finishFailure.suppressed.toList())
        val status = fixture.daemon.executeCommand("STATUS")
        assertEquals("ERR STATE", status)
    }

    @Test
    fun `failed local attachment remains owned and reports its registered failure once`() {
        val source = FinalizationSource()
        source.attachFailure = IllegalStateException("local attach failed")
        val fixture = fixture(source)

        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("ERR"))
        assertEquals(1, source.closeAttempts)
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("ERR"))
        runCatching { fixture.daemon.shutdown() }
        runCatching { fixture.daemon.shutdown() }

        assertEquals(2, source.closeAttempts)
        assertEquals(0, source.detachAttempts)
        assertEquals(1, fixture.reports.size)
        assertEquals(listOf(source.cleanupFailure), source.attachFailure!!.suppressed.toList())
    }

    @Test
    fun `failed attachment with successful cleanup still exposes failure in status`() {
        val source = FinalizationSource()
        source.attachFailure = IllegalStateException("local attach failed")
        source.closeFailure = null
        val fixture = fixture(source)

        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("ERR"))

        val status = fixture.daemon.executeCommand("STATUS")
        assertTrue(status.contains("FINALIZATION_FAILED"), status)
        assertEquals(1, source.closeAttempts)
        assertEquals(1, fixture.reports.size)
    }

    @Test
    fun `target exit finalization failure retains reachable owner for shutdown retry`() {
        val source = FinalizationSource()
        val incarnation = AtomicLong(9876)
        val reported = CountDownLatch(1)
        val fixture = fixture(source, incarnation, TierETerminalReportSink { reported.countDown() })
        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))

        incarnation.set(0)
        assertTrue(reported.await(2, TimeUnit.SECONDS))
        assertTrue(fixture.daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { fixture.daemon.shutdown() })
        assertEquals(2, source.closeAttempts)
        assertEquals(1, source.detachAttempts)
    }

    @Test
    fun `watcher observation failure retires collection and retains its cause`() {
        val source = FinalizationSource().apply {
            detachFailure = null
            closeFailure = null
        }
        val failObservation = AtomicBoolean(false)
        val denied = java.nio.file.AccessDeniedException("fixture stat")
        val reported = CountDownLatch(1)
        val fixture = fixture(source, sink = TierETerminalReportSink { reported.countDown() }, observe = {
            if (failObservation.get()) throw denied
            9876
        })

        assertTrue(fixture.daemon.executeCommand(ATTACH).startsWith("OK"))
        failObservation.set(true)

        assertTrue(reported.await(2, TimeUnit.SECONDS))
        assertTrue(fixture.daemon.executeCommand("STATUS").contains("FINALIZATION_FAILED"))
        assertSame(denied, assertFailsWith<java.nio.file.AccessDeniedException> { fixture.daemon.shutdown() })
        assertEquals(0, source.detachAttempts, "failed observation must bypass normal collection finalization")
        assertEquals(1, source.closeAttempts)
    }

    private fun fixture(
        source: FinalizationSource,
        incarnation: AtomicLong = AtomicLong(9876),
        sink: TierETerminalReportSink? = null,
        resultSink: TierEDaemonResultSink = TierEDaemonResultSink { },
        allTargetCapture: Boolean = false,
        observe: (Int) -> Long? = { incarnation.get().takeUnless { it == 0L } },
    ): Fixture {
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        val stopped = AtomicBoolean(false)
        val reports = mutableListOf<TierETerminalReport>()
        val verifier = TierEProcessIdentityVerifier(observe)
        val daemon = TierEDaemonService(
            stopRequested = stopped,
            artifactResolver = TierEDaemonArtifactResolver(root),
            registeredTargetResolver = TierETargetRegistrationResolver(mapOf("orders" to target)),
            processIdentityVerifier = verifier,
            targetExitCoordinator = TierETargetExitCoordinator(verifier, 1),
            terminalArtifactWaitMillis = 0,
            terminalReportSink = sink ?: TierETerminalReportSink(reports::add),
            resultSink = resultSink,
            allTargetCapture = allTargetCapture,
            sessionFactory = { _, process, options, epoch ->
                TierEProfilerSession(source, process, options, configuredSessionEpoch = epoch)
            },
        )
        return Fixture(daemon, stopped, reports)
    }

    private data class Fixture(
        val daemon: TierEDaemonService,
        val stopped: AtomicBoolean,
        val reports: List<TierETerminalReport>,
    )

    private companion object {
        const val ATTACH = "ATTACH 4242 uprobe /agent.so 20 4d5a"
    }

    @Test
    fun `failed finalization keeps closing owner blocks replacement and retries only retirement`() {
        val source = FinalizationSource()
        val reports = mutableListOf<TierETerminalReport>()
        var sessions = 0
        var profiles = 0
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        val daemon = TierEDaemonService(
            stopRequested = AtomicBoolean(false),
            artifactResolver = TierEDaemonArtifactResolver(root),
            registeredTargetResolver = TierETargetRegistrationResolver(mapOf("orders" to target)),
            processIdentityVerifier = TierEProcessIdentityVerifier { 9876 },
            terminalReportSink = TierETerminalReportSink {
                reports.add(it)
                TierETerminalReportFileSink(root).accept(it)
            },
            resultSink = TierEDaemonResultSink {
                profiles++
                TierEEventJsonlFileSink(root).publish(target, it.profile, it.terminalReason)
            },
            sessionFactory = { _, incarnation, options, epoch ->
                sessions++
                TierEProfilerSession(source, incarnation, options, configuredSessionEpoch = epoch)
            },
        )
        val attach = "ATTACH 4242 uprobe /agent.so 20 4d5a"
        assertTrue(daemon.executeCommand(attach).startsWith("OK"))

        val reply = daemon.executeCommand("DETACH")

        assertTrue(reply.startsWith("ERR"), reply)
        assertEquals(1, source.closeAttempts)
        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals("FINALIZATION_FAILED", report.terminalReason)
        assertEquals(EvidenceIntegrity.INCOMPLETE, report.evidenceIntegrity)
        assertEquals("orders", report.registrationId)
        assertEquals(0x4d5aL, report.sessionEpoch)
        assertEquals(4242, report.targetPid)
        assertEquals(9876L, report.processIncarnation)
        assertEquals(0, report.resolvedSyscalls)
        assertTrue(
            listOf(
                report.captureDomainValidity,
                report.transportCompleteness,
                report.attributionCoverage,
            report.dictionaryIntegrity,
                report.lifecycleValidity,
                report.policyEligibility,
            ).none { it },
        )
        val status = daemon.executeCommand("STATUS")
        assertTrue(status.contains("FINALIZATION_FAILED"), status)
        assertTrue(daemon.executeCommand(attach).startsWith("ERR"))
        assertEquals(1, sessions)

        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { daemon.shutdown() })
        assertSame(source.finishFailure, assertFailsWith<IllegalStateException> { daemon.shutdown() })

        assertEquals(2, source.closeAttempts)
        assertEquals(1, source.detachAttempts)
        assertEquals(1, reports.size)
        assertEquals(0, profiles)
        assertTrue(!Files.exists(root.resolve("orders-19802.events.jsonl")))
        assertTrue(Files.readString(root.resolve("orders-19802.tier-e.json")).contains("\"terminal_reason\":\"FINALIZATION_FAILED\""))
        assertEquals(listOf(source.cleanupFailure), source.finishFailure.suppressed.toList())
    }
}

private class FinalizationSource : TierEEventSource {
    var attachAction: () -> Unit = {}
    var attachAttempts = 0
    var drainAction: () -> Unit = {}
    val finishFailure = IllegalStateException("marker detach failed")
    var detachFailure: Throwable? = finishFailure
    val cleanupFailure = IllegalStateException("ring unmap failed")
    var detachAttempts = 0
    var closeAttempts = 0
    var closeFailure: Throwable? = cleanupFailure
    var attachFailure: Throwable? = null
    var quiescent = false

    override fun enableCaptureAdmission() = Unit

    override fun disableCaptureAdmission() = Unit

    override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean = quiescent

    override fun install(
        targetPid: Int,
        sessionTag: Int,
        options: TierEOptions,
    ) = Unit

    override fun attachMarker(
        targetPid: Int,
        agentLibrary: Path,
        markerOffset: Long,
    ) {
        attachAttempts++
        attachAction()
        attachFailure?.let { throw it }
    }

    override fun drainEvents(): List<SyscallInvocationEvent> {
        drainAction()
        return emptyList()
    }

    override fun readLossCount(): Long = 0

    override fun readMarkerFailureCount(): Long = 0

    override fun readMarkerTransitionCount(): Long = 0

    override fun readLastMarkerPidTgid(): Long = 0

    override fun detachMarker() {
        detachAttempts++
        detachFailure?.let { throw it }
    }

    override fun close() {
        closeAttempts++
        if (closeAttempts == 1) closeFailure?.let { throw it }
    }
}
