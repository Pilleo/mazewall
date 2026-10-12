package io.mazewall.portal

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
                assertTrue(reason in failure.stackTraceToString(), "$mode must reject for its actual launch condition")
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
        val fixture = fixture()
        for (cancel in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val receipts = Receipts()
            receipts.beforeReturn = { child ->
                assertEquals("MAZEWALL_PORTAL_PARENT_AUTH_PENDING", CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(5, TimeUnit.SECONDS))
                entered.countDown()
                if (cancel) assertTrue(release.await(5, TimeUnit.SECONDS)) else assertFalse(release.await(2100, TimeUnit.MILLISECONDS))
            }
            val broker =
                ProcessBroker(
                    launcher = receipts,
                    sockets = receipts.sockets,
                    workerClasspath = fixture,
                    launchProfile = selectedProfile(),
                    limits = PortalRuntimeLimits(startupTimeoutMs = 2000, closeTimeoutMs = 200),
                )
            val startup = CompletableFuture.supplyAsync { runCatching { broker.start() }.exceptionOrNull() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(receipts.child!!.isAlive)
                if (cancel) {
                    assertFailsWith<IncompleteBrokerCleanupException> { broker.close() }
                    assertEquals(1, broker.provisionalWorkers(), "held launch still physically owns actual child")
                    assertTrue(receipts.child!!.isAlive)
                    release.countDown()
                }
                val failure = requireNotNull(startup.get(8, TimeUnit.SECONDS))
                assertFalse("GUEST_ENTERED" in failure.suppressed.joinToString { it.message.orEmpty() })
                broker.close()
                assertEquals(ProcessBrokerLifecycle.Closed, broker.lifecycleState())
                receipts.assertRetired()
            } finally {
                release.countDown()
                startup.handle { _, _ -> Unit }.get(8, TimeUnit.SECONDS)
                broker.close()
            }
        }
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
