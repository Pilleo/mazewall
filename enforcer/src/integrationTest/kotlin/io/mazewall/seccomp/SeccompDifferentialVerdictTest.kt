package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.CompiledSandbox
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.compile
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential kernel-vs-simulator verification (issue-20260823-171500).
 *
 * For each policy archetype: compile, predict every probe verdict with the shared platform oracle
 * ([BpfSimulator]), install on a real thread, then assert the kernel's observable behavior matches.
 * Includes the post-install liveness assertion that would have caught issue-20260823-140500
 * deterministically.
 *
 * KILL_* verdicts are verified out-of-process via [io.mazewall.enforcer.api.IsolatedProcessTester]
 * (issue-20260823-172000): this class is @NeedsFreshJvm because its in-JVM archetypes permanently
 * restrict worker threads.
 */
@NeedsFreshJvm
class SeccompDifferentialVerdictTest : BaseIntegrationTest() {
    init {
        // Opt in to runtime self-verification: these policies are probe-safe by design.
        System.setProperty("io.mazewall.selfVerify", "true")
    }

    private val arch = io.mazewall.platform.syscall.Arch
        .current()

    @Test
    fun `x32 aliases and legacy range are killed by the installed kernel filter`(
        @TempDir directory: Path,
    ) {
        assumeTrue(arch.audit == Arch.AUDIT_ARCH_X86_64)
        for (nr in listOf(0x40000027, 512, 547)) {
            // A surviving baseline excludes an inherited filter as the source of SIGSYS.
            IsolatedProcessTester.runIsolatedTest(SeccompAbiProbeChild::class.java.name, nr.toString(), "baseline")
            val ready = directory.resolve("installed-$nr")
            val failure = assertFailsWith<IllegalStateException> {
                IsolatedProcessTester.runIsolatedTest(SeccompAbiProbeChild::class.java.name, nr.toString(), "guarded", ready.toString())
            }
            assertTrue(failure.message.orEmpty().contains("exit code 159."), "Expected SIGSYS for nr=$nr: ${failure.message}")
            assertEquals("installed", Files.readString(ready), "The child must finish installation before the fatal probe")
        }
    }

    private fun assertKernelVerdictMatchesSimulator(
        program: List<BpfInstruction>,
        probeNrs: List<Int>,
        kernelOutcome: (nr: Int) -> Boolean,
    ) {
        val simulatedVerdicts =
            probeNrs.associateWith { nr ->
                BpfSimulator.simulate(program, nr, arch)
                    ?: throw AssertionError(
                        "BPF simulator produced no verdict for nr=$nr on arch=${arch.name}\n" +
                            "BPF program:\n${BpfProgram.unverified(program).disassemble()}",
                    )
            }
        val blockedNrs = probeNrs.filter { nr -> simulatedVerdicts.getValue(nr) != NativeConstants.SECCOMP_RET_ALLOW }
        val allowedNrs = probeNrs.filter { nr -> simulatedVerdicts.getValue(nr) == NativeConstants.SECCOMP_RET_ALLOW }
        // Kernel-vs-simulator consensus: every simulator-denied probe is actually denied by the
        // kernel; every simulator-allowed probe actually succeeds.
        for (nr in blockedNrs) {
            assertTrue(kernelOutcome(nr), "Kernel must deny nr=$nr (simulator predicted denial)")
        }
        for (nr in allowedNrs.filter { it != 0 }) { // nr 0 is `read`: cannot be invoked benignly here
            assertFalse(kernelOutcome(nr), "Kernel must allow nr=$nr (simulator predicted ALLOW)")
        }
    }

    @Test
    fun `missing simulator verdict fails differential check with probe and disassembly`() {
        val noRetProgram = listOf(BpfInstruction.Ld(0))
        val probeNr = Syscall.UMASK.numberFor(arch)
        var kernelInvoked = false
        val failure =
            assertFailsWith<AssertionError> {
                assertKernelVerdictMatchesSimulator(
                    program = noRetProgram,
                    probeNrs = listOf(probeNr),
                ) {
                    kernelInvoked = true
                    false
                }
            }
        assertFalse(kernelInvoked, "Kernel oracle must not run when simulator produces no verdict")
        val message = failure.message.orEmpty()
        assertTrue(message.contains("nr=$probeNr"), "Expected probe nr in failure: $message")
        assertTrue(message.contains("arch=${arch.name}"), "Expected arch in failure: $message")
        assertTrue(message.contains("0000: ld [0]"), "Expected BPF disassembly in failure: $message")

        // Benign allow/deny controls verify both partition branches on valid single-RET filters.
        val allowProgram = listOf(BpfInstruction.Ret(SeccompAction.ACT_ALLOW))
        assertKernelVerdictMatchesSimulator(allowProgram, listOf(probeNr)) { false }

        val denyProgram = listOf(BpfInstruction.Ret(SeccompAction.ACT_ERRNO(NativeConstants.EPERM)))
        assertKernelVerdictMatchesSimulator(denyProgram, listOf(probeNr)) { true }
    }

