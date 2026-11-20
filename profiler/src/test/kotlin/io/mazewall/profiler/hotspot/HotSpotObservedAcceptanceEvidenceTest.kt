package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.api.HotSpotProfilingException
import io.mazewall.profiler.api.HotSpotProfilingSession
import io.mazewall.profiler.api.StructuredProfileEvidence
import io.mazewall.profiler.internal.hotspot.HotSpotCaptureExpectation
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorProcess
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotCoverageAdapter
import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocol
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.mazewall.profiler.internal.hotspot.HotSpotProfilingSession as OwnedSession

@EnabledIfLinuxAndSupported
class HotSpotObservedAcceptanceEvidenceTest {
    @Test
    fun `maps are bounded and preserved`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("maps")
        val bytes = "1000-2000 r-xp 0 00:00 1 /actual/libjvm.so\n".toByteArray() + byteArrayOf(0, -1)
        Files.write(file, bytes)
        assertContentEquals(bytes, readObservedMaps(file))
        val bound = ByteArray(16 * 1024 * 1024) { 42 }
        Files.write(file, bound)
        assertContentEquals(bound, readObservedMaps(file))
        Files.newOutputStream(file).use { it.write(ByteArray(bound.size + 1)) }
        assertFailsWith<IllegalArgumentException> { readObservedMaps(file) }
        Files.write(file, byteArrayOf())
        assertFailsWith<IllegalArgumentException> { readObservedMaps(file) }
    }

    @Test
    fun `maps read denial keeps its original filesystem cause`(
        @TempDir directory: Path,
    ) {
        val file = Files.writeString(directory.resolve("maps"), "unreadable")
        Files.setPosixFilePermissions(file, emptySet())
        try {
            val denied = assertFailsWith<AccessDeniedException> { readObservedMaps(file) }
            assertEquals(file.toString(), denied.file)
            assertSame(denied, retainObservedFailure(directory, denied))
            assertEquals("unknown", Files.readString(directory.resolve("pending-ownership.txt")))
            assertFalse(Files.exists(directory.resolve("native-errnos.json")))
        } finally {
            Files.setPosixFilePermissions(file, setOf(OWNER_READ, OWNER_WRITE))
        }
    }

    @Test
    fun `maps identity is checked before reading and after reading`(
        @TempDir directory: Path,
    ) {
        val identity = target()
        val changed = listOf(
            identity.copy(pid = Pid(124)),
            identity.copy(processStartTicks = 457uL),
            identity.copy(cgroupId = 790uL),
            identity.copy(mountNamespace = 43uL),
        )
        val maps = directory.resolve("maps")
        val destination = directory.resolve("target-maps")
        for (other in changed) {
            var reads = 0
            assertFailsWith<IllegalArgumentException> {
                retainObservedMaps(maps, destination, identity) {
                    reads++
                    other
                }
            }
            assertEquals(1, reads)
            assertFalse(Files.exists(destination))
        }
        val bytes = byteArrayOf(10, -1, 0, 13)
        Files.write(maps, bytes)
        for (other in changed) {
            var reads = 0
            assertFailsWith<IllegalArgumentException> {
                retainObservedMaps(maps, destination, identity) { if (reads++ == 0) identity else other }
            }
            assertEquals(2, reads)
            assertFalse(Files.exists(destination))
        }
        var reads = 0
        retainObservedMaps(maps, destination, identity) {
            reads++
            identity.copy()
        }
        assertEquals(2, reads)
        assertContentEquals(bytes, Files.readAllBytes(destination))
        Files.write(maps, byteArrayOf(42))
        assertFailsWith<FileAlreadyExistsException> { retainObservedMaps(maps, destination, identity) { identity } }
        assertContentEquals(bytes, Files.readAllBytes(destination))
    }

    @Test
    fun `retention does not overwrite or erase the original failure`(
        @TempDir directory: Path,
    ) {
        Files.writeString(directory.resolve("failure.txt"), "first attempt", CREATE_NEW)
        val original = AccessDeniedException("owned-maps")
        val result = retainObservedFailure(directory, original)
        assertSame(original, result)
        assertEquals("first attempt", Files.readString(directory.resolve("failure.txt")))
        assertTrue(result.suppressed.any { it is FileAlreadyExistsException })
        assertEquals("unknown", Files.readString(directory.resolve("pending-ownership.txt")))
    }

    @Test
    fun `typed failures retain original errno diagnostics partial evidence and discard count`(
        @TempDir directory: Path,
    ) {
        val original = profilingFailure()
        Files.writeString(directory.resolve("collector-failure.stderr"), "first stderr", CREATE_NEW)
        assertSame(original, retainObservedFailure(directory, original))
        assertTrue(original.cause is AccessDeniedException)
        assertEquals("first stderr", Files.readString(directory.resolve("collector-failure.stderr")))
        assertTrue(original.suppressed.any { it is FileAlreadyExistsException })
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
        assertEquals("7", Files.readString(directory.resolve("collector-failure-discarded-bytes.txt")))
        assertEquals("false", Files.readString(directory.resolve("pending-ownership.txt")))
        assertEquals("false", Files.readString(directory.resolve("root-failure-pending-ownership.txt")))
        assertEquals(original.partialResult!!.structuredEvidence!!.toJson(), Files.readString(directory.resolve("partial-evidence.json")))
    }

    @Test
    fun `wrapper causes retain actual typed native failure data`(
        @TempDir directory: Path,
    ) {
        val original = profilingFailure()
        val wrapper = IllegalStateException("outer", original)
        assertSame(wrapper, retainObservedFailure(directory, wrapper))
        assertSame(original, wrapper.cause)
        assertEquals("unknown", Files.readString(directory.resolve("root-failure-pending-ownership.txt")))
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
        assertEquals(original.diagnostics, Files.readString(directory.resolve("collector-failure.stderr")))
        assertEquals(original.partialResult!!.structuredEvidence!!.toJson(), Files.readString(directory.resolve("partial-evidence.json")))
    }

    @Test
    fun `promoted Error retains suppressed HotSpot failure data`(
        @TempDir directory: Path,
    ) {
        val original = profilingFailure()
        val promoted = AssertionError("cleanup")
        assertSame(promoted, CleanupFailures.merge(original, promoted))
        assertSame(promoted, retainObservedFailure(directory, promoted))
        assertSame(original, promoted.suppressed.single())
        assertEquals("unknown", Files.readString(directory.resolve("root-failure-pending-ownership.txt")))
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
        assertEquals(original.diagnostics, Files.readString(directory.resolve("collector-failure.stderr")))
        assertEquals(original.partialResult!!.structuredEvidence!!.toJson(), Files.readString(directory.resolve("partial-evidence.json")))
    }

    @Test
    fun `distinct reachable native failures are retained once each`(
        @TempDir directory: Path,
    ) {
        val first = profilingFailure()
        val second = profilingFailure()
        val wrapper = IllegalStateException("outer", first)
        wrapper.addSuppressed(first)
        wrapper.addSuppressed(second)
        assertSame(wrapper, retainObservedFailure(directory, wrapper))
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos-2.json")))
        assertEquals(second.diagnostics, Files.readString(directory.resolve("collector-failure-2.stderr")))
        assertFalse(Files.exists(directory.resolve("native-errnos-3.json")))
    }

    @Test
    fun `duplicate suppressed prefix cannot hide a reachable native failure`(
        @TempDir directory: Path,
    ) {
        val native = profilingFailure()
        val ordinary = requireNotNull(native.cause)
        val root = IllegalStateException("outer")
        repeat(64) { root.addSuppressed(ordinary) }
        root.addSuppressed(native)

        assertSame(root, retainObservedFailure(directory, root))
        assertEquals("unknown", Files.readString(directory.resolve("root-failure-pending-ownership.txt")))
        assertTrue(Files.exists(directory.resolve("native-errnos.json")), "Reachable native fields omitted behind duplicate suppressed references")
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
        assertEquals(native.diagnostics, Files.readString(directory.resolve("collector-failure.stderr")))
        assertEquals("7", Files.readString(directory.resolve("collector-failure-discarded-bytes.txt")))
        assertEquals(native.partialResult!!.structuredEvidence!!.toJson(), Files.readString(directory.resolve("partial-evidence.json")))
        assertEquals("false", Files.readString(directory.resolve("pending-ownership.txt")))
        val duplicateFiles = listOf(
            "native-errnos-2.json",
            "collector-failure-2.stderr",
            "collector-failure-2-discarded-bytes.txt",
            "partial-evidence-2.json",
            "pending-ownership-2.txt",
        )
        for (name in duplicateFiles) {
            assertFalse(Files.exists(directory.resolve(name)), "Native failure retained more than once: $name")
        }
    }

    @Test
    fun `cause traversal uses identity and terminates on cycles`(
        @TempDir directory: Path,
    ) {
        val cycle = object : IllegalStateException("cycle") {
            override fun equals(other: Any?): Boolean = error("Throwable equality must not be used")

            override fun hashCode(): Int = error("Throwable hashing must not be used")
        }
        val bridge = IllegalStateException("bridge", cycle)
        cycle.initCause(bridge)
        bridge.addSuppressed(profilingFailure())
        cycle.addSuppressed(bridge)
        assertSame(cycle, retainObservedFailure(directory, cycle))
        assertEquals("[13]", Files.readString(directory.resolve("native-errnos.json")))
    }

    @Test
    fun `cause traversal is bounded without inventing ownership`(
        @TempDir directory: Path,
    ) {
        var failure: Throwable = profilingFailure()
        repeat(100) { failure = IllegalStateException("wrapper", failure) }
        assertSame(failure, retainObservedFailure(directory, failure))
        assertEquals("unknown", Files.readString(directory.resolve("pending-ownership.txt")))
        assertFalse(Files.exists(directory.resolve("native-errnos.json")))
    }

    @Test
    fun `only observed nmethod PC provenance counts`() {
        for (scope in listOf("exact_pc", "preceding_pc")) {
            assertEquals(setOf("observed:0:1"), observedCompiledMarkedEvents(evidence(scope), setOf("synthetic")))
        }
        for (method in listOf("routeA", "routeB")) {
            val route = replay(evidenceText("exact_pc").replace("ObservedWorkload.leaf(", "ObservedWorkload.$method("))
            assertEquals(setOf("observed:0:1"), observedCompiledMarkedEvents(route, setOf("synthetic")))
        }
        for (scope in listOf("interpreter", "unknown", "stub", "method_only")) {
            assertTrue(observedCompiledMarkedEvents(evidence(scope), setOf("synthetic")).isEmpty())
        }
        assertTrue(observedCompiledMarkedEvents(evidence("exact_pc"), emptySet()).isEmpty())
    }

    @Test
    fun `partial error truncated unavailable and unrelated frames cannot supply compiled proof`() {
        val text = evidenceText("exact_pc")
        val truncatedPath = "/${"x".repeat(244)}/synthetic"
        val variants = listOf(
            text.replace("\"scope_walk\":\"returned\"", "\"scope_walk\":\"partial\""),
            text.replace("\"error_frame\":false", "\"error_frame\":true"),
            text
                .replace("\"path_truncated\":false", "\"path_truncated\":true")
                .replace("\"argument_read\":11", "\"argument_read\":256")
                .replace("\"filename\":\"/synthetic\"", "\"filename\":\"$truncatedPath\"")
                .replace("L3N5bnRoZXRpYw==", Base64.getEncoder().encodeToString(truncatedPath.toByteArray())),
            text.replace("ObservedWorkload.leaf(", "OtherWorkload.leaf("),
            text
                .replace(
                    "\"attribution\":\"event_correlated\"",
                    "\"attribution\":\"unavailable\",\"reason\":\"JIT info evicted\"",
                ).replace("\"resolved\":1,\"unavailable\":0", "\"resolved\":0,\"unavailable\":1"),
            text.replace("\"scope_lookup\":\"exact_pc\",\"scope_walk\":\"returned\"", "\"scope_walk\":\"unknown\""),
        )
        for (variant in variants) {
            val evidence = replay(variant)
            assertEquals("observed:0:1", evidence.records.single().eventId)
            assertTrue(observedCompiledMarkedEvents(evidence, setOf("synthetic")).isEmpty())
        }
    }

    @Test
    fun `diagnostic write failures cannot prevent synthetic collector close`(
        @TempDir directory: Path,
    ) {
        val session = syntheticSession(directory, "stderr")
        val original = AccessDeniedException("maps")
        Files.writeString(directory.resolve("collector-before-close.stderr"), "first stderr", CREATE_NEW)
        Files.writeString(directory.resolve("collector-after-close.stderr"), "last stderr", CREATE_NEW)
        try {
            assertSame(original, closeObservedSession(directory, session, original))
            assertFalse(session.pendingOwnership)
            assertEquals(2, original.suppressed.filterIsInstance<FileAlreadyExistsException>().size)
            assertEquals("first stderr", Files.readString(directory.resolve("collector-before-close.stderr")))
            assertEquals("last stderr", Files.readString(directory.resolve("collector-after-close.stderr")))
            assertTrue(Files.readString(directory.resolve("collector-after-close-discarded-bytes.txt")).toLong() > 0)
            assertEquals("false", Files.readString(directory.resolve("pending-ownership-after-close.txt")))
        } finally {
            session.close()
        }
    }

    @Test
    fun `pending ownership is retained from the real synthetic child and retired despite write failures`(
        @TempDir directory: Path,
    ) {
        val session = syntheticSession(directory, "hang")
        try {
            val original = assertFailsWith<HotSpotProfilingException> { session.finish() }
            assertTrue(original.pendingOwnership)
            val before = Files.createDirectory(directory.resolve("before"))
            Files.writeString(before.resolve("failure.txt"), "first failure", CREATE_NEW)
            assertSame(original, retainObservedFailure(before, original))
            assertEquals("true", Files.readString(before.resolve("pending-ownership.txt")))
            val result = closeObservedSession(directory, session, original)
            assertSame(original, result)
            assertFalse(session.pendingOwnership)
            assertEquals("false", Files.readString(directory.resolve("pending-ownership-after-close.txt")))
            assertTrue(Files.exists(directory.resolve("collector-before-close.stderr")))
            assertTrue(Files.exists(directory.resolve("collector-after-close.stderr")))
            val after = Files.createDirectory(directory.resolve("after"))
            assertSame(original, retainObservedFailure(after, original))
            assertEquals("false", Files.readString(after.resolve("pending-ownership.txt")))
            assertEquals("true", Files.readString(before.resolve("pending-ownership.txt")))
        } finally {
            val closed = assertFailsWith<HotSpotProfilingException> { session.close() }
            assertFalse(closed.pendingOwnership)
        }
    }

    private fun profilingFailure(): HotSpotProfilingException =
        HotSpotProfilingException(
            "native observation denied",
            AccessDeniedException("process memory"),
            HotSpotCoverageAdapter.toLiveResult(evidence("exact_pc").owner, false),
            null,
            listOf(13u),
            "original collector diagnostic\n",
            7,
        )

    private fun target(): HotSpotObservationTarget = HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL)

    private fun syntheticSession(
        directory: Path,
        mode: String,
    ): HotSpotProfilingSession {
        val owner = HotSpotCollectorProcess.attach(
            HotSpotChildFixture.argv(mode, directory, observedTarget = target()),
            123,
            456uL,
            timing = HotSpotCollectorTiming(stopNanos = 200_000_000),
            expectation = HotSpotCaptureExpectation.Observed(target()),
        )
        return HotSpotProfilingSession(OwnedSession(owner))
    }

    private fun evidence(scope: String): StructuredProfileEvidence = replay(evidenceText(scope))

    private fun evidenceText(scope: String): String =
        requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"))
            .bufferedReader()
            .use { it.readText() }
            .replace("\"function_name\":\"leaf\"", "\"function_name\":\"ObservedWorkload.leaf(java.nio.file.Path)\"")
            .replace("\"error_frame\":false", "\"error_frame\":false,\"scope_lookup\":\"$scope\",\"scope_walk\":\"returned\"")

    private fun replay(text: String): StructuredProfileEvidence {
        val limits = HotSpotReplayLimits()
        val expected = HotSpotCaptureExpectation.Observed(target())
        val protocol = HotSpotProtocol(123, 456uL, expected)
        val accumulator = HotSpotEvidenceAssembler(limits, expected).begin()
        HotSpotWireReader(limits, 3uL).read(text.byteInputStream()) { row ->
            protocol.controlEvent(row)
            when (row.kind) {
                "session_started" -> accumulator.accept(row.copy(kind = "collector_attached"))
                "collector_ready" -> Unit
                else -> accumulator.accept(row)
            }
        }
        val result = accumulator.finish().also { protocol.summaryEvent(it) }
        return StructuredProfileEvidence(result)
    }
}
