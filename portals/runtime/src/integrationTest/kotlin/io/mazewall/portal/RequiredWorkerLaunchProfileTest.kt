package io.mazewall.portal

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.process.JvmLaunchPreparation
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.socket.SocketManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequiredWorkerLaunchProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `original public config entries inherit deployment marker and explicit operator entries prepare real guests`() {
        val cp = fixture()
        val classes = directory.resolve("classes")
        val stub = directory.resolve("Nested_13_LaunchFixture_8_ContractPortalStub.java")
        Files.writeString(
            stub,
            """
            package fixture;
            import io.mazewall.portal.*;
            public class Nested_13_LaunchFixture_8_ContractPortalStub implements LaunchFixture.Contract {
                private final ProcessBroker broker;
                public Nested_13_LaunchFixture_8_ContractPortalStub(ProcessBroker broker) { this.broker = broker; }
                public String region() {
                    return new String(broker.invoke(new PortalMethod.Generated(102030), new byte[] {1}, new Capability.ReadFd[0]), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        """.trimIndent(),
        )
        val host = directory.resolve("PublicLaunchHost.java")
        Files.writeString(
            host,
            """
            package fixture;
            import io.mazewall.portal.*;
            public class PublicLaunchHost {
                public static void main(String[] args) throws Exception {
                    if (!"legacy-region".equals(System.getenv("MAZEWALL_DEPLOYMENT_REGION"))) throw new AssertionError("host deployment marker absent");
                    if (!"host-only".equals(System.getenv("MAZEWALL_HOST_ONLY_MARKER"))) throw new AssertionError("host-only marker absent");
                    var loader = PublicLaunchHost.class.getClassLoader();
                    var artifacts = java.util.Map.of("artifact", new PortalWorkerArtifact(java.nio.file.Path.of(args[0]), args[1]));
                    var profiles = java.util.Map.of("policy", new PortalWorkerProfile());
                    var grants = PortalGrantRoots.Companion.empty();
                    var limits = new PortalRuntimeLimits();
                    var preparation = (io.mazewall.platform.process.JvmLaunchPreparation) builder -> {
                        builder.environment().put("MAZEWALL_APPROVED_MARKER", "approved");
                        builder.environment().remove("MAZEWALL_HOST_ONLY_MARKER");
                        builder.environment().put("MAZEWALL_DEPLOYMENT_REGION", "operator-region");
                    };
                    var launch = new PortalLaunchProfile(java.util.Map.of(PortalLaunchMarker.APPROVED, "approved", PortalLaunchMarker.DEPLOYMENT_REGION, "operator-region"), java.util.Set.of(PortalLaunchMarker.HOST_ONLY), java.util.Set.of(), 2, preparation, java.util.List.of());
                    for (String mode : java.util.List.of("constructor", "load", "operator-constructor", "operator-load")) {
                        var cache = java.nio.file.Path.of(args[2], mode);
                        PortalRuntimeConfig config;
                        switch (mode) {
                            case "constructor": config = new PortalRuntimeConfig(PortalServiceManifestCatalog.INSTANCE.load(loader), artifacts, cache, profiles, null, grants, limits); break;
                            case "load": config = PortalRuntimeConfig.load(loader, artifacts, cache, profiles, null, grants, limits); break;
                            case "operator-constructor": config = new PortalRuntimeConfig(PortalServiceManifestCatalog.INSTANCE.load(loader), artifacts, cache, profiles, null, grants, limits, java.util.Map.of("policy", launch), java.util.Set.of("policy")); break;
                            case "operator-load": config = PortalRuntimeConfig.load(loader, artifacts, cache, profiles, null, grants, limits, java.util.Map.of("policy", launch), java.util.Set.of("policy")); break;
                            default: throw new AssertionError(mode);
                        }
                        try (var runtime = PortalRuntime.open(config)) {
                            String expected = mode.startsWith("operator") ? "operator-region" : "legacy-region";
                            if (!expected.equals(runtime.get(LaunchFixture.Contract.class).region())) throw new AssertionError("real guest marker mismatch: " + mode);
                        }
                        System.out.println("REAL_GUEST_" + mode);
                    }
                }
            }
        """.trimIndent(),
        )
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", cp, "-d", classes.toString(), stub.toString(), host.toString()))
        val fixtureJar = directory.resolve("fixture.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(fixtureJar)).use { jar ->
            Files.walk(classes).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    jar.putNextEntry(java.util.jar.JarEntry(classes.relativize(file).toString()))
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }
        val bundle = directory.resolve("worker.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            (System.getProperty("io.mazewall.portal.worker.classpath").split(java.io.File.pathSeparator).map(Path::of) + listOf(fixtureJar)).forEachIndexed { index, file ->
                assertTrue(Files.isRegularFile(file) && file.toString().endsWith(".jar"), "actual packaged worker classpath required: $file")
                zip.putNextEntry(java.util.zip.ZipEntry("lib/$index-${file.fileName}"))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
        val digest = java.util.HexFormat
            .of()
            .formatHex(
                java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(bundle)),
            )
        val process = io.mazewall.platform.process.JvmChildProcess.start(
            RealProcessLauncher,
            io.mazewall.platform.process.JvmChildSpec(
                "fixture.PublicLaunchHost",
                listOf(bundle.toString(), digest, directory.resolve("cache").toString()),
                classpath = cp,
                javaAgents = io.mazewall.platform.process.JavaAgentSelection.None,
            ),
            preparation = JvmLaunchPreparation { builder ->
                builder.environment()["MAZEWALL_DEPLOYMENT_REGION"] = "legacy-region"
                builder.environment()[HOST] = "host-only"
                builder.environment().remove(APPROVED)
            },
        )
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS))
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
            listOf("constructor", "load", "operator-constructor", "operator-load").forEach { assertTrue("REAL_GUEST_$it" in output, output) }
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `selected synthetic environment and acquired forbidden resource permit genuine worker and guest`() {
        val fixture = fixture()
        val canary = Files.writeString(directory.resolve("canary"), "acquired canary")
        FileChannel.open(canary, StandardOpenOption.READ).use {
            val identity = PortalDescriptorIdentity.measure(canary)
            val receipts = Receipts()
            val profile = PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "approved"), setOf(PortalLaunchMarker.HOST_ONLY), setOf(identity), preparation = operatorPreparation())
            ProcessBroker(launcher = receipts, sockets = receipts.sockets, workerClasspath = fixture, launchProfile = profile).use { broker ->
                broker.start()
                assertEquals("GUEST_ENTERED:approved:null", broker.call(PortalMethod.Generated(102030), byteArrayOf(), emptyList()).toString(Charsets.UTF_8))
                assertEquals("alive", broker.echo("alive"))
                assertSame(profile.preparation, receipts.prepared)
            }
            receipts.assertRetired()
        }
    }

    @Test
    fun `actual environment mismatch missing contract and unsupported contract have native zero guest entry`() {
        val fixture = fixture()
        for (mode in listOf("missing", "unsupported", "mismatch", "host-only", "wrong-identity")) {
            val receipts = Receipts { args, preparation ->
                val altered = args.toMutableList()
                val index = altered.indexOf("--launch-contract")
                when (mode) {
                    "missing" -> {
                        altered.removeAt(index + 1)
                        altered.removeAt(index)
                    }
                    "unsupported" -> altered[index + 1] = java.util.Base64
                        .getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("format=99\n".toByteArray())
                    "mismatch", "host-only" -> Unit
                    "wrong-identity" -> {
                        altered[index + 1] =
                            PortalLaunchProfile(
                                mapOf(PortalLaunchMarker.APPROVED to "different-authority"),
                                setOf(PortalLaunchMarker.HOST_ONLY),
                                preparation = operatorPreparation(),
                            ).contract().argument()
                    }
                }
                altered to JvmLaunchPreparation { builder ->
                    preparation.prepare(builder)
                    when (mode) {
                        "mismatch" -> builder.environment()[APPROVED] = "wrong"
                        "host-only" -> builder.environment()[HOST] = "synthetic-host-only"
                        "wrong-identity" -> builder.environment()[APPROVED] = "different-authority"
                    }
                }
            }
            ProcessBroker(launcher = receipts, sockets = receipts.sockets, workerClasspath = fixture, launchProfile = selectedProfile()).use { broker ->
                val failure = assertFailsWith<Exception>(mode) { broker.start() }
                val diagnostics = failure.suppressed.joinToString { it.message.orEmpty() }
                val reason = when (mode) {
                    "missing" -> "missing or duplicate required launch contract"
                    "unsupported" -> "unsupported required launch contract"
                    "mismatch" -> "declared child marker does not match launch contract"
                    "host-only" -> "declared absent child marker is present"
                    "wrong-identity" -> "parent startup authorization does not match"
                    else -> error("unknown test mode")
                }
                assertTrue(reason in failure.stackTraceToString(), "$mode must reject for its actual launch condition: ${failure.stackTraceToString()}")
                assertFalse("GUEST_ENTERED" in diagnostics, "$mode must reject before real guest constructor")
                assertEquals(0, broker.spawnedWorkers())
                assertEquals(0, broker.provisionalWorkers())
                assertEquals(0, broker.trackedWorkers())
            }
            receipts.assertRetired()
        }
    }

    @Test
    fun `selected launch cancellation and expiry retain actual child until physical retirement`() {
        val diagnostics = Array(2) { SelectedLaunchDiagnostic() }
        var diagnosticIteration = 0
        try {
            val fixture = fixture()
            for (cancel in listOf(true, false)) {
                val diagnostic = diagnostics[diagnosticIteration++]
                diagnostic.visited = true
                diagnostic.cancel = cancel
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val receipts = Receipts()
                receipts.beforeReturn = { child ->
                    assertEquals("MAZEWALL_PORTAL_PARENT_AUTH_PENDING", CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(5, TimeUnit.SECONDS))
                    entered.countDown()
                    if (cancel) assertTrue(release.await(10, TimeUnit.SECONDS)) else assertFalse(release.await(2100, TimeUnit.MILLISECONDS))
                }
                val broker =
                    ProcessBroker(
                        launcher = receipts,
                        sockets = receipts.sockets,
                        workerClasspath = fixture,
                        launchProfile = selectedProfile(),
                        limits = PortalRuntimeLimits(startupTimeoutMs = if (cancel) 10_000 else 2000, closeTimeoutMs = 5_000),
                    )
                diagnostic.broker = broker
                val startup = CompletableFuture.supplyAsync {
                    runCatching { broker.start() }.exceptionOrNull().also {
                        diagnostic.startupFailure = it
                        diagnostic.startupThreadId = Thread.currentThread().threadId()
                        diagnostic.startupInterrupted = Thread.currentThread().isInterrupted
                        diagnostic.startupReturnedNs = System.nanoTime()
                    }
                }
                var primary: Throwable? = null
                try {
                    diagnostic.phase = SelectedLaunchPhase.HANDSHAKE
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(receipts.child!!.isAlive)
                    if (cancel) {
                        val closeReturned = CompletableFuture<Throwable?>()
                        val closeInterrupted = AtomicBoolean()
                        val closer = Thread.ofPlatform().unstarted {
                            diagnostic.closerThreadId = Thread.currentThread().threadId()
                            diagnostic.interruptedCloseStartedNs = System.nanoTime()
                            val failure = runCatching { broker.close() }.exceptionOrNull()
                            closeInterrupted.set(Thread.currentThread().isInterrupted)
                            diagnostic.interruptedCloseReturnedNs = System.nanoTime()
                            closeReturned.complete(failure)
                        }
                        try {
                            diagnostic.phase = SelectedLaunchPhase.EXPECTED_INTERRUPTED_CLOSE
                            closer.start()
                            io.mazewall.testing.awaitCondition(java.time.Duration.ofSeconds(5), "selected launch entered close") {
                                broker.lifecycleState() == ProcessBrokerLifecycle.Closing
                            }
                            closer.interrupt()
                            val failure = closeReturned.get(5, TimeUnit.SECONDS)
                            assertTrue(failure is IncompleteBrokerCleanupException, failure?.stackTraceToString().orEmpty())
                            assertTrue(closeInterrupted.get(), "incomplete close must preserve its caller interruption")
                            assertEquals(1, broker.provisionalWorkers(), "held launch still physically owns actual child")
                            assertTrue(receipts.child!!.isAlive)
                            diagnostic.heldChildAliveAssertionPassed = true
                        } finally {
                            release.countDown()
                            closer.join(5_000)
                            assertFalse(closer.isAlive, "the actual close caller must physically end")
                        }
                    }
                    diagnostic.phase = SelectedLaunchPhase.STARTUP_RESULT
                    val failure = requireNotNull(startup.get(8, TimeUnit.SECONDS))
                    assertFalse("GUEST_ENTERED" in failure.suppressed.joinToString { it.message.orEmpty() })
                    diagnostic.phase = SelectedLaunchPhase.FINAL_NORMAL_CLOSE_272
                    diagnostic.normalCloseStartedNs = System.nanoTime()
                    broker.close()
                    diagnostic.normalCloseReturnedNs = System.nanoTime()
                    diagnostic.phase = SelectedLaunchPhase.PHYSICAL_ASSERTIONS
                    assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
                    receipts.assertRetired()
                    diagnostic.physicalAssertionsPassed = true
                } catch (failure: Throwable) {
                    diagnostic.bodyFailurePhase = diagnostic.phase
                    primary = failure
                    throw failure
                } finally {
                    release.countDown()
                    val cleanup = CleanupCollector()
                    diagnostic.phase = SelectedLaunchPhase.FINALLY_COMPLETION
                    cleanup.attempt("selected launch completion") { startup.handle { _, _ -> Unit }.get(8, TimeUnit.SECONDS) }
                    cleanup.attempt("selected launch broker retirement") {
                        diagnostic.phase = SelectedLaunchPhase.FINALLY_RETRY_282
                        diagnostic.retryStartedNs = System.nanoTime()
                        broker.close()
                        diagnostic.retryReturnedNs = System.nanoTime()
                    }
                    val outcome = cleanup.outcome()
                    diagnostic.finallyActionsFinished = true
                    diagnostic.finallyCleanupComplete = outcome.complete
                    outcome.failureOrNull()?.let { throw CleanupFailures.merge(primary, it) }
                    diagnostic.phase = SelectedLaunchPhase.FINISHED
                }
            }
        } catch (failure: Throwable) {
            try {
                renderSelectedLaunchDiagnostics(diagnostics)
            } catch (diagnosticFailure: Throwable) {
                runCatching { System.err.println("BUFFERED-DIAG diagnostic-render-error (original failure preserved): ${diagnosticFailure.stackTraceToString()}") }
            }
            throw failure
        }
    }

    private enum class SelectedLaunchPhase {
        NOT_REACHED, HANDSHAKE, EXPECTED_INTERRUPTED_CLOSE, STARTUP_RESULT, FINAL_NORMAL_CLOSE_272,
        PHYSICAL_ASSERTIONS, FINALLY_COMPLETION, FINALLY_RETRY_282, FINISHED,
    }

    private class SelectedLaunchDiagnostic {
        var visited = false
        var cancel = false
        var broker: ProcessBroker? = null
        var phase = SelectedLaunchPhase.NOT_REACHED
        var bodyFailurePhase = SelectedLaunchPhase.NOT_REACHED
        var startupFailure: Throwable? = null
        var startupThreadId = 0L
        var startupInterrupted = false
        var startupReturnedNs = 0L
        var closerThreadId = 0L
        var interruptedCloseStartedNs = 0L
        var interruptedCloseReturnedNs = 0L
        var heldChildAliveAssertionPassed = false
        var normalCloseStartedNs = 0L
        var normalCloseReturnedNs = 0L
        var physicalAssertionsPassed = false
        var retryStartedNs = 0L
        var retryReturnedNs = 0L
        var finallyActionsFinished = false
        var finallyCleanupComplete = false
    }

    private fun renderSelectedLaunchDiagnostics(diagnostics: Array<SelectedLaunchDiagnostic>) {
        System.err.println("BUFFERED-DIAG after-original-body-and-finally; 0 timestamp=not reached; predicate -1=not evaluated/reached, 0=false, 1=true; identity 0=null; no physical re-probes")
        for (record in diagnostics) {
            System.err.println("BUFFERED-DIAG iteration visited=${record.visited} cancel=${record.cancel} phase=${record.phase} bodyFailurePhase=${record.bodyFailurePhase} heldChildAliveAssertionPassed=${record.heldChildAliveAssertionPassed} physicalAssertionsPassed=${record.physicalAssertionsPassed} finallyActionsFinished=${record.finallyActionsFinished} finallyCleanupComplete=${record.finallyCleanupComplete}")
            if (!record.visited) continue
            System.err.println("BUFFERED-DIAG test expectedInterrupted(thread=${record.closerThreadId},startNs=${record.interruptedCloseStartedNs},returnedNs=${record.interruptedCloseReturnedNs}) normal272(startNs=${record.normalCloseStartedNs},returnedNs=${record.normalCloseReturnedNs}) retry282(startNs=${record.retryStartedNs},returnedNs=${record.retryReturnedNs})")
            System.err.println("BUFFERED-DIAG startup thread=${record.startupThreadId} interrupted=${record.startupInterrupted} returnedNs=${record.startupReturnedNs} failure=${record.startupFailure?.stackTraceToString()}")
            val broker = record.broker ?: continue
            System.err.println("BUFFERED-DIAG closeCount=${broker.diagnosticCloseCount} capacity=${broker.diagnosticCloses.size} overflow=${broker.diagnosticCloseCount > broker.diagnosticCloses.size} startupAttemptCount=${broker.diagnosticStartupAttemptCount} capacity=${broker.diagnosticStartupAttempts.size} overflow=${broker.diagnosticStartupAttemptCount > broker.diagnosticStartupAttempts.size}")
            for ((index, close) in broker.diagnosticCloses.withIndex()) {
                if (close.startedNs == 0L) {
                    System.err.println("BUFFERED-DIAG close[$index] skipped=no-start-record")
                    continue
                }
                System.err.println("BUFFERED-DIAG close[$index] startNs=${close.startedNs} conjunctionNs=${close.conjunctionNs} finishedNs=${close.finishedNs} thread=${close.threadId} interrupted(before,conjunction,after)=${close.interruptedBefore},${close.interruptedAtConjunction},${close.interruptedAfter} remainingMs(conjunction,after)=${close.remainingAtConjunctionMs},${close.remainingAfterMs} startupLatch=${close.startupLatch}")
                System.err.println("BUFFERED-DIAG close[$index] captures=${close.capturesStopped} operations=${close.operationsStopped} maintenance=${close.maintenanceStopped} startup=${close.startupStopped} ownedResources=${close.ownedResources} conjunctionResult=${close.conjunctionResult} registry(tracked,idle,provisionalSlots,attempts,handoffs,capabilities,cleanup)=${close.registry.toList()} allRetired=${close.allRetired} lifecycleAfter=${close.lifecycleAfter} failure=${close.failure?.stackTraceToString()}")
                System.err.println("BUFFERED-DIAG close[$index] attemptCount=${close.attemptCount} capacity=${close.attempts.size} overflow=${close.attemptCount > close.attempts.size} captureCount=${close.captureCount} capacity=${close.captures.size} overflow=${close.captureCount > close.captures.size}")
                for (attempt in close.attempts) renderAttemptDiagnostic(attempt)
                for (capture in close.captures) {
                    if (capture.observedNs == 0L) continue
                    System.err.println("BUFFERED-DIAG capture identity=${capture.identity} observedNs=${capture.observedNs} physicalEnd=${capture.physicalEnd} thread=${capture.captureThreadId} cancelThread=${capture.cancelThreadId} ended=${capture.ended} cancellationEnded=${capture.cancellationEnded} resultObserved=${capture.resultObserved} closerInterrupted=${capture.interrupted} cancelFailure=${capture.cancelFailure?.stackTraceToString()}")
                }
            }
            for (attempt in broker.diagnosticStartupAttempts) renderAttemptDiagnostic(attempt)
        }
    }

    private fun renderAttemptDiagnostic(attempt: BrokerAttemptDiagnostic) {
        if (attempt.observedNs == 0L) return
        System.err.println("BUFFERED-DIAG attempt identity=${attempt.attemptIdentity} observedNs=${attempt.observedNs} thread=${attempt.threadId} interrupted=${attempt.interrupted} retired=${attempt.retired} acquisitionFinished=${attempt.acquisitionFinished} retirementRequested=${attempt.retirementRequested} transferred=${attempt.transferred} flags(channel,server,destroyed,reaped,cgroup,pump,endpoint)=${attempt.channelRetired},${attempt.serverRetired},${attempt.processDestroyed},${attempt.processReaped},${attempt.cgroupRetired},${attempt.pumpRetired},${attempt.endpointRetired} processIdentity=${attempt.processIdentity} pumpIdentity=${attempt.pumpIdentity} cleanupComplete=${attempt.cleanupComplete}")
    }

    @Test
    fun `operator preparation failure preserves cause and retires actual listener without child acquisition`() {
        val receipts = Receipts()
        val primary = java.io.IOException("operator preparation failed")
        val profile = PortalLaunchProfile(preparation = JvmLaunchPreparation { throw primary })
        ProcessBroker(launcher = receipts, sockets = receipts.sockets, workerClasspath = fixture(), launchProfile = profile).use { broker ->
            assertSame(primary, assertFailsWith<java.io.IOException> { broker.start() })
            assertEquals(null, receipts.child)
            assertEquals(0, broker.provisionalWorkers())
            assertEquals(0, broker.spawnedWorkers())
            assertTrue(receipts.acquired.isNotEmpty())
            receipts.acquired.forEach { owner -> assertEquals(1, receipts.retired.count { it === owner }) }
            assertFalse(Files.exists(requireNotNull(receipts.endpoint)))
            assertFalse(Files.exists(requireNotNull(receipts.endpoint).parent))
        }
    }

    @Test
    fun `owned delegated cgroup applies finite controls before permit and retires real guest`() {
        val supplied = System.getenv("MAZEWALL_TEST_DELEGATED_CGROUP_ROOT")
        assumeTrue(supplied != null, "PREREQUISITE: fresh owned delegated cgroup v2 unit with cpu and memory controllers")
        val root = Path.of(requireNotNull(supplied))
        assertEquals("cgroup2", Files.getFileStore(root).type(), "explicit required root must be genuine cgroup v2")
        val hostMembership = Files.readString(Path.of("/proc/self/cgroup")).trim().removePrefix("0::")
        assertEquals(root.resolve("runner"), Path.of("/sys/fs/cgroup$hostMembership"), "test JVM must belong to the supplied owned unit's runner subgroup")
        val receipts = Receipts()
        var attached: Path? = null
        var checkedBeforePermit = false
        receipts.beforeReturn = { child ->
            try {
                assertEquals(
                    "MAZEWALL_PORTAL_PARENT_AUTH_PENDING",
                    CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(5, TimeUnit.SECONDS),
                )
            } catch (failure: Throwable) {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                throw failure
            }
        }
        val sockets = object : SocketManager by receipts.sockets {
            override fun accept(
                serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                // No peer has been returned to the broker, so it cannot have sent a permit.
                val pid = requireNotNull(receipts.child).pid()
                val group = Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith("portal-$pid-") }.toList().single() }
                attached = group
                assertEquals("268435456", Files.readString(group.resolve("memory.max")).trim())
                assertEquals("50000 100000", Files.readString(group.resolve("cpu.max")).trim())
                assertEquals(listOf(pid.toString()), Files.readAllLines(group.resolve("cgroup.procs")))
                val membership = Files.readString(Path.of("/proc/$pid/cgroup"))
                assertEquals("0::/${Path.of("/sys/fs/cgroup").relativize(group)}\n", membership)
                checkedBeforePermit = true
                println("CGROUP_BEFORE_PERMIT pid=$pid directory=$group memory.max=268435456 cpu.max=50000,100000 membership=${membership.trim()}")
                return receipts.sockets.accept(serverFd)
            }
        }
        val broker = ProcessBroker(
            launcher = receipts,
            sockets = sockets,
            workerClasspath = fixture(),
            launchProfile = selectedProfile(),
            cgroupRoot = root,
            workerProfile = PortalWorkerProfile(
                maxHeapBytes = 64L * 1024 * 1024,
                memoryMaxBytes = 256L * 1024 * 1024,
                cpuLimit = PortalCpuLimit(java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(100)),
            ),
        )
        broker.use {
            broker.start() // Validates the worker's independent membership in ACK and format-2 readiness.
            assertTrue(checkedBeforePermit)
            assertEquals(1, broker.spawnedWorkers())
            assertEquals(requireNotNull(attached).toString(), broker.cgroupUsage().single().directory)
            assertEquals("GUEST_ENTERED:approved:null", broker.call(PortalMethod.Generated(102030), byteArrayOf(), emptyList()).toString(Charsets.UTF_8))
            println("CGROUP_READY_AND_GUEST pid=${requireNotNull(receipts.child).pid()} resources=${PortalParentResources.measure(requireNotNull(receipts.child).pid(), true)}")
        }
        receipts.assertRetired()
        assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
        assertEquals(0, broker.trackedWorkers())
        assertEquals(0, broker.provisionalWorkers())
        assertFalse(Files.exists(requireNotNull(attached)))
        println(
            "CGROUP_PHYSICAL_END pid=${requireNotNull(
                receipts.child,
            ).pid()} childExit=${requireNotNull(receipts.child).exitValue()} sockets=${receipts.retired.size} pump=ended endpoint=deleted cgroup=deleted",
        )
    }

    @Test
    fun `required real cgroup setup failure reaps child without permit or guest entry`() {
        val receipts = Receipts()
        val root = Files.createDirectory(directory.resolve("undelegated"))
        Files.writeString(root.resolve("cgroup.controllers"), "memory cpu\n")
        ProcessBroker(
            launcher = receipts,
            sockets = receipts.sockets,
            workerClasspath = fixture(),
            cgroupRoot = root,
            workerProfile = PortalWorkerProfile(
                maxHeapBytes = 64L * 1024 * 1024,
                memoryMaxBytes =
            128L * 1024 * 1024,
            ),
        ).use { broker ->
            assertFailsWith<PortalCgroupUnavailableException> { broker.start() }
            assertEquals(0, broker.spawnedWorkers())
            assertEquals(0, broker.provisionalWorkers())
        }
        receipts.assertRetired()
    }

    @Test
    fun `canary is actually observable in child before forbidden resource rejection and cleanup retry`() {
        val fixture = fixture()
        val path = Files.writeString(directory.resolve("canary"), "owned resource")
        FileChannel.open(path, StandardOpenOption.READ).use {
            val identity = PortalDescriptorIdentity.measure(path)
            var observed = false
            val receipts = Receipts { args, preparation ->
                val altered = args.toMutableList()
                altered[altered.indexOf("io.mazewall.portal.worker.PortalWorkerMain")] = "fixture.LaunchFixture"
                altered.add(1, "-Dfixture.canary=$path")
                altered to preparation
            }
            receipts.beforeReturn = { child ->
                val marker = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(5, TimeUnit.SECONDS)
                assertEquals("CANARY_OBSERVABLE", marker)
                observed = Files.newDirectoryStream(Path.of("/proc/${child.pid()}/fd")).use { entries ->
                    entries.any { runCatching { PortalDescriptorIdentity.measure(it) == identity }.getOrDefault(false) }
                }
                assertTrue(observed, "negative must observe the same acquired resource in the real child")
                child.outputStream.write(1)
                child.outputStream.flush()
            }
            receipts.failNextClose = true
            val broker = ProcessBroker(launcher = receipts, sockets = receipts.sockets, workerClasspath = fixture, launchProfile = PortalLaunchProfile(forbiddenDescriptors = setOf(identity)))
            try {
                val failure = assertFailsWith<Exception> { broker.start() }
                assertTrue(observed)
                assertFalse("GUEST_ENTERED" in failure.suppressed.joinToString { it.message.orEmpty() })
                assertEquals(0, broker.spawnedWorkers())
                assertEquals(1, broker.provisionalWorkers(), "pre-native socket failure retains actual owner")
                assertFalse(receipts.child!!.isAlive)
                assertTrue(receipts.acquired.any { it === receipts.failedOwner })
                assertTrue(receipts.retired.none { it === receipts.failedOwner })
                broker.close()
                assertEquals(0, broker.provisionalWorkers())
                receipts.assertRetired()
            } finally {
                broker.close()
            }
        }
    }

    private class Receipts(
        private val alter: (List<String>, JvmLaunchPreparation) -> Pair<List<String>, JvmLaunchPreparation> = { args, preparation -> args to preparation },
    ) : ProcessLauncher by RealProcessLauncher {
        var child: Process? = null
        var prepared: JvmLaunchPreparation? = null
        var beforeReturn: (Process) -> Unit = {}
        val acquired = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val retired = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        var failNextClose = false
        var failedOwner: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
        var endpoint: Path? = null
        val sockets = object : SocketManager by RealSocketManager {
            override fun createUnixServer(socketPath: String): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> {
                endpoint = Path.of(socketPath)
                return RealSocketManager.createUnixServer(socketPath).also(acquired::add)
            }

            override fun accept(
                serverFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            ): FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned> = RealSocketManager.accept(serverFd).also(acquired::add)

            override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>) {
                if (failNextClose) {
                    failNextClose = false
                    failedOwner = fd
                    throw java.io.IOException("retryable pre-native socket cleanup")
                }
                RealSocketManager.close(fd)
                retired.add(fd)
            }
        }

        override fun startProcess(
            args: List<String>,
            redirectErrorStream: Boolean,
            preparation: JvmLaunchPreparation,
        ): Process {
            prepared = preparation
            val (actualArgs, actualPreparation) = alter(args, preparation)
            return RealProcessLauncher.startProcess(actualArgs, redirectErrorStream, actualPreparation).also {
                child = it
                beforeReturn(it)
            }
        }

        fun assertRetired() {
            val actual = requireNotNull(child)
            assertFalse(actual.isAlive)
            actual.exitValue() // Actual wait/reap, not result delivery.
            assertFalse(Files.exists(Path.of("/proc/${actual.pid()}")))
            assertTrue(acquired.isNotEmpty())
            acquired.forEach { owner -> assertEquals(1, retired.count { it === owner }) }
            assertFalse(Files.exists(requireNotNull(endpoint)))
            assertFalse(Files.exists(requireNotNull(endpoint).parent))
            assertTrue(Thread.getAllStackTraces().keys.none { it.name == "portal-worker-output" && it.isAlive }, "real output pump must physically terminate")
        }
    }

    private fun operatorPreparation(): JvmLaunchPreparation =
        JvmLaunchPreparation { builder ->
        builder.environment()[APPROVED] = "approved"
        builder.environment().remove(HOST)
    }

    private fun selectedProfile(): PortalLaunchProfile = PortalLaunchProfile(mapOf(PortalLaunchMarker.APPROVED to "approved"), setOf(PortalLaunchMarker.HOST_ONLY), preparation = operatorPreparation())

    private fun fixture(): String {
        val source = directory.resolve("LaunchFixture.java")
        Files.writeString(
            source,
            """
            package fixture;
            import io.mazewall.portal.*;
            import io.mazewall.portal.worker.PortalWorkerMain;
            public class LaunchFixture {
                static java.io.FileInputStream canary;
                public static void main(String[] args) throws Exception {
                    canary = new java.io.FileInputStream(System.getProperty("fixture.canary"));
                    boolean observable = false;
                    try (var entries = java.nio.file.Files.newDirectoryStream(java.nio.file.Path.of("/proc/self/fd"))) {
                        for (var fd : entries) {
                            try { if (java.nio.file.Files.isSameFile(fd, java.nio.file.Path.of(System.getProperty("fixture.canary")))) observable = true; }
                            catch (java.nio.file.NoSuchFileException closed) {}
                        }
                    }
                    if (!observable) throw new AssertionError("acquired canary not visible");
                    System.out.println("CANARY_OBSERVABLE"); System.out.flush();
                    if (System.in.read() != 1) throw new AssertionError("missing release");
                    PortalWorkerMain.main(args);
                }
                public interface Contract { String region(); }
                public static class Guest implements Contract {
                    public Guest() { System.out.println("GUEST_ENTERED"); System.out.flush(); }
                    public String region() { return System.getenv("MAZEWALL_DEPLOYMENT_REGION"); }
                }
                public static class Dispatcher {
                    public static final Dispatcher INSTANCE = new Dispatcher();
                    public int[] getMETHOD_IDS() { return new int[] {102030}; }
                    public byte[] handle(Contract guest, PortalMethod.Generated method, byte[] payload, java.util.List<?> fds) {
                        if (payload.length != 0) return guest.region().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        return ("GUEST_ENTERED:" + System.getenv("$APPROVED") + ":" + System.getenv("$HOST")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        """.trimIndent(),
        )
        val classes = Files.createDirectories(directory.resolve("classes"))
        val classpath = System.getProperty("io.mazewall.portal.worker.classpath")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath, "-d", classes.toString(), source.toString()))
        val root = Files.createDirectories(classes.resolve("META-INF/mazewall/portal"))
        Files.writeString(root.resolve("fixture.LaunchFixture\$Contract"), "fixture.LaunchFixture\$Dispatcher\n")
        Files.writeString(root.resolve("services.index"), "launch\n")
        Files.createDirectories(root.resolve("services"))
        val manifest =
            PortalServiceManifest(
                PortalServiceId("launch"),
                "fixture.LaunchFixture\$Contract",
                "fixture.LaunchFixture\$Guest",
                "fixture.LaunchFixture\$Dispatcher",
                "schema",
                "launch",
                PortalInstanceMode.STATELESS,
                "policy",
                "artifact",
            )
        Files.writeString(root.resolve("services/launch"), PortalServiceManifestCodec.encode(manifest))
        return "$classpath${java.io.File.pathSeparator}$classes"
    }

    private companion object {
        const val APPROVED = "MAZEWALL_APPROVED_MARKER"
        const val HOST = "MAZEWALL_HOST_ONLY_MARKER"
    }
}
