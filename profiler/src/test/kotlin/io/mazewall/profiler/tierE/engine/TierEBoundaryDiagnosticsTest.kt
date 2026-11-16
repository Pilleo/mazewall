package io.mazewall.profiler.tierE.engine

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.LinuxNative.SyscallHandledState
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.internal.attribution.TierEEventSource
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.tierE.daemon.TierEClientSession
import io.mazewall.profiler.tierE.daemon.TierEDaemonArtifactResolver
import io.mazewall.profiler.tierE.daemon.TierEDaemonService
import io.mazewall.profiler.tierE.daemon.TierEProcessIdentityVerifier
import io.mazewall.profiler.tierE.daemon.TierERegisteredTarget
import io.mazewall.profiler.tierE.daemon.TierETargetRegistrationResolver
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PrintStream
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
class TierEBoundaryDiagnosticsTest {
    @Test
    fun initializationRejectsEveryNonzeroControlByteBeforeProgramsAndRetiresOwnedFds() {
        for (offset in 0 until 128) {
            val native = BoundaryNative()
            native.control[offset] = 1
            val engine = boundaryEngine(native)
            assertFailsWith<IllegalArgumentException> { engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true)) }
            engine.close()
            assertEquals(1, native.reads)
            assertFalse(native.commands.any { it == 5L || it == 17L }, "program load/attachment preceded initialization proof")
            assertEquals(native.acquired, native.closed)
        }
    }

    @Test
    fun initializationReadFailurePreservesOriginalAndClosesBothMaps() {
        val native = BoundaryNative()
        val failure = IOException("initial control read failed")
        native.readFailure = failure
        val engine = boundaryEngine(native)
        assertSame(failure, assertFailsWith<IOException> { engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true)) })
        assertEquals(2, native.acquired.size)
        assertEquals(native.acquired, native.closed)
        assertFalse(native.commands.any { it == 5L || it == 17L })
    }

    @Test
    fun statusUsesExactAdoptedEngineOnceAndKeepsHealthyWireReplyUnchanged() {
        val off = daemonFixture(enabled = false)
        val on = daemonFixture()
        try {
            assertEquals("OK engine=idle", clientStatus(on.daemon))
            assertEquals(0, on.factories)
            assertEquals(0, on.reporters)
            assertEquals(0, on.native.reads)
            assertTrue(off.daemon.executeCommand(attach).startsWith("OK"))
            assertTrue(on.daemon.executeCommand(attach).startsWith("OK"))
            assertEquals(1, on.native.reads, "initial proof must precede attachment")
            setPollingControl(on.native)
            val before = on.native.control.copyOf()
            val reply = clientStatus(on.daemon)
            assertEquals(clientStatus(off.daemon).replace(Regex("\\d+"), "#"), reply.replace(Regex("\\d+"), "#"))
            assertEquals(2, on.native.reads)
            assertEquals(1, on.factories)
            assertEquals(1, on.reporters)
            assertEquals(0, off.reporters)
            assertEquals(0, off.native.reads)
            val receipt = receipt(on.lines.single())
            assertEquals("1", receipt["installation"])
            assertEquals("1", receipt["ordinal"])
            assertEquals("4242", receipt["target"])
            assertEquals("19802", receipt["epoch"])
            assertEquals("1", receipt["initialized"])
            assertEquals("12", receipt["attempted"])
            assertEquals("3", receipt["main_in_flight"])
            assertEquals("2", receipt["marker_in_flight"])
            assertTrue(before.contentEquals(on.native.control), "STATUS must not reset polling rows/control")
            ByteBuffer
                .wrap(on.native.control)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0, 1)
                .putLong(8, 0)
                .putLong(16, 3)
                .putLong(24, 4)
            assertTrue(clientStatus(on.daemon).startsWith("OK"))
            assertEquals("2", receipt(on.lines.last())["ordinal"])
            assertEquals("0", receipt(on.lines.last())["reserved"])
            assertEquals("4", receipt(on.lines.last())["terminals"])
            assertEquals(3, on.native.reads)
        } finally {
            off.daemon.shutdown()
            on.daemon.shutdown()
        }
    }

    @Test
    fun statusReadAndPublicationFailuresLatchWithoutSkippingTerminalEffects() {
        for (publication in listOf(false, true)) {
            val failure = IOException(if (publication) "publication failed" else "live read failed")
            val fixture = daemonFixture(publishFailure = if (publication) failure else null)
            assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
            if (!publication) fixture.native.readFailure = failure
            assertTrue(clientStatus(fixture.daemon).startsWith("ERR"))
            val reads = fixture.native.reads
            assertTrue(clientStatus(fixture.daemon).startsWith("ERR"))
            assertEquals(reads, fixture.native.reads, "latched failure must not retry observation")
            fixture.native.readFailure = null
            assertSame(failure, assertFailsWith<IOException> { fixture.daemon.shutdown() })
            assertSame(failure, assertFailsWith<IOException> { fixture.daemon.shutdown() })
            assertEquals(listOf("disable", "detach", "quiesce", "export:false:true", "close"), fixture.effects)
            assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
            assertEquals(if (publication) 1 else 0, fixture.lines.size)
        }
    }

    @Test
    fun readinessFailureFoldsTerminalAndFatalCleanupErrorsWithoutSkippingEither() {
        val fixture = daemonFixture()
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
        val original = IOException("live read")
        val terminal = IOException("terminal export")
        val fatal = LinkageError("fatal cleanup")
        fixture.native.readFailure = original
        assertTrue(clientStatus(fixture.daemon).startsWith("ERR"))
        fixture.native.readFailure = null
        fixture.source.exportFailure = terminal
        fixture.source.closeFailure = fatal
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertTrue(fatal.suppressed.any { it === original })
        assertTrue(original.suppressed.any { it === terminal })
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertEquals(listOf("disable", "detach", "quiesce", "export:false:true", "close", "close"), fixture.effects)
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    @Test
    fun fatalObservationEscapesActualClientBoundaryAndRemainsOwnedForShutdown() {
        val fixture = daemonFixture()
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
        val fatal = LinkageError("fatal control read")
        fixture.native.readFailure = fatal
        assertSame(fatal, assertFailsWith<LinkageError> { clientStatus(fixture.daemon) })
        fixture.native.readFailure = null
        assertSame(fatal, assertFailsWith<LinkageError> { fixture.daemon.shutdown() })
        assertTrue(fixture.effects.contains("export:false:true"))
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    @Test
    fun reattachmentNeverReusesInstallationIdentityEvenForSameTargetAndEpoch() {
        val fixture = daemonFixture()
        repeat(2) { index ->
            assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
            assertTrue(clientStatus(fixture.daemon).startsWith("OK"))
            assertEquals((index + 1).toString(), receipt(fixture.lines.last())["installation"])
            assertEquals("1", receipt(fixture.lines.last())["ordinal"])
            assertTrue(fixture.daemon.executeCommand("DETACH").startsWith("OK"))
        }
        fixture.daemon.shutdown()
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    @Test
    fun reporterConsumesOrdinalBeforeFailedPublication() {
        val native = BoundaryNative()
        val engine = boundaryEngine(native)
        val failure = IOException("publisher failed after receiving complete line")
        val lines = mutableListOf<String>()
        val reporter = engine.boundaryControlReporter(7) { line ->
            lines += line
            if (lines.size == 1) throw failure
        }
        try {
            engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
            assertSame(failure, assertFailsWith<IOException> { reporter() })
            reporter()
            assertEquals(listOf("1", "2"), lines.map { receipt(it)["ordinal"] })
            assertEquals(listOf("7", "7"), lines.map { receipt(it)["installation"] })
        } finally {
            engine.close()
        }
    }

    @Test
    fun checkedPrintStreamFailureIsObservableThroughStatusAndRetainedAtShutdown() {
        val output = PrintStream(object : java.io.OutputStream() {
            override fun write(value: Int) {
                throw IOException("stdout broken")
            }
        })
        val fixture = daemonFixture(publisher = { publishBoundaryControlReceipt(it, output) })
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
        assertTrue(clientStatus(fixture.daemon).startsWith("ERR"))
        assertTrue(output.checkError())
        assertFailsWith<IOException> { fixture.daemon.shutdown() }
        assertTrue(fixture.effects.contains("export:false:true"))
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    @Test
    fun readExcludesCloseButPublicationRunsAfterLifecycleProtectionIsReleased() {
        val native = BoundaryNative()
        val engine = boundaryEngine(native)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var close: FutureTask<Unit>
        val reporter = engine.boundaryControlReporter(1) { close.get(2, TimeUnit.SECONDS) }
        engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
        native.readAction = {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }
        val read = FutureTask { reporter() }
        val reader = Thread(read)
        reader.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            close = FutureTask { engine.close() }
            val closer = Thread(close)
            closer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
            assertEquals(Thread.State.BLOCKED, closer.state)
            assertTrue(native.closed.isEmpty())
            release.countDown()
            read.get(3, TimeUnit.SECONDS)
            close.get(3, TimeUnit.SECONDS)
            assertEquals(native.acquired.sorted(), native.closed.sorted())
            assertFailsWith<IllegalStateException> { reporter() }
            assertEquals(2, native.reads)
        } finally {
            release.countDown()
            reader.join(3000)
            engine.close()
        }
    }

    @Test
    fun invalidLiveObservationReturnsErrWithoutPublishingAndStillFinalizes() {
        for ((offset, value) in listOf(32 to 1L, 40 to 1L, 0 to 32768L, 8 to 32768L, 64 to 1L)) {
            val fixture = daemonFixture()
            assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
            ByteBuffer.wrap(fixture.native.control).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
            assertEquals("ERR BOUNDARY_DIAGNOSTICS_FAILED", clientStatus(fixture.daemon))
            assertTrue(fixture.lines.isEmpty())
            val original = assertFailsWith<IllegalArgumentException> { fixture.daemon.shutdown() }
            assertSame(original, assertFailsWith<IllegalArgumentException> { fixture.daemon.shutdown() })
            assertTrue(fixture.effects.contains("export:false:true"))
            assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
        }
    }

    @Test
    fun reporterFactoryFailureCannotLoseAdoptedEngineOrReuseInstallation() {
        val failure = IOException("reporter binding failed")
        val fixture = daemonFixture(reporterEffect = { engine, installation, publish ->
            if (installation == 1L) {
                engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
                throw failure
            }
            engine.boundaryControlReporter(installation, publish)
        })
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("ERR"))
        assertFalse(fixture.native.acquired.isEmpty())
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
        assertSame(failure, assertFailsWith<IOException> { fixture.daemon.shutdown() })
        // A separate daemon tests continued admission: direct shutdown above intentionally terminates this one.
        val next = daemonFixture(reporterEffect = { engine, installation, publish ->
            if (installation == 1L) throw failure
            engine.boundaryControlReporter(installation, publish)
        })
        assertTrue(next.daemon.executeCommand(attach).startsWith("ERR"))
        assertTrue(next.daemon.executeCommand(attach).startsWith("OK"))
        assertTrue(clientStatus(next.daemon).startsWith("OK"))
        assertEquals("2", receipt(next.lines.single())["installation"])
        next.daemon.shutdown()
        assertEquals(next.native.acquired.sorted(), next.native.closed.sorted())
    }

    @Test
    fun checkedFlushFailureMakesCompleteLookingReceiptUnusableWithoutOk() {
        val bytes = object : ByteArrayOutputStream() {
            var flushes = 0

            override fun flush() {
                flushes++
                throw IOException("flush failed")
            }
        }
        val output = PrintStream(bytes)
        val fixture = daemonFixture(publisher = { publishBoundaryControlReceipt(it, output) })
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
        assertEquals("ERR BOUNDARY_DIAGNOSTICS_FAILED", clientStatus(fixture.daemon))
        assertEquals("1", receipt(bytes.toString(Charsets.US_ASCII))["ordinal"])
        assertTrue(bytes.flushes > 0)
        val failure = assertFailsWith<IOException> { fixture.daemon.shutdown() }
        assertSame(failure, assertFailsWith<IOException> { fixture.daemon.shutdown() })
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    @Test
    fun installationAndOrdinalOverflowRejectWithoutWrappingOrPublishing() {
        val fixture = daemonFixture()
        val sequence = TierEDaemonService::class.java.getDeclaredField("boundaryInstallationSequence").apply { isAccessible = true }
        sequence.setLong(fixture.daemon, Long.MAX_VALUE)
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("ERR"))
        assertEquals(Long.MAX_VALUE, sequence.getLong(fixture.daemon))
        assertEquals(0, fixture.reporters)
        assertFailsWith<ArithmeticException> { fixture.daemon.shutdown() }

        val native = BoundaryNative()
        val engine = boundaryEngine(native)
        val lines = mutableListOf<String>()
        val reporter = engine.boundaryControlReporter(1, lines::add)
        try {
            engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
            val resources = TierEbpfEngine::class.java
                .getDeclaredField("resources")
                .apply { isAccessible = true }
                .get(engine)
            val ordinal = resources.javaClass.getDeclaredField("boundaryOrdinal").apply { isAccessible = true }
            ordinal.setLong(resources, Long.MAX_VALUE)
            assertFailsWith<ArithmeticException> { reporter() }
            assertEquals(Long.MAX_VALUE, ordinal.getLong(resources))
            assertTrue(lines.isEmpty())
        } finally {
            engine.close()
        }
        assertEquals(native.acquired.sorted(), native.closed.sorted())
    }

    @Test
    fun staleReporterCannotReadReinstalledResourcesAndNewOrdinalStartsAtOne() {
        val native = BoundaryNative()
        val engine = boundaryEngine(native)
        val lines = mutableListOf<String>()
        val old = engine.boundaryControlReporter(1, lines::add)
        engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
        old()
        engine.close()
        val current = engine.boundaryControlReporter(2, lines::add)
        try {
            engine.install(4242, 0x4d5a, TierEOptions(allTargetCapture = true))
            val before = native.reads
            assertFailsWith<IllegalStateException> { old() }
            assertEquals(before, native.reads)
            current()
            assertEquals("2", receipt(lines.last())["installation"])
            assertEquals("1", receipt(lines.last())["ordinal"])
        } finally {
            engine.close()
        }
        assertEquals(native.acquired.sorted(), native.closed.sorted())
    }

    @Test
    fun receiptIsBoundedAsciiWithAllFrozenKeysAndCheckedFlush() {
        val control = BoundaryDiagnosticControl(32767, 32767, 32768, 32768, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE)
        val line = boundaryControlReceipt(Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 3691072, control)
        val fields = receipt(line)
        assertEquals("6291456", fields["combined_storage_bound"])
        assertEquals(Long.MAX_VALUE.toString(), fields["installation"])
        assertEquals(Long.MAX_VALUE.toString(), fields["ordinal"])
        assertEquals(Long.MAX_VALUE.toString(), fields["main_in_flight"])
        val bytes = object : ByteArrayOutputStream() {
            var flushes = 0

            override fun flush() {
                flushes++
            }
        }
        publishBoundaryControlReceipt(line, PrintStream(bytes))
        assertEquals(line, bytes.toString(Charsets.US_ASCII))
        assertTrue(bytes.flushes > 0)
    }

    @Test
    fun verifierReservePreservesExactMapAllocationEdgeAndRejectsOneMoreByte() {
        assertEquals(6270528L, BoundaryLayout.INITIAL_STORAGE_BYTES)
        assertEquals(20928L, BoundaryLayout.MAP_EXCESS_BYTES)
        val records = "map_type: 2\nvalue_size: 112\nmax_entries: 32768\nmemlock: 3686976\n"
        val control = "map_type: 2\nvalue_size: 128\nmax_entries: 1\nmemlock: 4096\n"
        assertEquals(3691072L, boundaryAllocatedBytes(records, control))
        assertFailsWith<IllegalArgumentException> { boundaryAllocatedBytes(records.replace("3686976", "3686977"), control) }
        val zero = BoundaryDiagnosticControl(0, 0, 0, 0, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { boundaryControlReceipt(1, 1, 42, 19802, 3691073, zero) }
    }

    @Test
    fun retiredOrFailedCollectionCannotPublishAnotherUsableReadinessReceipt() {
        val fixture = daemonFixture()
        assertTrue(fixture.daemon.executeCommand(attach).startsWith("OK"))
        val terminal = IOException("terminal export failed")
        fixture.source.exportFailure = terminal
        fixture.source.closeFailure = IOException("retirement pending")
        assertTrue(fixture.daemon.executeCommand("DETACH").startsWith("ERR"))
        val before = fixture.native.reads
        assertTrue(clientStatus(fixture.daemon).startsWith("ERR"))
        assertEquals(before, fixture.native.reads)
        assertTrue(fixture.lines.isEmpty())
        assertSame(terminal, assertFailsWith<IOException> { fixture.daemon.shutdown() })
        assertEquals(fixture.native.acquired.sorted(), fixture.native.closed.sorted())
    }

    private val attach = "ATTACH 4242 uprobe /agent.so 20 4d5a"

    private fun boundaryEngine(native: BoundaryNative): TierEbpfEngine =
        TierEbpfEngine(
        native,
        BoundaryDiagnosticConfig(Path.of("/fixture/evidence/boundary.bpfdiag")),
        boundaryMapInfo = { native.info.getValue(it) },
    )

    private fun setPollingControl(native: BoundaryNative) {
        val buffer = ByteBuffer.wrap(native.control).order(ByteOrder.LITTLE_ENDIAN)
        listOf(12L, 11, 10, 9, 0, 0, 3, 2).forEachIndexed { index, value -> buffer.putLong(index * 8, value) }
    }

    private fun receipt(line: String): Map<String, String> {
        assertTrue(line.startsWith("tier-e-boundary-control: "))
        assertTrue(line.endsWith("\n"))
        assertTrue(line.length <= 1024 && line.all { it.code in 32..126 || it == '\n' })
        val fields = line
            .removePrefix("tier-e-boundary-control: ")
            .trimEnd()
            .split(' ')
            .map { it.split('=') }
        assertTrue(fields.all { it.size == 2 })
        val result = fields.associate { it[0] to it[1] }
        assertEquals(fields.size, result.size)
        assertEquals(
            setOf(
                "version",
                "status",
                "installation",
                "ordinal",
                "initialized",
                "target",
                "epoch",
                "capacity",
                "record_bytes",
                "control_bytes",
            "allocated_bytes",
                "combined_storage_bound",
                "bookkeeping_bound",
                "attempted",
                "reserved",
                "entries",
                "terminals",
                "drops",
                "helper_failures",
            "main_in_flight",
                "marker_in_flight",
            ),
                result.keys,
        )
        assertEquals("ok", result["status"])
        return result
    }

    private fun clientStatus(daemon: TierEDaemonService): String {
        val request = ByteArrayOutputStream().also { DataOutputStream(it).writeUTF("STATUS") }.toByteArray()
        val response = ByteArrayOutputStream()
        val socket = object : Socket() {
            override fun setSoTimeout(timeout: Int) {}

            override fun getInputStream() = ByteArrayInputStream(request)

            override fun getOutputStream() = response

            override fun close() {}
        }
        TierEClientSession.run(socket, AtomicBoolean(false), daemon::executeCommand)
        return DataInputStream(ByteArrayInputStream(response.toByteArray())).readUTF()
    }

    private inner class DaemonFixture(
        val native: BoundaryNative,
    ) {
        lateinit var daemon: TierEDaemonService
        lateinit var source: ReadinessSource
        val effects = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var factories = 0
        var reporters = 0
    }

    private fun daemonFixture(
        enabled: Boolean = true,
        publishFailure: Throwable? = null,
        publisher: ((String) -> Unit)? = null,
        reporterEffect: ((TierEbpfEngine, Long, (String) -> Unit) -> (() -> Unit))? = null,
    ): DaemonFixture {
        val fixture = DaemonFixture(BoundaryNative())
        val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/agent.so"), 0x20, "orders.mzse")
        fixture.daemon = TierEDaemonService(
            AtomicBoolean(false),
            engineFactory = {
                fixture.factories++
                if (enabled) boundaryEngine(fixture.native) else TierEbpfEngine(fixture.native)
            },
            artifactResolver = TierEDaemonArtifactResolver(directory),
            registeredTargetResolver = TierETargetRegistrationResolver(mapOf("orders" to target)),
            processIdentityVerifier = TierEProcessIdentityVerifier { 9876 },
            terminalArtifactWaitMillis = 0,
            allTargetCapture = true,
            boundaryPublisher = publisher ?: { line ->
                fixture.lines += line
                publishFailure?.let { throw it }
                Unit
            },
            boundaryReporterFactory = { engine, installation, publish ->
                fixture.reporters++
                reporterEffect?.invoke(engine, installation, publish) ?: engine.boundaryControlReporter(installation, publish)
            },
            sessionFactory = { engine, process, options, epoch ->
                val source = ReadinessSource(engine, fixture.effects)
                fixture.source = source
                TierEProfilerSession(source, process, options, configuredSessionEpoch = epoch)
            },
        )
        return fixture
    }

    private class ReadinessSource(
        private val engine: TierEbpfEngine,
        private val effects: MutableList<String>,
    ) : TierEEventSource {
        var exportFailure: Throwable? = null
        var closeFailure: Throwable? = null

        override fun install(
            targetPid: Int,
            sessionTag: Int,
            options: TierEOptions,
        ) = engine.install(targetPid, sessionTag, options)

        override fun attachMarker(
            targetPid: Int,
            agentLibrary: Path,
            markerOffset: Long,
        ) {}

        override fun enableCaptureAdmission() {}

        override fun disableCaptureAdmission() {
            effects += "disable"
        }

        override fun detachMarker() {
            effects += "detach"
        }

        override fun awaitSysEnterQuiescence(timeoutNanos: Long): Boolean {
            effects += "quiesce"
            return true
        }

        override fun exportBoundaryDiagnostics(
            producerClosed: Boolean,
            quiescent: Boolean,
        ) {
            effects += "export:$producerClosed:$quiescent"
            exportFailure?.let { throw it }
        }

        override fun drainEvents(): List<SyscallInvocationEvent> = emptyList()

        override fun readLossCount(): Long = 0

        override fun readMarkerFailureCount(): Long = 0

        override fun readMarkerTransitionCount(): Long = 0

        override fun readLastMarkerPidTgid(): Long = 0

        override fun close() {
            effects += "close"
            closeFailure?.let {
                closeFailure = null
                throw it
            }
            engine.close()
        }
    }

    private class BoundaryNative : MockNativeEngine() {
        val control = ByteArray(128)
        val commands = mutableListOf<Long>()
        val acquired = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        val info = mutableMapOf<Int, String>()
        var reads = 0
        var controlFd = -1
        var readFailure: Throwable? = null
        var readAction: (() -> Unit)? = null

        override fun syscall(
            nr: Long,
            a1: NativeArg,
            a2: NativeArg,
            a3: NativeArg,
            a4: NativeArg,
            a5: NativeArg,
            a6: NativeArg,
        ): SyscallResult<Long, SyscallHandledState.Unhandled> {
            if (nr == Arch.current().close.toLong()) {
                closed += a1.asLong
                return RealNativeEngine.syscall(nr, a1, a2, a3, a4, a5, a6)
            }
            check(nr == Syscall.BPF.numberFor(Arch.current()).toLong())
            val cmd = a1.asLong
            commands += cmd
            val attr = (a2 as NativeArg.MemoryArg).value.native
            if (cmd in listOf(0L, 5L, 17L, 18L)) {
                val result = NativeArena.ofConfined().use { LinuxNative.fileSystem.open(it.allocateFrom("/dev/null"), OpenFlags(0)) }
                if (result is SyscallResult.Success) {
                    acquired += result.value
                    if (cmd == 0L) {
                        val type = attr.get(ValueLayout.JAVA_INT, 0)
                        val size = attr.get(ValueLayout.JAVA_INT, 8)
                        val capacity = attr.get(ValueLayout.JAVA_INT, 12)
                        info[result.value.toInt()] = "map_type:\t$type\nvalue_size:\t$size\nmax_entries:\t$capacity\nmemlock:\t${if (size == 112) 3674112 else 4096}\n"
                        if (size == 128 && capacity == 1) controlFd = result.value.toInt()
                    }
                }
                return result
            }
            if (cmd == 1L && attr.get(ValueLayout.JAVA_INT, 0) == controlFd) {
                reads++
                readAction?.invoke()
                readFailure?.let { throw it }
                attr.get(ValueLayout.ADDRESS, 16).reinterpret(128).copyFrom(MemorySegment.ofArray(control))
            }
            return SyscallResult.Success(0L)
        }
    }

    @Test
    fun liveControlAllowsPollingInflightAndInterleavedCounters() {
        for (counts in listOf(
            listOf(12L, 11, 10, 9, 0, 0, 3, 2),
            listOf(1L, 0, 3, 4, 0, 0, 2, 1),
            listOf(5L, 4, 2, 3, 0, 0, 0, 1),
        )) {
            val bytes = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            counts.forEachIndexed { index, value -> bytes.putLong(index * 8, value) }
            assertEquals(
                BoundaryDiagnosticControl(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7]),
                decodeLiveBoundaryControl(bytes.array()),
            )
        }
    }

    @Test
    fun liveControlRejectsLossExhaustionUnknownAndReservedData() {
        for ((offset, value) in (0 until 8).map { it * 8 to -1L } +
            listOf(
            0 to 32768L,
                8 to 32768L,
                16 to 32769L,
                24 to 32769L,
            32 to 1L,
                40 to 1L,
                64 to 1L,
                120 to 1L,
        )) {
            val bytes = ByteArray(128)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value)
            assertFailsWith<IllegalArgumentException>("offset=$offset value=$value") { decodeLiveBoundaryControl(bytes) }
        }
        for (size in listOf(0, 127, 129)) {
            assertFailsWith<IllegalArgumentException> { decodeLiveBoundaryControl(ByteArray(size)) }
        }
    }

    @Test
    fun capacityAndAttemptedCountsReconcile() {
        val bytes = image().copyOf(256 + 32768 * 112)
        bytes.fill(0, 256)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b
            .putLong(128, 32769)
            .putLong(136, 32768)
            .putLong(144, 0)
            .putLong(152, 0)
            .putLong(160, 1)
        val snapshot = decodeBoundaryDiagnostics(bytes)
        assertEquals(32768, snapshot.records.size)
        assertEquals(snapshot.control.attempted, snapshot.control.reserved + snapshot.control.drops)
        assertFalse(snapshot.complete)
    }

    @Test
    fun daemonOptInIsStrictAndRegisteredOnly() {
        fun config(
            env: Map<String, String>,
            registered: Boolean = true,
        ) = io.mazewall.profiler.tierE.daemon
            .boundaryDiagnosticsFromEnvironment(env, registered)
        assertEquals(null, config(emptyMap(), false))
        assertEquals(null, config(mapOf("TIER_E_TEST_BOUNDARY_DIAGNOSTICS" to ""), false))
        val enabled = mapOf("TIER_E_TEST_BOUNDARY_DIAGNOSTICS" to "1", "TIER_E_ALL_TARGET_CAPTURE" to "1")
        assertEquals(Path.of("/fixture/evidence/boundary.bpfdiag"), config(enabled)?.output)
        assertFailsWith<IllegalArgumentException> { config(enabled, false) }
        for ((key, value) in listOf(
            "TIER_E_TEST_BOUNDARY_DIAGNOSTICS" to "true",
                "TIER_E_ALL_TARGET_CAPTURE" to "0",
                "TIER_E_MARKER_VERSION" to "v2",
                "TIER_E_COVERAGE_RECOVERY_MODE" to "recover",
            "TIER_E_BENCHMARK_PHASES" to "1",
                "TIER_E_SPLIT_BENCHMARK" to "1",
        )) {
            assertFailsWith<IllegalArgumentException> { config(enabled + (key to value)) }
        }
        assertFailsWith<IllegalArgumentException> { BoundaryDiagnosticConfig(Path.of("boundary.bpfdiag")) }
        assertFailsWith<IllegalArgumentException> { BoundaryDiagnosticConfig(directory.resolve("boundary.bpfdiag")) }
    }

    @Test
    fun decodedRecordsAndWordsAreImmutableOwnedCopies() {
        val bytes = image()
        val snapshot = decodeBoundaryDiagnostics(bytes)
        bytes.fill(0)
        assertEquals(ULong.MAX_VALUE, snapshot.records.single().rawId)
        assertFailsWith<UnsupportedOperationException> { (snapshot.records as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.records.single().words as MutableList)[0] = 0uL }
    }

    @Test
    fun attemptedButUnaccountedRecordsCannotProveAbsence() {
        val bytes = image().copyOf(256)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(136, 0).putLong(144, 0).putLong(152, 0)
        assertFalse(decodeBoundaryDiagnostics(bytes).complete)
    }

    @Test
    fun readFailureRetainsPartialTemporaryAndIdentity() {
        val output = directory.resolve("failed-read.bpfdiag")
        val failure = java.io.IOException("map lookup denied")
        assertSame(
            failure,
            assertFailsWith<java.io.IOException> {
            streamBoundaryDiagnostics(output, 1, 2, true, true, { control, _, buffer ->
                if (control) buffer.putLong(0, 1).putLong(8, 1) else throw failure
            })
        },
        )
        assertFalse(Files.exists(output))
        assertEquals(256L, Files.size(output.resolveSibling("${output.fileName}.tmp")))
    }

    @Test
    fun unknownActualAllocationFailsBeforeInstallationAndClosesBothFds() {
        val native = MapAcquisitionNative(failSecondMap = false)
        val engine = TierEbpfEngine(native, BoundaryDiagnosticConfig(Path.of("/fixture/evidence/boundary.bpfdiag")))
        val failure = assertFailsWith<IllegalArgumentException> {
            engine.install(
                12345,
                0x4d5a,
                io.mazewall.profiler.internal.attribution
            .TierEOptions(allTargetCapture = true),
            )
        }
        assertTrue(failure.message.orEmpty().contains("map_type"))
        engine.close()
        assertEquals(2, native.acquired.size)
        assertEquals(native.acquired, native.closed)
    }

    @Test
    fun allocationRequiresActualBoundedKernelAccounting() {
        val records = "map_type:\t2\nvalue_size:\t112\nmax_entries:\t32768\nmemlock:\t3674112\n"
        val control = "map_type:\t2\nvalue_size:\t128\nmax_entries:\t1\nmemlock:\t4096\n"
        assertEquals(3678208L, boundaryAllocatedBytes(records, control))
        assertFailsWith<IllegalArgumentException> { boundaryAllocatedBytes(records.replace("memlock:", "unknown:"), control) }
        assertFailsWith<IllegalArgumentException> { boundaryAllocatedBytes(records.replace("3674112", "4000000"), control) }
        assertFailsWith<IllegalArgumentException> { boundaryAllocatedBytes(records.replace("3674112", "9223372036854775807"), control) }
        assertFailsWith<IllegalArgumentException> { boundaryAllocatedBytes(records.replace("112", "128"), control) }
    }

    @Test
    fun diagnosticOffAcquiresNoMaps() {
        val native = MapAcquisitionNative()
        val engine = TierEbpfEngine(native)
        assertFailsWith<IllegalStateException> { engine.install(12345) }
        engine.exportBoundaryDiagnostics(false, false)
        assertEquals(emptyList(), native.mapSizes)
        assertEquals(emptyList(), native.closed)
    }

    @Test
    fun secondMapFailureClosesActualAcquiredFdOnce() {
        val native = MapAcquisitionNative()
        val engine = TierEbpfEngine(native, BoundaryDiagnosticConfig(Path.of("/fixture/evidence/boundary.bpfdiag")))
        assertFailsWith<IllegalStateException> {
            engine.install(
                12345,
                0x4d5a,
                io.mazewall.profiler.internal.attribution
            .TierEOptions(allTargetCapture = true),
            )
        }
        engine.close()
        engine.close()
        assertEquals(listOf(112, 128), native.mapSizes)
        assertEquals(1, native.acquired.size)
        assertEquals(native.acquired, native.closed)
    }

    private class MapAcquisitionNative(
        private val failSecondMap: Boolean = true,
    ) : io.mazewall.enforcer.api.MockNativeEngine() {
        val mapSizes = mutableListOf<Int>()
        val acquired = mutableListOf<Long>()
        val closed = mutableListOf<Long>()

        override fun syscall(
            nr: Long,
            a1: io.mazewall.platform.syscall.NativeArg,
            a2: io.mazewall.platform.syscall.NativeArg,
            a3: io.mazewall.platform.syscall.NativeArg,
            a4: io.mazewall.platform.syscall.NativeArg,
            a5: io.mazewall.platform.syscall.NativeArg,
            a6: io.mazewall.platform.syscall.NativeArg,
        ): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> {
            if (nr == io.mazewall.platform.syscall.Arch
                .current()
                .close
                .toLong()
            ) {
                closed += a1.asLong
                return io.mazewall.ffi.internal.RealNativeEngine
                    .syscall(nr, a1, a2, a3, a4, a5, a6)
            }
            check(
                nr == io.mazewall.platform.syscall.Syscall.BPF
                .numberFor(
                    io.mazewall.platform.syscall.Arch
                    .current(),
                ).toLong(),
            )
            if (a1.asLong == 0L) {
                val attr = (a2 as io.mazewall.platform.syscall.NativeArg.MemoryArg).value.native
                mapSizes += attr.get(java.lang.foreign.ValueLayout.JAVA_INT, 8)
                if (mapSizes.size == 1 || !failSecondMap && mapSizes.size == 2) {
                    return io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                        val result = io.mazewall.platform.native.LinuxNative.fileSystem
                            .open(
                                arena.allocateFrom("/dev/null"),
                                io.mazewall.platform.lifecycle
                                .OpenFlags(0),
                            )
                        if (result is io.mazewall.platform.native.LinuxNative.SyscallResult.Success) acquired += result.value
                        result
                    }
                }
            }
            if (a1.asLong == 1L) {
                val attr = (a2 as io.mazewall.platform.syscall.NativeArg.MemoryArg).value.native
                attr.get(ValueLayout.ADDRESS, 16).reinterpret(128).fill(0)
                return SyscallResult.Success(0L)
            }
            return io.mazewall.platform.native.LinuxNative.SyscallResult
                .Error(5, -1)
        }
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun streamingExportDoesNotMirrorArray() {
        val output = directory.resolve("boundary.bpfdiag")
        var buffer: ByteBuffer? = null
        val reads = mutableListOf<Int>()
        streamBoundaryDiagnostics(output, 0x4d5a, 12345, true, true, { control, index, destination ->
            if (buffer == null) buffer = destination else assertSame(buffer, destination)
            assertEquals(128, destination.capacity())
            if (control) {
                destination
                    .putLong(0, 32769)
                    .putLong(8, 32768)
                    .putLong(16, 0)
                    .putLong(24, 0)
                    .putLong(32, 1)
            } else {
                assertTrue(index in 0 until 32768)
                reads += index
                destination.putInt(0, if (index == 0) 1 else 0).putInt(4, 2)
            }
        })
        assertEquals(32768, reads.size)
        val snapshot = decodeBoundaryDiagnostics(Files.readAllBytes(output))
        assertEquals(32769L, snapshot.control.attempted)
        assertEquals(1, snapshot.records.first().commit)
        assertFalse(snapshot.complete)
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(output))
    }

    @Test
    fun failedPublishKeepsTemporaryAndOriginalError() {
        val output = directory.resolve("boundary.bpfdiag")
        val failure = java.io.IOException("publication denied")
        val thrown = assertFailsWith<java.io.IOException> {
            streamBoundaryDiagnostics(output, 1, 2, false, false, { _, _, _ -> }, { _, _ -> throw failure })
        }
        assertSame(failure, thrown)
        assertFalse(Files.exists(output))
        assertEquals(256L, Files.size(directory.resolve("boundary.bpfdiag.tmp")))
    }

    @Test
    fun publicationNeverReplacesExistingOrSymlinkDestination() {
        for (symlink in listOf(false, true)) {
            val output = directory.resolve("boundary-$symlink.bpfdiag")
            val original = directory.resolve("original-$symlink")
            Files.writeString(original, "retained")
            if (symlink) Files.createSymbolicLink(output, original) else Files.writeString(output, "retained")
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                streamBoundaryDiagnostics(output, 1, 2, true, true, { _, _, _ -> })
            }
            assertEquals("retained", Files.readString(output))
            assertEquals("retained", Files.readString(original))
            assertTrue(Files.exists(output.resolveSibling("${output.fileName}.tmp")))
        }
    }

    private fun image(commit: Int = 3): ByteArray =
        ByteArray(256 + 112).also { bytes ->
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b
            .put(byteArrayOf(77, 90, 66, 68))
            .putShort(1)
            .putShort(128)
            .putInt(112)
            .putInt(32768)
        b
            .putLong(0x4d5a)
            .putLong(12345)
            .putInt(1)
            .putInt(1)
        b.position(128)
        b
            .putLong(1)
            .putLong(1)
            .putLong(1)
            .putLong(if (commit == 3) 1 else 0)
        b.position(256)
        b
            .putInt(commit)
            .putInt(2)
            .putLong(11)
            .putLong(12345L shl 32 or 17)
            .putLong(-1)
        b.putLong(123).putLong(9)
        repeat(6) { b.putLong(100L + it) }
        b
            .putInt(29)
            .putInt(7)
            .putInt(8)
            .putInt(1023)
    }

    @Test
    fun exactOffsetsAndStageEncodings() {
        val snapshot = decodeBoundaryDiagnostics(image())
        assertEquals(0x4d5aL, snapshot.session)
        assertEquals(12345L, snapshot.targetTgid)
        val row = snapshot.records.single()
        assertEquals(ULong.MAX_VALUE, row.rawId)
        assertEquals(123uL, row.invocation)
        assertEquals(9uL, row.sequence)
        assertEquals((100uL..105uL).toList(), row.words)
        assertEquals(BoundaryStage.MAIN_ACTIVE_COUNTER_REACHED, row.stage)
        assertEquals(7u, row.generation)
        assertEquals(8u, row.aux)
        assertEquals(1023u, row.validFields)
        assertEquals(listOf(0) + (1..10).toList() + (20..30).toList(), BoundaryStage.entries.map { it.encoding })
    }

    @Test
    fun uncommittedSlotIsIncomplete() {
        for (commit in listOf(0, 1)) {
            val snapshot = decodeBoundaryDiagnostics(image(commit))
            assertEquals(commit, snapshot.records.single().commit)
            assertFalse(snapshot.complete)
        }
    }

    @Test
    fun terminalEntryStageIsRejected() {
        val bytes = image()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(352, BoundaryStage.ENTRY.encoding)
        assertFailsWith<IllegalArgumentException> { decodeBoundaryDiagnostics(bytes) }
    }

    @Test
    fun mainPlaneWithMarkerTerminalStageIsRejected() {
        val bytes = image()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(352, BoundaryStage.MARKER_SET.encoding)
        assertFailsWith<IllegalArgumentException> { decodeBoundaryDiagnostics(bytes) }
    }

    @Test
    fun markerPlaneWithMainTerminalStageIsRejected() {
        val bytes = image()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(260, 1)
        assertFailsWith<IllegalArgumentException> { decodeBoundaryDiagnostics(bytes) }
    }

    @Test
    fun matchingTerminalStagesAndInterruptedStageWritesRemainReadable() {
        for (stage in BoundaryStage.entries.filter { it != BoundaryStage.ENTRY }) {
            val plane = if (stage.encoding in 1..10) 1 else 2
            for (commit in listOf(1, 3)) {
                val bytes = image(commit)
                ByteBuffer
                    .wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(260, plane)
                    .putInt(352, stage.encoding)
                val snapshot = decodeBoundaryDiagnostics(bytes)
                assertEquals(stage, snapshot.records.single().stage)
                assertEquals(plane, snapshot.records.single().plane)
                assertEquals(commit, snapshot.records.single().commit)
                assertEquals(commit == 3, snapshot.complete)
            }
        }
    }

    @Test
    fun rejectsOverflowAndMalformedImages() {
        assertFailsWith<IllegalArgumentException> { decodeBoundaryDiagnostics(image().copyOf(367)) }
        for (offset in listOf(12, 128, 136)) {
            val bad = image()
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, -1)
            assertFailsWith<IllegalArgumentException> { decodeBoundaryDiagnostics(bad) }
        }
    }
}