    /**
     * Invokes an arbitrary syscall and reports whether the installed filter denied it.
     * A filter decision surfaces as a raw error result; anything else (success, ENOSYS from the
     * kernel itself) counts as "not denied by seccomp".
     */
    private fun kernelDenied(nr: Int): Boolean {
        return kernelDenied(nr, LongArray(6))
    }

    private fun kernelDenied(
        nr: Int,
        args: LongArray,
    ): Boolean {
        val res = io.mazewall.platform.native.LinuxNative.raw.syscall(
            nr.toLong(),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(0) { 0 }),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(1) { 0 }),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(2) { 0 }),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(3) { 0 }),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(4) { 0 }),
            io.mazewall.platform.syscall.NativeArg
                .LongArg(args.getOrElse(5) { 0 }),
        )
        return res is io.mazewall.platform.native.LinuxNative.SyscallResult.Error &&
            res.errno == NativeConstants.EPERM
    }

    @Test
    fun `argument-inspection fuzz agrees with kernel for prctl and mprotect`() {
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val compiled = policy.compile(arch)
        val random = Random(0x4d415a45)

        ContainedExecutors.installOnCurrentThread(policy)

        repeat(64) { sample ->
            val highWord = random.nextLong() shl 32
            val prctlOption = highWord or if (random.nextBoolean()) 15L else 25L
            val prctlArgs = longArrayOf(prctlOption)
            val prctlExpectedDenied = BpfSimulator.simulate(
                compiled.compiledFilters,
                arch.prctl,
                arch.audit,
                prctlArgs,
            ) != NativeConstants.SECCOMP_RET_ALLOW
            assertEquals(
                prctlExpectedDenied,
                kernelDenied(arch.prctl, prctlArgs),
                "seed=0x4d415a45 sample=$sample prctlOption=0x${prctlOption.toULong().toString(16)}",
            )

            val protections = highWord or if (random.nextBoolean()) 0x1L else 0x5L
            val mprotectArgs = longArrayOf(0L, 4096L, protections)
            val mprotectExpectedDenied = BpfSimulator.simulate(
                compiled.compiledFilters,
                arch.mprotect,
                arch.audit,
                mprotectArgs,
            ) != NativeConstants.SECCOMP_RET_ALLOW
            assertEquals(
                mprotectExpectedDenied,
                kernelDenied(arch.mprotect, mprotectArgs),
                "seed=0x4d415a45 sample=$sample protections=0x${protections.toULong().toString(16)}",
            )
        }
    }

    @Test
    fun `equals-any 64-bit argument fuzz agrees with kernel`() {
        val allowed = listOf(0x1122334455667788L, -0x778899aabbccddefL)
        val policy = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val builder = BpfProgram.builder().checkArch(arch).loadSyscallNr()
        BpfFilter.emitInspections(
            builder,
            listOf(
                SyscallInspection(
                    syscallNumber = Syscall.GETPPID.numberFor(arch),
                    argument = SyscallArgument.FIRST,
                    check = ArgCheck.EqualsAny(allowed),
                    ifMatched = SeccompAction.ACT_ALLOW,
                    ifNotMatched = SeccompAction.ACT_ERRNO(NativeConstants.EPERM),
                ),
            ),
            profilingMode = false,
            handledNrs = mutableSetOf(),
        )
        val program = BpfStaticVerifier.verify(builder.allow().build())
        PureJavaBpfEngine.install(CompiledSandbox(policy.definition, program))

        val random = Random(0x455155414c53414e)
        repeat(64) { sample ->
            val argument = if (random.nextBoolean()) allowed.random(random) else random.nextLong()
            val args = longArrayOf(argument)
            val expectedDenied = BpfSimulator.simulate(
                program.instructions,
                Syscall.GETPPID.numberFor(arch),
                arch.audit,
                args,
            ) != NativeConstants.SECCOMP_RET_ALLOW
            assertEquals(
                expectedDenied,
                kernelDenied(Syscall.GETPPID.numberFor(arch), args),
                "seed=0x455155414c53414e sample=$sample argument=0x${argument.toULong().toString(16)}",
            )
        }
    }

    @Test
    fun `blacklist errno archetype - kernel matches simulator and liveness holds`() {
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.CONNECT)
            .build()
        val compiled = policy.compile(arch)
        val probes = SyscallProbeMatrix.structural(arch) +
            SyscallProbeMatrix.matched(
            arch,
            listOf(Syscall.CONNECT.numberFor(arch)),
        )

        ContainedExecutors.installOnCurrentThread(policy)

        // Liveness: benign syscalls must keep working after installation.
        assertTrue(ProcessHandle.current().pid() > 0)

        // Simulator predictions over the whole matrix.
        assertEquals(
            NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
            BpfSimulator.simulate(compiled.compiledFilters, Syscall.CONNECT.numberFor(arch), arch),
        )
        for (probe in SyscallProbeMatrix.structural(arch)) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(compiled.compiledFilters, probe.nr, arch),
                "Structural probe ${probe.label} must be allowed",
            )
        }

        // Kernel behavior vs simulator for the matched NR (connect -> EPERM).
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 1), 100)
            }
            error("connect must be denied by the installed filter")
        } catch (e: java.net.SocketException) {
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("Operation not permitted", ignoreCase = true) || msg.contains("Permission denied", ignoreCase = true),
                "connect must fail with EPERM-class error, got: $msg",
            )
        }

        assertKernelVerdictMatchesSimulator(compiled.compiledFilters, probes.map { it.nr }) { nr ->
            kernelDenied(nr)
        }
    }

    @Test
    fun `bst fast path archetype - blocked set enforced, liveness holds`() {
        // default ALLOW + <=32 actions => BST codegen path. Never deny-by-default here: the JVM
        // lazily loads classes for every syscall imaginable and would starve (ClassFormatError).
        val blocked = listOf(Syscall.CONNECT, Syscall.UMASK, Syscall.GETPPID, Syscall.GETEUID)
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .addAction(SeccompAction.ACT_ERRNO(NativeConstants.EPERM), *blocked.toTypedArray())
            .build()
        val compiled = policy.compile(arch)

        // Oracle: all structural probes allowed, all blocked NRs -> ERRNO|EPERM.
        for (probe in SyscallProbeMatrix.structural(arch)) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ALLOW,
                BpfSimulator.simulate(compiled.compiledFilters, probe.nr, arch),
                "Structural probe ${probe.label} must be allowed on BST path",
            )
        }
        for (sys in blocked) {
            assertEquals(
                NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM,
                BpfSimulator.simulate(compiled.compiledFilters, sys.numberFor(arch), arch),
            )
        }

        ContainedExecutors.installOnCurrentThread(policy)

        assertTrue(ProcessHandle.current().pid() > 0)

        val probes = SyscallProbeMatrix.structural(arch) +
            SyscallProbeMatrix.matched(
            arch,
            blocked.map { it.numberFor(arch) },
        )
        assertKernelVerdictMatchesSimulator(compiled.compiledFilters, probes.map { it.nr }) { nr ->
            kernelDenied(nr)
        }
    }

    private fun verifyKillThreadProbeOutcome(
        readyPath: Path,
        outcomePath: Path,
        runProbe: () -> Unit,
    ) {
        runProbe()
        assertTrue(Files.exists(readyPath), "Kill probe never wrote seccomp-installed readiness marker")
        val readyMarker = Files.readString(readyPath).trim()
        assertTrue(readyMarker.startsWith("installed:"), "Unexpected kill-probe readiness marker: $readyMarker")
        val tid = readyMarker.removePrefix("installed:").toIntOrNull()
        assertTrue(tid != null && tid > 0, "Invalid probe tid in readiness marker: $readyMarker")
        assertTrue(Files.exists(outcomePath), "Kill probe never wrote kernel thread-death outcome marker")
        val outcome = Files.readString(outcomePath).trim()
        assertEquals("killed:$tid", outcome, "Kill-probe thread was not terminated by kernel seccomp")
    }

    @Test
    fun `kill_thread archetype terminates the probe child before PROBE_OK`(
        @TempDir directory: Path,
    ) {
        val victimNr = Syscall.UMASK.numberFor(arch)
        val childProgram = compiledProgramOf(victimNr)

        // Oracle prediction: the victim NR maps to KILL_THREAD.
        assertEquals(
            NativeConstants.SECCOMP_RET_KILL_THREAD,
            BpfSimulator.simulate(childProgram, victimNr, arch),
        )

        // Positive control: without seccomp, the dedicated probe thread executes victimNr and survives.
        val baselineReady = directory.resolve("baseline.ready")
        val baselineOutcome = directory.resolve("baseline.outcome")
        IsolatedProcessTester.runIsolatedTest(
            SeccompKillProbeChild::class.java.name,
            victimNr.toString(),
            "baseline",
            baselineReady.toString(),
            baselineOutcome.toString(),
        )
        val baselineTid = Files.readString(baselineReady).trim().removePrefix("baseline:")
        assertTrue(baselineTid.toIntOrNull()?.let { it > 0 } == true, "Missing baseline tid in $baselineReady")
        assertEquals("survived:$baselineTid", Files.readString(baselineOutcome).trim())

        // Negative control 1: seccomp installed with ACT_ALLOW lets probe thread survive -> oracle rejects.
        val allowReady = directory.resolve("allow.ready")
        val allowOutcome = directory.resolve("allow.outcome")
        val allowFailure =
            assertFailsWith<AssertionError> {
                verifyKillThreadProbeOutcome(allowReady, allowOutcome) {
                    IsolatedProcessTester.runIsolatedTest(
                        SeccompKillProbeChild::class.java.name,
                        victimNr.toString(),
                        "allow-control",
                        allowReady.toString(),
                        allowOutcome.toString(),
                    )
                }
            }
        val allowTid = Files.readString(allowReady).trim().removePrefix("installed:")
        assertEquals("survived:$allowTid", Files.readString(allowOutcome).trim())
        assertTrue(allowFailure.message.orEmpty().contains("killed:$allowTid"))

        // Negative control 2: child fails before installing seccomp (exit code 2) -> oracle rejects.
        val preInstallReady = directory.resolve("pre-install.ready")
        val preInstallOutcome = directory.resolve("pre-install.outcome")
        assertFailsWith<IllegalStateException> {
            verifyKillThreadProbeOutcome(preInstallReady, preInstallOutcome) {
                IsolatedProcessTester.runIsolatedTest(
                    SeccompKillProbeChild::class.java.name,
                    victimNr.toString(),
                    "fail-before-install",
                    preInstallReady.toString(),
                    preInstallOutcome.toString(),
                )
            }
        }
        assertFalse(Files.exists(preInstallReady), "Pre-install failure must not produce readiness marker")

        // Negative control 3: simulated child timeout -> oracle rejects rather than treating exception as death.
        val timeoutReady = directory.resolve("timeout.ready")
        val timeoutOutcome = directory.resolve("timeout.outcome")
        assertFailsWith<IllegalStateException> {
            verifyKillThreadProbeOutcome(timeoutReady, timeoutOutcome) {
                error("Timed out waiting for kill-probe child")
            }
        }

        // Guarded run: real ACT_KILL_THREAD terminates probeThread in kernel before syscall returns.
        val guardedReady = directory.resolve("guarded.ready")
        val guardedOutcome = directory.resolve("guarded.outcome")
        verifyKillThreadProbeOutcome(guardedReady, guardedOutcome) {
            IsolatedProcessTester.runIsolatedTest(
                SeccompKillProbeChild::class.java.name,
                victimNr.toString(),
                "guarded",
                guardedReady.toString(),
                guardedOutcome.toString(),
            )
        }
    }

    /** Compiles the same program the kill-probe child installs, for oracle prediction. */
    private fun compiledProgramOf(victimNr: Int): List<BpfInstruction> {
        val victim = io.mazewall.platform.syscall.Syscall.entries
            .first { it.numberFor(arch) == victimNr }
        val policy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .addAction(SeccompAction.ACT_KILL_THREAD, victim)
            .build()
        return policy.compile(arch).compiledFilters
    }

    @Test
    fun `stacked filters union semantics - deny-then-allow results in deny`() {
        // Test union-aware self-verification (issue-20260824-011900).
        // When multiple filters are stacked, the kernel enforces the UNION of all filters.
        // This means: deny-then-allow ⇒ deny (the most restrictive action wins).

        val victimNr = Syscall.CONNECT.numberFor(arch)

        // First layer: deny CONNECT
        val firstPolicy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .block(Syscall.CONNECT)
            .build()

        ContainedExecutors.installOnCurrentThread(firstPolicy)

        // Verify first layer denies CONNECT
        assertTrue(kernelDenied(victimNr), "First layer must deny CONNECT")

        // Second layer: allow CONNECT (but this should be overridden by first layer's deny)
        val secondPolicy = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .defaultAction(SeccompAction.ACT_ALLOW)
            .allow(Syscall.CONNECT)
            .build()

        ContainedExecutors.installOnCurrentThread(secondPolicy)

        // Union semantics: CONNECT must STILL be denied because first layer denied it
        // The union takes the most restrictive action (deny > allow)
        assertTrue(kernelDenied(victimNr), "Union of stacked filters must deny CONNECT (deny-then-allow ⇒ deny)")

        // Liveness: getpid must still work (both layers allow it)
        assertTrue(ProcessHandle.current().pid() > 0)
    }
}
