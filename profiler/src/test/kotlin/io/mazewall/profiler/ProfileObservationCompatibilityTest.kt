package io.mazewall.profiler

import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.core.Tid
import io.mazewall.profiler.collector.CollectorDrain
import io.mazewall.profiler.collector.ObservationMerger
import io.mazewall.profiler.compiler.StraceLogParser
import io.mazewall.profiler.engine.PathResolution
import io.mazewall.profiler.engine.PathResolutionFailure
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class ProfileObservationCompatibilityTest {
    @Test
    fun `syscall identity distinguishes missing and incomplete flag evidence`() {
        val correlation = ObservationCorrelation(7, Tid(8))
        val missing = ProfileObservation.Syscall(correlation, ObservationSource.STRACE, "OPEN")
        val future = ProfileObservation.Syscall(
            correlation,
            ObservationSource.STRACE,
            "OPEN",
            openFlagsEvidence = OpenFlagsEvidence.Incomplete(0, setOf("O_FUTURE")),
        )
        val other = ProfileObservation.Syscall(
            correlation,
            ObservationSource.STRACE,
            "OPEN",
            openFlagsEvidence = OpenFlagsEvidence.Incomplete(0, setOf("O_OTHER")),
        )

        assertNotEquals(missing, future)
        assertNotEquals(future, other)
        assertEquals(3, hashSetOf(missing, future, other).size)
    }

    private val correlation = ObservationCorrelation(1, Tid(7), 42L)
    private val endpoint = NetworkEndpoint("127.0.0.1", 80)

    @Test
    fun `reflective syscall construction retains the original primary schema and defaults`() {
        val observation = construct(
            ProfileObservation.Syscall::class,
            listOf("correlation", "source", "name", "args", "paths", "openFlags", "contextId", "openFlagsEvidence"),
            listOf("args", "paths", "openFlags", "contextId", "openFlagsEvidence"),
            mapOf("correlation" to correlation, "source" to ObservationSource.STRACE, "name" to "GETPID"),
        )
        assertEquals(ProfileObservation.Syscall(correlation, ObservationSource.STRACE, "GETPID"), observation)
        assertEquals(emptyList(), observation.args)
        assertEquals(emptyList(), observation.paths)
        assertEquals(null, observation.openFlags)
        assertEquals(null, observation.contextId)
    }

    @Test
    fun `reflective uring construction retains the original primary schema and defaults`() {
        val observation = construct(
            ProfileObservation.IoUring::class,
            listOf("correlation", "source", "opcode", "paths"),
            listOf("paths"),
            mapOf("correlation" to correlation, "source" to ObservationSource.EBPF, "opcode" to "NOP"),
        )
        assertEquals(ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "NOP"), observation)
        assertEquals(emptyList(), observation.paths)
    }

    @Test
    fun `reflective connect construction retains the original primary schema and defaults`() {
        val observation = construct(
            ProfileObservation.Connect::class,
            listOf("correlation", "source", "endpoint", "paths"),
            listOf("paths"),
            mapOf("correlation" to correlation, "source" to ObservationSource.EBPF, "endpoint" to endpoint),
        )
        assertEquals(ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint), observation)
        assertEquals(emptyList(), observation.paths)
    }

    @Test
    fun `generated copies and components retain all typed outcomes on every observation variant`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "TID 7 EPERM")
        val resolutions = listOf(
            PathResolution.NotApplicable,
            PathResolution.Resolved(listOf("/tmp/a")),
            PathResolution.Partial(listOf("/tmp/b"), listOf(failure)),
            PathResolution.Failed(listOf(failure)),
        )
        for (resolution in resolutions) {
            val syscall = ProfileObservation
                .Syscall(correlation, ObservationSource.USER_NOTIF, "RENAME", listOf(9L), openFlags = 2L, contextId = 3L)
                .withPathResolution(resolution)
            val uring = ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "OPENAT").withPathResolution(resolution)
            val connect = ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint).withPathResolution(resolution)
            val changedCorrelation = correlation.copy(ktimeNs = 99L)
            val copies = listOf(
                syscall.copy(correlation = changedCorrelation, source = ObservationSource.STRACE, args = listOf(10L), openFlags = 4L, contextId = 5L),
                uring.copy(correlation = changedCorrelation, source = ObservationSource.STRACE),
                connect.copy(correlation = changedCorrelation, endpoint = NetworkEndpoint("127.0.0.2", 90)),
            )
            val copiedSyscall = assertIs<ProfileObservation.Syscall>(copies[0])
            assertEquals(listOf(10L), copiedSyscall.args)
            assertEquals(4L, copiedSyscall.openFlags)
            assertEquals(5L, copiedSyscall.contextId)
            assertEquals(ObservationSource.STRACE, copiedSyscall.source)
            assertEquals(ObservationSource.STRACE, copies[1].source)
            assertEquals(NetworkEndpoint("127.0.0.2", 90), assertIs<ProfileObservation.Connect>(copies[2]).endpoint)
            for (copy in copies) {
                assertEquals(changedCorrelation, copy.correlation)
                assertEquals(resolution, copy.pathResolution)
                assertEquals(resolution.paths, copy.paths)
                assertEquals(copy.paths, resolution.paths)
                assertEquals(resolution.paths.hashCode(), copy.paths.hashCode())
                if (copy.paths.isNotEmpty()) {
                    assertFailsWith<UnsupportedOperationException> { (copy.paths as MutableList).add("/changed") }
                }
                if (copy.pathResolution.failures.isNotEmpty()) {
                    assertFailsWith<UnsupportedOperationException> { (copy.pathResolution.failures as MutableList).add(failure) }
                }
            }
            val (sysCorrelation, sysSource, name, args, sysPaths, flags, contextId) = syscall
            assertEquals(listOf(correlation, ObservationSource.USER_NOTIF, "RENAME", listOf(9L), resolution.paths, 2L, 3L), listOf(sysCorrelation, sysSource, name, args, sysPaths, flags, contextId))
            val (uringCorrelation, uringSource, opcode, uringPaths) = uring
            assertEquals(listOf(correlation, ObservationSource.EBPF, "OPENAT", resolution.paths), listOf(uringCorrelation, uringSource, opcode, uringPaths))
            val (connectCorrelation, connectSource, destination, connectPaths) = connect
            assertEquals(listOf(correlation, ObservationSource.EBPF, endpoint, resolution.paths), listOf(connectCorrelation, connectSource, destination, connectPaths))
        }
    }

    @Test
    fun `typed observation factories snapshot argument and evidence lists`() {
        val args = mutableListOf(9L)
        val paths = mutableListOf("/tmp/a")
        val failures = mutableListOf(PathResolutionFailure(1, PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9"))
        val syscall = ProfileObservation
            .Syscall(correlation, ObservationSource.USER_NOTIF, "RENAME", args)
            .withPathResolution(PathResolution.Partial(paths, failures))
        args.clear()
        paths.clear()
        failures.clear()
        assertEquals(listOf(9L), syscall.args)
        assertEquals(listOf("/tmp/a"), syscall.paths)
        assertEquals(listOf(1), syscall.pathResolution.failures.map { it.operandIndex })
        assertEquals(syscall, syscall.copy())
        assertFailsWith<UnsupportedOperationException> { (syscall.copy().args as MutableList).add(10L) }
    }

    @Test
    fun `strace factory publishes immutable multi-operand paths`() {
        val observation = requireNotNull(StraceLogParser.parseLine("""7 rename("/tmp/a", "/tmp/b") = 0"""))
        assertEquals(listOf("/tmp/a", "/tmp/b"), observation.paths)
        assertFailsWith<UnsupportedOperationException> { (observation.paths as MutableList).clear() }
    }

    @Test
    fun `published results snapshot nested observations without discarding typed evidence`() {
        val args = mutableListOf(9L)
        val paths = mutableListOf("/tmp/a")
        val failure = PathResolutionFailure(1, PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9")
        val typed = ProfileObservation
            .Syscall(correlation, ObservationSource.USER_NOTIF, "RENAME")
            .withPathResolution(PathResolution.Partial(paths, listOf(failure)))
            .copy(args = args)
        val plain = listOf(
            ProfileObservation.Syscall(correlation, ObservationSource.STRACE, "OPEN", args, paths),
            ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "OPENAT", paths),
            ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint, paths),
        )
        val result = ProfilingResult(Unit, BillOfBehavior(), emptyMap(), observations = plain + typed)
        args.clear()
        paths.clear()
        for (observation in result.observations) {
            assertEquals(listOf("/tmp/a"), observation.paths)
            assertFailsWith<UnsupportedOperationException> { (observation.paths as MutableList).clear() }
        }
        assertEquals(listOf(9L), assertIs<ProfileObservation.Syscall>(result.observations.first()).args)
        assertEquals(listOf(9L), assertIs<ProfileObservation.Syscall>(result.observations.last()).args)
        assertEquals(
            listOf(failure),
            result
            .copy()
            .observations
            .last()
            .pathResolution.failures,
        )
    }

    @Test
    fun `ordinary construction and copies own caller lists`() {
        val args = mutableListOf(9L)
        val paths = mutableListOf("/tmp/a")
        val syscall = ProfileObservation.Syscall(correlation, ObservationSource.STRACE, "OPEN", args, paths)
        val uring = ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "OPENAT", paths)
        val connect = ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint, paths)
        assertNotSame(args, syscall.args)
        assertNotSame(paths, syscall.copy().paths)
        assertNotSame(paths, uring.copy().paths)
        assertNotSame(paths, connect.copy().paths)
        val replacement = listOf("/tmp/replacement")
        assertNotSame(replacement, syscall.copy(paths = replacement).paths)
        assertNotSame(replacement, uring.copy(paths = replacement).paths)
        assertNotSame(replacement, connect.copy(paths = replacement).paths)
    }

    @Test
    fun `explicit plain path replacement replaces evidence even when projected paths are equal`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "TID 7 EPERM")
        val resolution = PathResolution.Partial(listOf("/tmp/a"), listOf(failure))
        val syscall = ProfileObservation.Syscall(correlation, ObservationSource.USER_NOTIF, "OPEN").withPathResolution(resolution)
        val uring = ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "OPENAT").withPathResolution(resolution)
        val connect = ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint).withPathResolution(resolution)
        val copies = listOf(syscall.copy(paths = listOf("/tmp/a")), uring.copy(paths = listOf("/tmp/a")), connect.copy(paths = listOf("/tmp/a")))
        for (copy in copies) {
            assertIs<PathResolution.Resolved>(copy.pathResolution)
            assertTrue(copy.pathResolution.failures.isEmpty())
        }
        assertIs<PathResolution.Resolved>(syscall.copy(name = "STAT").pathResolution)
        assertIs<PathResolution.Resolved>(uring.copy(opcode = "STATX").pathResolution)
    }

    @Test
    fun `data class equality and collector deduplication retain distinct failure evidence`() {
        val reasons = listOf(PathResolutionFailure.Reason.VmReadDenied, PathResolutionFailure.Reason.Truncated)
        val observations = reasons.flatMap { reason ->
            val resolution = PathResolution.Failed(listOf(PathResolutionFailure(0, reason, "inspection")))
            listOf(
                ProfileObservation.Syscall(correlation, ObservationSource.USER_NOTIF, "OPEN").withPathResolution(resolution).copy(),
                ProfileObservation.IoUring(correlation, ObservationSource.EBPF, "OPENAT").withPathResolution(resolution).copy(),
                ProfileObservation.Connect(correlation, ObservationSource.EBPF, endpoint).withPathResolution(resolution).copy(),
            )
        }
        for (index in 0..2) assertNotEquals(observations[index], observations[index + 3])
        val copies = observations.map { observation ->
            when (observation) {
                is ProfileObservation.Syscall -> observation.copy()
                is ProfileObservation.IoUring -> observation.copy()
                is ProfileObservation.Connect -> observation.copy()
            }
        }
        assertEquals(observations, copies)
        assertEquals(observations.map { it.hashCode() }, copies.map { it.hashCode() })
        assertEquals(6, (observations + copies).toSet().size)
        val merged = ObservationMerger.merge(listOf(CollectorDrain(observations + copies)))
        assertEquals(observations, merged.observations)
        val coverage = ProfilingCoverage.infer(
            ProfileStrategy.USER_NOTIF,
            "compatibility",
            false,
            merged.observations,
            StackAttribution.SKIPPED,
            0,
            true,
            ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertEquals(PathResolutionQuality.MIXED, coverage.pathResolution)
        assertEquals(List(3) { reasons[0] } + List(3) { reasons[1] }, coverage.pathFailures.map { it.reason })
    }

    private fun <T : ProfileObservation> construct(
        type: KClass<T>,
        names: List<String>,
        optional: List<String>,
        values: Map<String, Any>,
    ): T = ObservationConstructorContract.construct(type.java, names, optional, values)
}
