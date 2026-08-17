package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.compiler.StraceLogParser
import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfilerSessionApiTest {

    @Test
    fun `bob compiler is the only path from strace lines and trace events`() {
        val traceEvents = listOf(
            TraceEvent(1, "OPENAT", longArrayOf(0, 0, 0), listOf("/etc/hostname")),
            TraceEvent(1, "CONNECT", longArrayOf(), emptyList()),
        )
        val straceLog = """
            100 openat(AT_FDCWD, "/etc/hostname", O_RDONLY) = 3
            100 connect(3, {sa_family=AF_INET, sin_port=htons(80), sin_addr=inet_addr("127.0.0.1")}, 16) = 0
        """.trimIndent()

        val fromEvents = BobCompiler.compile(traceEvents)
        val fromStrace = BobCompiler.compileObservations(StraceLogParser.parse(straceLog))

        assertTrue(fromEvents.opens.contains("/etc/hostname"))
        assertTrue(fromStrace.opens.contains("/etc/hostname"))
        assertTrue(fromEvents.syscalls.contains(Syscall.OPENAT))
        assertTrue(fromStrace.syscalls.contains(Syscall.OPENAT))
        assertEquals(setOf(NetworkEndpoint("127.0.0.1", 80)), fromStrace.connects)
        assertTrue(fromEvents.syscalls.contains(Syscall.CONNECT))
    }

    @Test
    fun `io_uring syscalls without IoUring observations are BLIND and fail-closed`() {
        val observations = listOf(
            ProfileObservation.Syscall(
                ObservationCorrelation(1, Tid(1)),
                ObservationSource.USER_NOTIF,
                "IO_URING_ENTER",
            ),
        )
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test",
            processWide = false,
            observations = observations,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("unit")),
        )
        assertEquals(IoUringVisibility.BLIND, coverage.ioUring)
        assertEquals(false, coverage.complete)
        val bob = BobCompiler.compileObservations(observations)
        assertFailsWith<IncompleteProfileException> {
            bob.toPolicy(Policy.PURE_COMPUTE_UNSAFE, coverage = coverage)
        }
        val policy = bob.toPolicy(Policy.PURE_COMPUTE_UNSAFE, coverage = coverage, allowIncomplete = true)
        assertTrue(policy.isSyscallAllowed(Syscall.IO_URING_ENTER))
    }

    @Test
    fun `hybrid without uring syscalls is DISABLED_FOR_HYBRID and complete`() {
        val observations = listOf(
            ProfileObservation.Syscall(
                ObservationCorrelation(1, Tid(1)),
                ObservationSource.USER_NOTIF,
                "OPENAT",
                paths = listOf("/tmp/x"),
            ),
        )
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.HYBRID_NO_URING,
            strategyReason = "test",
            processWide = false,
            observations = observations,
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("unit"), ioUringDisabled = true),
        )
        assertEquals(IoUringVisibility.DISABLED_FOR_HYBRID, coverage.ioUring)
        assertEquals(true, coverage.complete)
    }

    @Test
    fun `AUTO never silently selects unimplemented eBPF`() {
        val (resolved, reason) = MazewallProfiler.resolve(ProfileStrategy.AUTO, EbpfLoad.Available)
        assertEquals(ProfileStrategy.USER_NOTIF, resolved)
        assertTrue(reason.contains("USER_NOTIF"))
    }

    @Test
    fun `EBPF strategy fails closed even when capabilities look available`() {
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.EBPF)).use { session ->
            val ex = assertFailsWith<IncompleteProfileException> {
                session.profile { "nope" }
            }
            assertEquals(ProfileStrategy.EBPF, ex.coverage.strategy)
        }
    }

    @Test
    fun `ebpf probe distinguishes user-namespace root`() {
        assertEquals(EbpfLoad.Available, syntheticProbe(inInitNs = true, capEff = 1L shl 39, euid = 0))
        assertEquals(EbpfLoad.UserNamespaceRoot, syntheticProbe(inInitNs = false, capEff = 1L shl 39, euid = 0))
        assertTrue(syntheticProbe(inInitNs = true, capEff = 0L, euid = 1000) is EbpfLoad.Denied)
    }

    @Test
    fun `cap bitmask and euid parse from proc status`() {
        val status = """
            Name:	java
            Uid:	1000	1000	1000	1000
            CapEff:	0000000000000000
        """.trimIndent()
        assertEquals(1000, EbpfCapability.parseEuid(status))
        assertEquals(0L, EbpfCapability.parseCapEff(status))
        assertTrue(EbpfCapability.hasCap(1L shl 21, 21))
        assertTrue(EbpfCapability.parseInitUserNamespace("0 0 4294967295"))
        assertTrue(!EbpfCapability.parseInitUserNamespace("0 100000 65536"))
    }

    @Test
    fun `compiler records io_uring and connect observations`() {
        val corr = ObservationCorrelation(1, Tid(1))
        val bob = BobCompiler.compileObservations(
            listOf(
                ProfileObservation.IoUring(corr, ObservationSource.EBPF, "IORING_OP_OPENAT", listOf("/tmp/u")),
                ProfileObservation.Connect(corr, ObservationSource.STRACE, NetworkEndpoint("::1", 443)),
            ),
        )
        assertEquals(setOf("IORING_OP_OPENAT"), bob.ioUringOps)
        assertEquals(setOf(NetworkEndpoint("::1", 443)), bob.connects)
        assertTrue(bob.opens.contains("/tmp/u"))
        assertTrue(bob.syscalls.contains(Syscall.CONNECT))
        val merged = bob + BillOfBehavior(connects = setOf(NetworkEndpoint("10.0.0.1", 9)))
        assertEquals(2, merged.connects.size)
        val json = merged.toJson()
        val parsed = BillOfBehavior.fromJson(json)
        assertTrue(parsed.connects.any { it.port == 443 })
        assertTrue(parsed.ioUringOps.contains("IORING_OP_OPENAT"))
    }

    @Test
    fun `strace parser ignores noise and extracts write flags`() {
        assertEquals(null, StraceLogParser.parseLine(""))
        assertEquals(null, StraceLogParser.parseLine("+++ exited with 0 +++"))
        val write = StraceLogParser.parseLine("""99 open("/tmp/w", O_WRONLY|O_CREAT, 0666) = 3""")
        val bob = BobCompiler.compileObservations(listOf(write!!))
        assertTrue(bob.fsWritePaths.contains("/tmp/w"))
    }

    @Test
    fun `session lifecycle rejects closed use and wrong entry points`() {
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.AUTO)).use { session ->
            session.snapshot()
            assertFailsWith<IllegalArgumentException> {
                session.profile(TraceableWorkload::class.java)
            }
            session.close()
            assertFailsWith<IllegalStateException> {
                session.profile { 1 }
            }
        }
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.STRACE)).use { session ->
            assertFailsWith<IllegalArgumentException> {
                session.profile { "lambda" }
            }
        }
    }

    @Test
    fun `resolve records eBPF denial reasons`() {
        val ns = MazewallProfiler.resolve(ProfileStrategy.EBPF, EbpfLoad.UserNamespaceRoot)
        assertEquals(ProfileStrategy.EBPF, ns.first)
        assertTrue(ns.second.contains("user namespace"))
        val denied = MazewallProfiler.resolve(ProfileStrategy.EBPF, EbpfLoad.Denied("no caps"))
        assertTrue(denied.second.contains("no caps"))
        val autoNs = MazewallProfiler.resolve(ProfileStrategy.AUTO, EbpfLoad.UserNamespaceRoot)
        assertEquals(ProfileStrategy.USER_NOTIF, autoNs.first)
        val hybrid = MazewallProfiler.resolve(ProfileStrategy.HYBRID_NO_URING, EbpfLoad.Denied("x"))
        assertEquals(ProfileStrategy.HYBRID_NO_URING, hybrid.first)
    }

    @Test
    fun `coverage OBSERVED and BLOCKED`() {
        val corr = ObservationCorrelation(1, Tid(1))
        val observed = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "t",
            processWide = true,
            observations = listOf(ProfileObservation.IoUring(corr, ObservationSource.EBPF, "OPENAT")),
            stacks = StackAttribution.CAPTURED,
            droppedEvents = 1,
            drainComplete = false,
            environment = ProfileEnvironment("t", EbpfLoad.Available),
        )
        assertEquals(IoUringVisibility.OBSERVED, observed.ioUring)
        assertEquals(false, observed.complete)
        val blocked = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "t",
            processWide = false,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(IoUringVisibility.BLOCKED, blocked.ioUring)
    }

    @Test
    fun `ebpf probe reads synthetic proc files`() {
        val dir = java.nio.file.Files.createTempDirectory("ebpf-probe")
        val status = dir.resolve("status")
        java.nio.file.Files.writeString(
            status,
            "Uid:\t0\t0\t0\t0\nCapEff:\t0000008000000000\n",
        )
        val uidMap = dir.resolve("uid_map")
        java.nio.file.Files.writeString(uidMap, "0 100000 65536\n")
        val nsA = dir.resolve("nsA")
        val nsB = dir.resolve("nsB")
        java.nio.file.Files.writeString(nsA, "user:[1]")
        java.nio.file.Files.writeString(nsB, "user:[2]")
        val load = EbpfCapability.probe(
            selfNsUser = nsA,
            initNsUser = nsB,
            status = status,
            uidMap = uidMap,
            euid = 0,
        )
        assertEquals(EbpfLoad.UserNamespaceRoot, load)
        assertEquals(null, EbpfCapability.parseCapEff("Name: x"))
        assertEquals(null, EbpfCapability.parseEuid("Name: x"))
        assertEquals(false, EbpfCapability.parseInitUserNamespace("bogus"))
    }

    @Test
    fun `result toPolicy uses coverage`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "IO_URING_SETUP",
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        val result = ProfilingResult(Unit, BillOfBehavior(syscalls = setOf(Syscall.IO_URING_SETUP)), emptyMap(), coverage)
        assertFailsWith<IncompleteProfileException> { result.toPolicy() }
    }

    @Test
    fun `inferObservationsFromBob uses events when present`() {
        val event = TraceEvent(2, "OPENAT", longArrayOf(0, 0, 0), listOf("/x"))
        val obs = MazewallProfiler.inferObservationsFromBob(BillOfBehavior(), setOf(event))
        assertTrue(obs.single() is ProfileObservation.Syscall)
        val fromBob = MazewallProfiler.inferObservationsFromBob(
            BillOfBehavior(syscalls = setOf(Syscall.GETPID), ioUringOps = setOf("NOP")),
            emptySet(),
        )
        assertTrue(fromBob.any { it is ProfileObservation.IoUring })
    }

    private fun syntheticProbe(inInitNs: Boolean, capEff: Long, euid: Int): EbpfLoad {
        return when {
            inInitNs && (EbpfCapability.hasCap(capEff, 39) || EbpfCapability.hasCap(capEff, 21)) ->
                EbpfLoad.Available
            !inInitNs && euid == 0 -> EbpfLoad.UserNamespaceRoot
            !inInitNs -> EbpfLoad.Denied("not in the initial user namespace")
            else -> EbpfLoad.Denied("missing caps")
        }
    }
}
