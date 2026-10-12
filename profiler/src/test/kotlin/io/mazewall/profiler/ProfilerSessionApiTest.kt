package io.mazewall.profiler.api
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.api.PathResolution
import io.mazewall.profiler.api.PathResolutionFailure
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.SyscallEventState
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.compiler.StraceLogParser
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfilerSessionApiTest {
    @Test
    fun `public incomplete refusal recommends retaining diagnostics without an unavailable override`() {
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.USER_NOTIF)).use { profiler ->
            val result = profiler.snapshot()
            val failure = assertFailsWith<IncompleteProfileException> { result.toDedicatedWorkerPolicies() }
            assertFalse(failure.message.orEmpty().contains("allowIncomplete"))
            assertTrue(failure.message.orEmpty().contains("toProfileDocumentJson"))
            assertEquals(result.coverage, failure.coverage)
        }
    }

    @Test
    fun `recorded eBPF augmentation retains path failures in the final result and snapshot`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "Yama denied")
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(
            Tid(7),
            "OPEN",
            listOf(0L, 0L),
            PathResolution.Failed(listOf(failure)),
        )
        val observations = listOf(ProfileObservation.fromSyscallEvent(event))
        val raw = ProfilingResult("observed", BillOfBehavior(), emptyMap(), pathCoverage(observations), observations)
        val log = directory.resolve("recorded.log")
        java.nio.file.Files
            .writeString(log, "kind=uring tid=1 opcode=IORING_OP_OPENAT path=/tmp/sidecar\n")
        MazewallProfiler.open(ProfileOptions(ProfileStrategy.USER_NOTIF, captureStacks = false, ebpfEventLog = log)).use { profiler ->
            // Exercise the real final-result augmentation without installing a USER_NOTIF filter.
            val attach = MazewallProfiler::class.java.getDeclaredMethod("attachCoverage", ProfilingResult::class.java)
            attach.isAccessible = true
            val result = attach.invoke(profiler, raw) as ProfilingResult<*>
            assertEquals("observed", result.value)
            assertTrue(result.behavior.opens.contains("/tmp/sidecar"))
            assertTrue(result.coverage.warnings.any { it.contains("recorded eBPF") })
            assertFalse(result.coverage.complete)
            assertEquals(listOf(failure), result.coverage.pathFailures)
            assertEquals(listOf(failure), profiler.snapshot().coverage.pathFailures)
            assertEquals(result.coverage, profiler.snapshot().coverage)
        }
    }

    @Test
    fun `mutation audit augmentation retains path failures in the final public result`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9")
        val observation = ProfileObservation.fromSyscallEvent(
            SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "OPENAT", emptyList(), PathResolution.Failed(listOf(failure))),
        )
        val raw = ProfilingResult("observed", BillOfBehavior(), emptyMap(), pathCoverage(listOf(observation)), listOf(observation))
        val result = raw.withCoverageAugmentation(recordedLog = false, untrappedMutations = listOf("truncate(76)"))
        assertEquals("observed", result.value)
        assertEquals(listOf(observation), result.observations)
        assertTrue(result.coverage.warnings.any { it.contains("truncate(76)") })
        assertFalse(result.coverage.complete)
        assertEquals(listOf(failure), result.coverage.pathFailures)
    }

    @Test
    fun `policy rejection warning augmentation preserves the original typed path failures`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.Truncated, "no NUL")
        val observation = ProfileObservation.fromSyscallEvent(
            SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "OPEN", emptyList(), PathResolution.Failed(listOf(failure))),
        )
        val coverage = pathCoverage(listOf(observation))
        val behaviors = listOf(
            BillOfBehavior(connects = setOf(NetworkEndpoint("127.0.0.1", 80))),
            BillOfBehavior(execs = setOf("/bin/tool")),
            BillOfBehavior(ioUringOps = setOf("IORING_OP_SENDMSG")),
        )
        val rejected = behaviors.map { behavior ->
            assertFailsWith<IncompleteProfileException> { ProfilingResult(Unit, behavior, emptyMap(), coverage).toPolicy() }.coverage
        }
        assertEquals(List(3) { listOf(failure) }, rejected.map { it.pathFailures })
    }

    @Test
    fun `DSL rejection retains supplied diagnostics even when caller overrides completeness`() {
        val failure = PathResolutionFailure(0, PathResolutionFailure.Reason.Missing, "missing operand")
        val observation = ProfileObservation.fromSyscallEvent(
            SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "OPEN", emptyList(), PathResolution.Failed(listOf(failure))),
        )
        val original = pathCoverage(listOf(observation))
        val coverage = original.copy(integrity = original.integrity.copy(complete = true))
        val behaviors = listOf(
            BillOfBehavior(connects = setOf(NetworkEndpoint("127.0.0.1", 80))),
            BillOfBehavior(execs = setOf("/bin/tool")),
            BillOfBehavior(ioUringOps = setOf("IORING_OP_SENDMSG")),
        )
        val rejected = behaviors.map { behavior ->
            assertFailsWith<IncompleteProfileException> { ProfilingResult(Unit, behavior, emptyMap(), coverage).toDsl() }.coverage
        }
        assertEquals(List(3) { listOf(failure) }, rejected.map { it.pathFailures })
    }

    @Test
    fun `coverage retains immutable diagnostics when merging prior evidence`() {
        val denied = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "TID 7 Yama EPERM")
        val missing = PathResolutionFailure(1, PathResolutionFailure.Reason.Missing, "null operand")

        fun coverage(failure: PathResolutionFailure): ProfilingCoverage {
            val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(
                Tid(7),
                "RENAME",
                emptyList(),
                PathResolution.Failed(listOf(failure)),
            )
            return pathCoverage(listOf(ProfileObservation.fromSyscallEvent(event)))
        }
        val merged = coverage(missing).retainStricterPathResolution(coverage(denied))
        assertEquals(listOf(missing, denied), merged.pathFailures)
        assertEquals(merged.pathFailures, merged.copy(strategyReason = "copied").pathFailures)
        assertFailsWith<UnsupportedOperationException> { (merged.pathFailures as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (merged.warnings as MutableList).clear() }
    }

    @Test
    fun `typed path failures survive heap events observations and coverage`() {
        val denied = PathResolutionFailure(0, PathResolutionFailure.Reason.VmReadDenied, "TID 7 Yama EPERM")
        val missing = PathResolutionFailure(1, PathResolutionFailure.Reason.Missing, "null operand")
        val truncated = PathResolutionFailure(0, PathResolutionFailure.Reason.Truncated, "no NUL in 16 bytes")
        val cases = listOf(
            PathResolution.NotApplicable to PathResolutionQuality.NONE,
            PathResolution.Resolved(listOf("/tmp/a")) to PathResolutionQuality.RESOLVED,
            PathResolution.Partial(listOf("/tmp/a"), listOf(missing)) to PathResolutionQuality.FAILED,
            PathResolution.Failed(listOf(denied)) to PathResolutionQuality.FAILED,
            PathResolution.Failed(listOf(truncated)) to PathResolutionQuality.TRUNCATED,
            PathResolution.Failed(listOf(truncated, missing)) to PathResolutionQuality.MIXED,
        )
        for ((resolution, quality) in cases) {
            val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "RENAME", emptyList(), resolution)
            val observation = ProfileObservation.fromSyscallEvent(event)
            assertEquals(resolution, observation.pathResolution)
            assertEquals(resolution.paths, observation.paths)
            val coverage = pathCoverage(listOf(observation))
            assertEquals(quality, coverage.pathResolution)
            assertEquals(resolution.failures, coverage.pathFailures)
            assertEquals(resolution.failures.isEmpty(), coverage.complete)
            assertEquals(resolution.failures, coverage.copy(strategyReason = "copied").pathFailures)
        }
    }

    @Test
    fun `typed resolution is authoritative even for a long complete path`() {
        val resolution = PathResolution.Resolved(listOf("/" + "a".repeat(4096)))
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "OPEN", listOf(0L, 0L), resolution)
        val coverage = pathCoverage(listOf(ProfileObservation.fromSyscallEvent(event)))
        assertEquals(PathResolutionQuality.RESOLVED, coverage.pathResolution)
        assertTrue(coverage.complete)
    }

    @Test
    fun `path evidence snapshots caller lists and survives trace projection and observation copies`() {
        val paths = mutableListOf("/tmp/a")
        val failures = mutableListOf(PathResolutionFailure(1, PathResolutionFailure.Reason.DirFdUnresolved, "dirfd=9"))
        val resolution = PathResolution.Partial(paths, failures)
        val event = SyscallEvent.fromResolution<SyscallEventState.Resolved>(Tid(7), "RENAME", emptyList(), resolution)
        val trace = TraceEvent.fromSyscallEvent(event)
        val observation = assertIs<ProfileObservation.Syscall>(ProfileObservation.fromTraceEvent(trace))
        paths.clear()
        failures.clear()
        assertEquals(listOf("/tmp/a"), observation.paths)
        assertEquals(listOf(1), observation.pathResolution.failures.map { it.operandIndex })
        assertEquals(resolution, observation.copy(contextId = 99L).pathResolution)
        assertFailsWith<UnsupportedOperationException> { (resolution.paths as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (resolution.failures as MutableList).clear() }
    }

    private fun pathCoverage(observations: List<ProfileObservation>): ProfilingCoverage =
        ProfilingCoverage.infer(
        strategy = ProfileStrategy.USER_NOTIF,
        strategyReason = "typed evidence",
        processWide = false,
        observations = observations,
        stacks = StackAttribution.SKIPPED,
        droppedEvents = 0,
        drainComplete = true,
        environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
    )

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
        val bind = StraceLogParser.parseLine(
            """100 bind(3, {sa_family=AF_INET, sin_port=htons(80), sin_addr=inet_addr("127.0.0.1")}, 16) = 0""",
        )
        assertTrue(bind is ProfileObservation.Syscall)
        assertEquals("BIND", (bind as ProfileObservation.Syscall).name)
        val sendto = StraceLogParser.parseLine(
            """100 sendto(3, "payload", 7, 0, {sa_family=AF_INET, sin_port=htons(80), sin_addr=inet_addr("127.0.0.1")}, 16) = 7""",
        )
        assertTrue(sendto is ProfileObservation.Syscall)
        assertEquals("SENDTO", (sendto as ProfileObservation.Syscall).name)
        assertTrue((sendto as ProfileObservation.Syscall).paths.isEmpty())
        val rename = StraceLogParser.parseLine(
            """100 rename("/tmp/old", "/tmp/new") = 0""",
        ) as ProfileObservation.Syscall
        assertEquals(listOf("/tmp/old", "/tmp/new"), rename.paths)
        val v6 = StraceLogParser.parseLine(
            """100 connect(3, {sa_family=AF_INET6, sin6_port=htons(443), inet_pton(AF_INET6, "2001:db8::1", &sin6_addr)}, 28) = 0""",
        )
        assertTrue(v6 is ProfileObservation.Connect)
        assertEquals(NetworkEndpoint("2001:db8::1", 443), (v6 as ProfileObservation.Connect).endpoint)
    }

    @Test
    fun `partial rename path list is FAILED not RESOLVED`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "RENAME",
                    paths = listOf("/tmp/old"),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(PathResolutionQuality.FAILED, coverage.pathResolution)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `toPolicy rejects observed connect destinations`() {
        val bob = BillOfBehavior(connects = setOf(NetworkEndpoint("127.0.0.1", 80)))
        val complete = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(true, complete.complete)
        assertFailsWith<IncompleteProfileException> {
            bob.toPolicy(coverage = complete)
        }
    }

    @Test
    fun `io_uring OPENAT without flags is incomplete`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.IoUring(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.EBPF,
                    "IORING_OP_OPENAT",
                    listOf("/tmp/a"),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Available),
        )
        assertEquals(false, coverage.complete)
        assertTrue(coverage.warnings.contains("io_uring open access mode was not observed"))
    }

    @Test
    fun `descriptor-only fstat fchmod fchown do not fail path completeness`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.STRACE,
                    "FSTAT",
                    paths = emptyList(),
                ),
                ProfileObservation.Syscall(
                    ObservationCorrelation(2, Tid(1)),
                    ObservationSource.STRACE,
                    "FCHMOD",
                    paths = emptyList(),
                ),
                ProfileObservation.Syscall(
                    ObservationCorrelation(3, Tid(1)),
                    ObservationSource.STRACE,
                    "FCHOWN",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(PathResolutionQuality.NONE, coverage.pathResolution)
        assertEquals(true, coverage.complete)
    }

    @Test
    fun `unmapped syscall names make coverage incomplete`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.STRACE,
                    "RECVMSG",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(false, coverage.complete)
        assertTrue(coverage.warnings.any { it.contains("unmapped syscall") && it.contains("RECVMSG") })
        assertIs<ProfileEvidence.Incomplete>(coverage.evidence())
    }

    @Test
    fun `READLINK without a path is FAILED`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "READLINK",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(PathResolutionQuality.FAILED, coverage.pathResolution)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `OPENAT2 without flags is read-classified and incomplete`() {
        val corr = ObservationCorrelation(1, Tid(1))
        val readOnly =
            ProfileObservation.Syscall(
                corr,
                ObservationSource.USER_NOTIF,
                "OPENAT2",
                paths = listOf("/tmp/ro"),
                openFlags = 0L,
            )
        val unknown =
            ProfileObservation.Syscall(
                corr,
                ObservationSource.USER_NOTIF,
                "OPENAT2",
                paths = listOf("/tmp/x"),
                openFlags = null,
            )
        val readBob = BobCompiler.compileObservations(listOf(readOnly))
        assertEquals(setOf("/tmp/ro"), readBob.opens)
        assertTrue(readBob.fsWritePaths.isEmpty())
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(unknown),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `retainStricterPathResolution keeps FAILED over NONE`() {
        val failed = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "raw",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "OPENAT",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        val rebuilt = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "synthetic",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "OPENAT",
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = failed.droppedEvents,
            drainComplete = failed.drainComplete,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        val kept = rebuilt.retainStricterPathResolution(failed)
        assertEquals(PathResolutionQuality.FAILED, kept.pathResolution)
        assertEquals(false, kept.complete)
    }

    @Test
    fun `toPolicy without coverage is incomplete`() {
        val bob = BillOfBehavior(syscalls = setOf(Syscall.OPENAT))
        assertFailsWith<IncompleteProfileException> { bob.toPolicy() }
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
        val policy = bob.toPolicyAllowingIncomplete(Policy.PURE_COMPUTE_UNSAFE, coverage = coverage)
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
                openFlags = 0,
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
    fun `AUTO never selects STRACE`() {
        for (load in listOf(EbpfLoad.Available, EbpfLoad.UserNamespaceRoot, EbpfLoad.Denied("x"))) {
            val (resolved, _) = MazewallProfiler.resolve(ProfileStrategy.AUTO, load)
            assertEquals(ProfileStrategy.USER_NOTIF, resolved)
            assertTrue(resolved != ProfileStrategy.STRACE)
        }
    }

    @Test
    fun `EBPF strategy fails closed even when capabilities look available`() {
        var workloadEntered = false
        MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.EBPF)).use { session ->
            val ex = assertFailsWith<IncompleteProfileException> {
                session.profile {
                    workloadEntered = true
                    "nope"
                }
            }
            assertEquals(ProfileStrategy.EBPF, ex.coverage.strategy)
            assertFalse(workloadEntered, "Workload must NOT be entered if collector fails to start")
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
        assertTrue(!bob.fsWritePaths.contains("/tmp/u"))
        assertTrue(bob.syscalls.contains(Syscall.CONNECT))
        val link = BobCompiler.compileObservations(
            listOf(
                ProfileObservation.IoUring(
                    corr,
                    ObservationSource.EBPF,
                    "IORING_OP_LINKAT",
                    listOf("/tmp/old", "/tmp/new"),
                ),
            ),
        )
        assertTrue(link.fsWritePaths.contains("/tmp/old"))
        assertTrue(link.fsWritePaths.contains("/tmp/new"))
        val symlink = BobCompiler.compileObservations(
            listOf(
                ProfileObservation.IoUring(
                    corr,
                    ObservationSource.EBPF,
                    "IORING_OP_SYMLINKAT",
                    listOf("/tmp/target", "/tmp/link"),
                ),
            ),
        )
        assertTrue(symlink.fsWritePaths.contains("/tmp/target"))
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
            session.close()
            assertFailsWith<IllegalStateException> {
                session.profile { 1 }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.STRACE))
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
        assertEquals(IoUringVisibility.UNSEEN, blocked.ioUring)
        assertEquals(false, blocked.complete)
    }

    @Test
    fun `ebpf probe reads synthetic proc files`() {
        val dir = java.nio.file.Files
            .createTempDirectory("ebpf-probe")
        val status = dir.resolve("status")
        java.nio.file.Files.writeString(
            status,
            "Uid:\t0\t0\t0\t0\nCapEff:\t0000008000000000\n",
        )
        val uidMap = dir.resolve("uid_map")
        java.nio.file.Files
            .writeString(uidMap, "0 100000 65536\n")
        val nsA = dir.resolve("nsA")
        val nsB = dir.resolve("nsB")
        java.nio.file.Files
            .writeString(nsA, "user:[1]")
        java.nio.file.Files
            .writeString(nsB, "user:[2]")
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
    fun `unused MazewallProfiler snapshot is incomplete`() {
        MazewallProfiler.open().use { session ->
            val snap = session.snapshot()
            assertEquals(false, snap.coverage.complete)
            assertEquals(false, snap.coverage.drainComplete)
            assertFailsWith<IncompleteProfileException> { snap.toPolicy() }
        }
    }

    @Test
    fun `lowercase io_uring syscall names are still BLIND`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "io_uring_setup",
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(IoUringVisibility.BLIND, coverage.ioUring)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `max-length paths are TRUNCATED`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "OPENAT",
                    paths = listOf("x".repeat(4096)),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(PathResolutionQuality.TRUNCATED, coverage.pathResolution)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `uring write without path is FAILED`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.EBPF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.IoUring(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.EBPF,
                    "IORING_OP_WRITE",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Available),
        )
        assertEquals(IoUringVisibility.OBSERVED, coverage.ioUring)
        assertEquals(PathResolutionQuality.FAILED, coverage.pathResolution)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `legacy three-arg ProfilingResult is incomplete`() {
        val result = ProfilingResult(Unit, BillOfBehavior(), emptyMap())
        assertEquals(false, result.coverage.complete)
        assertFailsWith<IncompleteProfileException> { result.toPolicy() }
    }

    @Test
    fun `HYBRID_NO_URING without disable evidence is not DISABLED_FOR_HYBRID`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.HYBRID_NO_URING,
            strategyReason = "t",
            processWide = false,
            observations = emptyList(),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x"), ioUringDisabled = null),
        )
        assertEquals(IoUringVisibility.UNSEEN, coverage.ioUring)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `unresolved path-bearing events mark coverage FAILED`() {
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "t",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.USER_NOTIF,
                    "OPENAT",
                    paths = emptyList(),
                ),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("t", EbpfLoad.Denied("x")),
        )
        assertEquals(PathResolutionQuality.FAILED, coverage.pathResolution)
        assertEquals(false, coverage.complete)
    }

    @Test
    fun `portless IPv6 endpoints survive JSON`() {
        val original = BillOfBehavior(connects = setOf(NetworkEndpoint("2001:db8::1", null)))
        val parsed = BillOfBehavior.fromJson(original.toJson())
        assertEquals(original.connects, parsed.connects)
    }

    @Test
    fun `uring write opcodes go to fsWritePaths`() {
        val bob = BobCompiler.compileObservations(
            listOf(
                ProfileObservation.IoUring(
                    ObservationCorrelation(1, Tid(1)),
                    ObservationSource.EBPF,
                    "IORING_OP_WRITE",
                    listOf("/tmp/w"),
                ),
            ),
        )
        assertEquals(setOf("/tmp/w"), bob.fsWritePaths)
        assertTrue(bob.opens.isEmpty())
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

    @Test
    fun `unparsed CONNECT syscall alongside parsed Connect marks coverage incomplete`() {
        val corr = ObservationCorrelation(1, Tid(1))
        val mixedCoverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test",
            processWide = false,
            observations = listOf(
                ProfileObservation.Connect(corr, ObservationSource.USER_NOTIF, NetworkEndpoint("1.2.3.4", 80)),
                ProfileObservation.Syscall(corr, ObservationSource.USER_NOTIF, "CONNECT"),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertFalse(mixedCoverage.complete, "A leftover unparsed CONNECT syscall must mark coverage incomplete")
        assertTrue(mixedCoverage.warnings.any { it.contains("CONNECT") })

        val onlyParsedCoverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test",
            processWide = false,
            observations = listOf(
                ProfileObservation.Connect(corr, ObservationSource.USER_NOTIF, NetworkEndpoint("1.2.3.4", 80)),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertTrue(onlyParsedCoverage.complete, "Fully parsed connects should be complete")

        val onlyUnparsedCoverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "test",
            processWide = false,
            observations = listOf(
                ProfileObservation.Syscall(corr, ObservationSource.USER_NOTIF, "CONNECT"),
            ),
            stacks = StackAttribution.SKIPPED,
            droppedEvents = 0,
            drainComplete = true,
            environment = ProfileEnvironment("test", EbpfLoad.Denied("test")),
        )
        assertFalse(onlyUnparsedCoverage.complete)
        assertTrue(onlyUnparsedCoverage.warnings.contains("CONNECT was observed without a parsed destination"))
    }

    private fun syntheticProbe(
        inInitNs: Boolean,
        capEff: Long,
        euid: Int,
    ): EbpfLoad {
        return when {
            inInitNs && (EbpfCapability.hasCap(capEff, 39) || EbpfCapability.hasCap(capEff, 21)) ->
                EbpfLoad.Available
            !inInitNs && euid == 0 -> EbpfLoad.UserNamespaceRoot
            !inInitNs -> EbpfLoad.Denied("not in the initial user namespace")
            else -> EbpfLoad.Denied("missing caps")
        }
    }
}
