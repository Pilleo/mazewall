package io.mazewall.profiler.collector

import io.mazewall.core.Tid
import io.mazewall.profiler.EbpfLoad
import io.mazewall.profiler.IncompleteProfileException
import io.mazewall.profiler.IoUringVisibility
import io.mazewall.profiler.MazewallProfiler
import io.mazewall.profiler.NetworkEndpoint
import io.mazewall.profiler.ObservationCorrelation
import io.mazewall.profiler.ObservationSource
import io.mazewall.profiler.ProfileObservation
import io.mazewall.profiler.ProfileOptions
import io.mazewall.profiler.ProfileStrategy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectorHybridTest {

    @Test
    fun `parser reads uring syscall and connect lines`() {
        val log = """
            # comment
            kind=uring tid=8 tgid=7 ktime=1 opcode=IORING_OP_OPENAT path=/tmp/a
            kind=syscall tid=8 name=io_uring_enter
            kind=connect tid=8 host=127.0.0.1 port=443
        """.trimIndent()
        val obs = EbpfEventParser.parse(log)
        assertEquals(3, obs.observations.size)
        assertEquals(0, obs.droppedLines)
        assertTrue(obs.observations[0] is ProfileObservation.IoUring)
        assertEquals("IO_URING_ENTER", (obs.observations[1] as ProfileObservation.Syscall).name)
        assertEquals(NetworkEndpoint("127.0.0.1", 443), (obs.observations[2] as ProfileObservation.Connect).endpoint)
    }

    @Test
    fun `parser keeps spaces inside quoted path values`() {
        val obs =
            EbpfEventParser.parseLine(
                """kind=syscall tid=3 name=OPENAT path="/tmp/My File" """,
            ) as ProfileObservation.Syscall
        assertEquals(listOf("/tmp/My File"), obs.paths)
    }

    @Test
    fun `malformed eBPF lines increment droppedLines`() {
        val parsed =
            EbpfEventParser.parse(
                """
                kind=uring tid=1 opcode=IORING_OP_OPENAT path=/tmp/a
                kind=garbage tid=1
                not-a-field-line
                """.trimIndent(),
            )
        assertEquals(1, parsed.observations.size)
        assertEquals(2, parsed.droppedLines)
    }

    @Test
    fun `merger prefers OBSERVED and deduplicates`() {
        val corr = ObservationCorrelation(1, Tid(1), 5)
        val uring = ProfileObservation.IoUring(corr, ObservationSource.EBPF, "OPENAT", listOf("/x"))
        val merged = ObservationMerger.merge(
            listOf(
                CollectorDrain(listOf(uring), ioUring = IoUringVisibility.UNSEEN),
                CollectorDrain(listOf(uring), droppedEvents = 2, ioUring = IoUringVisibility.OBSERVED),
            ),
        )
        assertEquals(1, merged.observations.size)
        assertEquals(2, merged.droppedEvents)
        assertEquals(IoUringVisibility.OBSERVED, merged.ioUring)
    }

    @Test
    fun `recorded eBPF log compiles through the session without live attach`() {
        val log = Files.createTempFile("ebpf", ".log")
        Files.writeString(log, "kind=uring tid=1 opcode=IORING_OP_OPENAT path=/tmp/sidecar\n")
        MazewallProfiler.open(
            ProfileOptions(strategy = ProfileStrategy.EBPF, ebpfEventLog = log),
        ).use { session ->
            val result = session.profile { "ok" }
            assertEquals("ok", result.value)
            assertTrue(result.behavior.opens.contains("/tmp/sidecar"))
            assertEquals(IoUringVisibility.OBSERVED, result.coverage.ioUring)
            // Recorded eBPF logs have drainComplete=false, so coverage is incomplete
            assertEquals(false, result.coverage.complete)
            assertFailsWith<IncompleteProfileException> { result.toPolicy() }
        }
    }

    @Test
    fun `live eBPF attach without a log fails closed`() {
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.EBPF)).use { session ->
            assertFailsWith<IncompleteProfileException> {
                session.profile { "nope" }
            }
        }
    }

    @Test
    fun `strace collector compiles a recorded log and marks uring BLIND`() {
        val log = Files.createTempFile("strace", ".log")
        Files.writeString(
            log,
            """
            1 openat(AT_FDCWD, "/etc/hostname", O_RDONLY) = 3
            1 io_uring_enter(3, 1, 1, 0, NULL, 0) = 1
            """.trimIndent(),
        )
        val collector = StraceCollector(recordedLog = log)
        collector.start()
        val drain = collector.use { it.drain() }
        assertTrue(drain.observations.any { it.paths.contains("/etc/hostname") })
        assertEquals(IoUringVisibility.BLIND, drain.ioUring)
    }

    @Test
    fun `strace collector requires a source`() {
        assertFailsWith<IllegalArgumentException> {
            StraceCollector().start()
        }
    }

    @Test
    fun `missing log file fails closed`() {
        val collector = EbpfCollector(EbpfLoad.Available, recordedLog = java.nio.file.Path.of("/no/such/ebpf.log"))
        assertFailsWith<IncompleteProfileException> { collector.start() }
    }
}
