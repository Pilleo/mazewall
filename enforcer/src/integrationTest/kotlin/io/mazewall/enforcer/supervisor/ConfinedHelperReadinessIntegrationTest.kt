package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.TrustedPolicyReview
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperSession
import io.mazewall.enforcer.internal.supervisor.ConfinedReadRequest
import io.mazewall.enforcer.internal.supervisor.HelperPurpose
import io.mazewall.enforcer.internal.supervisor.SocketHelperMain
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.IovecSegment
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.MsghdrSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.native
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannel
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeNetworking
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.lang.foreign.ValueLayout
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfinedHelperReadinessIntegrationTest {
    internal enum class ReadinessCase { Ready, MissingReady, PrematureReady, PeerPermission, WorkerAncestry, KnownWorkerLandlock, HelperLoss }

    @Test
    fun `actual managed baseline and worker installs have independently observed ancestry deltas`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCase", "AncestryLayers")
    }

    @ParameterizedTest
    @EnumSource(ReadinessCase::class)
    internal fun `actual managed readiness qualifies refusal and retires its original helper`(case: ReadinessCase) {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCase", case.name)
    }

    private fun verifyCase(case: String) {
        if (case != "AncestryLayers") {
            verifyManagedReadiness(ReadinessCase.valueOf(case))
            return
        }
        require(case == "AncestryLayers")
        val native = LinuxNative.snapshot()
        val observation = HelperSecurityObservation()
        val pid = Pid(ProcessHandle.current().pid().toInt())

        fun current() = observation.observeAncestry(pid, native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)

        fun fields(value: HelperSecurityObservation.Observation) =
            value.ancestry.canonical
            .trimEnd()
            .lines()
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val initial = current()
        val installations = CopyOnWriteArrayList<Pair<HelperSecurityObservation.Observation, HelperSecurityObservation.Observation>>()
        val observing = object : NativeEngine by native {
            override val raw = object : RawSyscallOperations by native.raw {
                override fun syscall(
                    nr: Long,
                    a1: NativeArg,
                    a2: NativeArg,
                    a3: NativeArg,
                    a4: NativeArg,
                    a5: NativeArg,
                    a6: NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val before = if (nr == Arch.current().seccompSyscallNumber.toLong() && a1.asLong == 1L && a3.asLong != 0L) current() else null
                    val result = native.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                    if (before != null && result is LinuxNative.SyscallResult.Success && result.value == 0L) installations += before to current()
                    return result
                }
            }
        }
        LinuxNative.setEngine(observing)
        val timeout = Duration.ofSeconds(10)
        try {
            val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
            try {
                val owner = current()
                val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build(), DomainLimits(1, 1))
                val invocation = runtime.submit(domain, Callable { 42 })
                assertEquals(42, invocation.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(2, installations.size, "Exactly one Tier1 and one worker filter must be observed")
                val boot = fields(initial)
                val caller = fields(owner)
                val baseline = boot.getValue("seccompFilters").toLong()
                assertEquals((baseline + 1).toString(), caller.getValue("seccompFilters"))
                assertEquals("1", caller.getValue("noNewPrivs"))
                assertEquals("2", caller.getValue("seccomp"))
                val mutableKeys = setOf("seccomp", "seccompFilters", "noNewPrivs")
                assertEquals(boot - mutableKeys, caller - mutableKeys)
                installations.forEachIndexed { index, (before, after) ->
                    initial.time.requireCommon(before.time)
                    initial.time.requireCommon(after.time)
                    assertEquals(initial.identity, before.identity)
                    assertEquals(initial.identity, after.identity)
                    assertEquals(boot - mutableKeys, fields(before) - mutableKeys)
                    assertEquals(boot - mutableKeys, fields(after) - mutableKeys)
                    assertEquals((baseline + index).toString(), fields(before).getValue("seccompFilters"))
                    assertEquals((baseline + index + 1).toString(), fields(after).getValue("seccompFilters"))
                    assertEquals("1", fields(before).getValue("noNewPrivs"))
                    assertEquals("1", fields(after).getValue("noNewPrivs"))
                    println("LAYER=$index BEFORE=${before.ancestry.canonical}AFTER=${after.ancestry.canonical}")
                }
                assertTrue(!Thread.currentThread().isVirtual)
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                println("ANCESTRY_LAYERS retired=true initial=${initial.ancestry.canonical}owner=${owner.ancestry.canonical}")
            } finally {
                runtime.close()
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    private fun verifyManagedReadiness(case: ReadinessCase) {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build/confined-readiness-fixtures")), "managed-").toAbsolutePath()
        val application = Files.createDirectory(directory.resolve("application"))
        val readable = Files.writeString(application.resolve("file"), "managed-confined-read")
        val forbidden = Files.writeString(directory.resolve("forbidden"), "outside")
        assertEquals("outside", Files.readString(forbidden))
        val source = Files.writeString(directory.resolve("Operation.java"), "package fixture; public class Operation { public int call() { return 42; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val operationJar = directory.resolve("operation.jar")
        JarOutputStream(Files.newOutputStream(operationJar)).use { output ->
            output.putNextEntry(JarEntry("fixture/Operation.class"))
            Files.copy(directory.resolve("fixture/Operation.class"), output)
            output.closeEntry()
        }
        val classpath = helperClasspath(directory)
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                val command = args.toMutableList()
                command[command.indexOf("-cp") + 1] = classpath.joinToString(File.pathSeparator)
                return RealProcessLauncher.startProcess(command, redirectErrorStream)
            }
        }
        val effects = ReadinessEffects(case, application, LinuxNative.snapshot())
        LinuxNative.setEngine(effects)
        val manager = SupervisorDaemonManager(processLauncher = launcher)
        try {
            val context = manager.prepareConfinedReadBootstrap()
            val helper = requireNotNull(context.confinedHelper)
            val helperPid = helper.identity.pid
            val process = ProcessHandle
                .of(helperPid.value.toLong())
                .orElseThrow()
            val childExit = process.onExit()
            effects.beforeRoot1 = {
                assertEquals(1, descriptorsFor(helperPid.value.toLong(), application))
                assertEquals(0, descriptorsFor(context.daemonProcess.pid(), application))
            }
            val mapped = Files.readAllLines(Path.of("/proc/self/maps")).mapNotNull { row ->
                val fields = row.split(Regex(" +"), limit = 6)
                fields
                    .getOrNull(5)
                    ?.takeIf { it.startsWith('/') && !it.endsWith(" (deleted)") && (fields[1].endsWith('p') || fields[1][1] != 'w' && fields[1].endsWith('s')) }
                    ?.let(Path::of)
                    ?.takeIf(Files::isRegularFile)
            }
            val support = (classpath + mapped + listOf(Path.of(System.getProperty("java.home"), "lib/modules"))).map { it.toRealPath() }.distinct()
            val reviewedSupport = support.map { ReviewedHelperSupport(it, digest(it)) }
            val builder = WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(application)
                .allowFsRead(operationJar)
                .block(Syscall.CONNECT)
            support.forEach { builder.allowFsRead(it) }
            val policy = builder.build()
            println("MANAGED_BOOTSTRAP daemon=${context.daemonProcess.pid()} helper=${helper.identity} ancestry=${helper.ancestry.canonical}")
            reviewedSupport.forEach { println("SUPPORT path=${it.path} sha256=${it.sha256} no-writers=disposable-fixture-lifetime") }
            URLClassLoader(arrayOf(operationJar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).use { resources ->
                    val binding = OperationBinding("readiness", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
                    val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                    val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("fixture", document.sha256(), emptySet()), binding)
                    val timeout = Duration.ofSeconds(10)
                    val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
                    try {
                        val native = LinuxNative.snapshot()
                        val denied = native.raw.syscall(Syscall.EXECVE.numberFor(Arch.current()).toLong())
                        check(denied is LinuxNative.SyscallResult.Error && denied.errno in setOf(1, 13)) { "Parent exec is not denied: $denied" }
                        assertFails { manager.prepareConfinedReadBootstrap() }
                        assertEquals(1, effects.installs)
                        if (case == ReadinessCase.HelperLoss) {
                            assertTrue(process.destroyForcibly())
                            childExit.get(5, TimeUnit.SECONDS)
                            effects.injected = true
                        }
                        effects.armed = true
                        val preparation = runCatching {
                            runtime.prepareConfinedRead(reviewed, DomainLimits(1, 1), resources, context, reviewedSupport, HelperSupportLifetime.NoWriters, effects.observations)
                        }
                        effects.armed = false
                        if (case != ReadinessCase.Ready) {
                            val failure = requireNotNull(preparation.exceptionOrNull()) { "Fault admitted a worker: $case" }
                            assertTrue(effects.injected, "The selected trust-boundary fault must run")
                            assertEquals(1, effects.installs, "Readiness refusal must precede every worker install")
                            assertEquals(if (case == ReadinessCase.KnownWorkerLandlock) 1 else 0, effects.workerLandlocks)
                            runtime.drain(timeout)
                            val drained = runtime.drain(timeout)
                            if (drained is DrainReport.Incomplete) assertEquals(0, drained.liveWorkers)
                            childExit.get(5, TimeUnit.SECONDS)
                            assertFalse(process.isAlive)
                            assertFails { resources.validate() }
                            assertEquals(drained, runtime.drain(timeout))
                            assertEquals(0L, context.daemonProcess.children().use { it.count() }, "A spent helper must not be replaced")
                            println("MANAGED_REJECT case=$case cause=$failure worker-installs=0 helper=${helper.identity} physically-exited=true drain=$drained")
                            return
                        }
                        val domain = preparation.getOrThrow()
                        assertEquals(2, effects.installs)
                        assertTrue(effects.root0Verified, "Root0 must reach H and D must close its intermediate before root1 is sent")
                        assertTrue(process.isAlive)
                        val sealedHelper = HelperSecurityObservation().observeAncestry(
                            helperPid,
                            Tid(helperPid.value),
                            HelperSecurityObservation.Lineage.Bootstrap,
                        )
                        assertEquals(helper.identity, sealedHelper.identity)
                        verifyRelayRead(domain, readable, native, context.daemonProcess.pid(), helperPid.value.toLong())
                        val read = runtime.submit(domain, Callable { Files.readString(readable) })
                        assertEquals("managed-confined-read", read.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                        println("MANAGED_READY helper=${helper.identity} generation=${domain.generation} worker-read=true parent-exec-denied=true late-bootstrap-refused=true")
                        assertEquals(DrainReport.Complete, runtime.drain(timeout))
                        childExit.get(5, TimeUnit.SECONDS)
                        assertFalse(process.isAlive)
                        assertFails { resources.validate() }
                        assertEquals(DrainReport.Complete, runtime.drain(timeout))
                        assertEquals(DrainReport.Complete, runtime.drain(timeout))
                        println("MANAGED_RETIRED helper=${helper.identity} physically-exited=true repeated-drain=complete")
                    } finally {
                        effects.armed = false
                        runtime.drain(timeout)
                        runtime.drain(timeout)
                    }
                }
            }
        } finally {
            manager.stop()
            LinuxNative.resetToDefault()
        }
    }

    /** Faults replace only parent native receipt/observation effects; all process owners and installs are real. */
    private class ReadinessEffects(
        private val case: ReadinessCase,
        private val application: Path,
        private val real: NativeEngine,
    ) : NativeEngine by real {
        @Volatile var armed = false

        @Volatile var injected = false

        @Volatile var installs = 0

        @Volatile var workerLandlocks = 0
        var beforeRoot1: () -> Unit = {}
        var root0Verified = false
        private var held: FileDescriptor<*, FdState.Open, FdOwnership>? = null
        val observations = HelperSecurityObservation(readBounded = { path, limit ->
            val worker = Thread.currentThread().name.startsWith("mazewall-domain-") && !Thread.currentThread().name.endsWith("resource-retirement")
            if (armed && !injected && case == ReadinessCase.KnownWorkerLandlock && worker) {
                injected = true
                val narrower = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(application).build()
                ContainedExecutors.installOnCurrentThread(narrower).use { it.requireInstalled() }
            }
            if (armed && case == ReadinessCase.WorkerAncestry && worker && path.fileName.toString() == "timens_offsets") {
                injected = true
                "monotonic 1 0\nboottime 0 0\n".toByteArray()
            } else {
                Files.newInputStream(path).use { it.readNBytes(limit + 1) }
            }
        })
        override val raw = object : RawSyscallOperations by real.raw {
            override fun syscall(
                nr: Long,
                a1: NativeArg,
                a2: NativeArg,
                a3: NativeArg,
                a4: NativeArg,
                a5: NativeArg,
                a6: NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (armed && case == ReadinessCase.PeerPermission && nr == Arch.current().getsockopt.toLong() && Thread.currentThread().name.startsWith("mazewall-manager-")) {
                    injected = true
                    return LinuxNative.SyscallResult.Error(13, -1L)
                }
                val result = real.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                if (nr == Arch.current().seccompSyscallNumber.toLong() && a1.asLong == 1L && a3.asLong != 0L && result is LinuxNative.SyscallResult.Success && result.value == 0L) installs++
                if (nr == NativeConstants.LANDLOCK_RESTRICT_SELF_NR &&
                    Thread.currentThread().name.startsWith("mazewall-domain-") &&
                    result is LinuxNative.SyscallResult.Success &&
                    result.value == 0L
                ) {
                    workerLandlocks++
                }
                return result
            }
        }
        override val networking = object : NativeNetworking by real.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (armed && case == ReadinessCase.Ready) {
                    val header = MsghdrSegment(msg.native)
                    val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                    if (iov.getIovLen() == 133L && header.getMsgControllen() == 0L) {
                        val bytes = iov.getIovBase().reinterpret(133).toArray(ValueLayout.JAVA_BYTE)
                        val frame = ConfinedHelperProtocol.decode(bytes)
                        if (frame is Frame.Root && frame.tag == ConfinedHelperProtocol.RootTag.Offer && frame.index == 1) {
                            beforeRoot1()
                            root0Verified = true
                        }
                    }
                }
                return real.networking.sendmsg(sockfd, msg, flags)
            }

            override fun recvmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (armed && held === sockfd) return LinuxNative.SyscallResult.Error(11, -1L)
                val result = real.networking.recvmsg(sockfd, msg, flags)
                if (armed && !injected && result is LinuxNative.SyscallResult.Success && result.value == 15L) {
                    val header = MsghdrSegment(msg.native)
                    val iov = IovecSegment(header.getMsgIov().reinterpret(Layouts.IOVEC.byteSize()))
                    check(iov.getIovLen() >= 15L)
                    val data = iov.getIovBase().reinterpret(iov.getIovLen())
                    val tag = data.get(ValueLayout.JAVA_BYTE, 0).toInt() and 255
                    if (case == ReadinessCase.PrematureReady && tag == 0x54) {
                        data.set(ValueLayout.JAVA_BYTE, 0, 0x50.toByte())
                        injected = true
                    } else if (case == ReadinessCase.MissingReady && tag == 0x50) {
                        held = sockfd
                        injected = true
                        return LinuxNative.SyscallResult.Error(11, -1L)
                    }
                }
                return result
            }
        }
    }

    private fun verifyRelayRead(
        domain: InProcessDomain,
        path: Path,
        native: NativeEngine,
        daemonPid: Long,
        helperPid: Long,
    ) {
        // Qualification drives the retained parent's idle channel; public read routing belongs to B5/B6.
        fun field(
            owner: Any,
            name: String,
        ): Any =
            owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            requireNotNull(it.get(owner))
        }
        val plan = field(domain.resource, "helperPlan") as PreparedHelperReadPlan
        val session = field(domain.resource, "helperSession") as ConfinedHelperSession
        val channel = field(session, "channel") as ConfinedHelperChannel
        val deadline = ConfinedDeadline.mint(native) { 5_000_000_000 }
        val binding = plan.manifest.binding
        val request = ConfinedReadRequest(binding, 1, HelperPurpose.Application, 0, "file")
        channel.sendFrame(Frame.Read(request, deadline))
        assertEquals(Frame.Result(ConfinedHelperProtocol.ResultTag.Opened, binding, 1, deadline), channel.receiveFrame(deadline).frame)
        channel.sendFrame(Frame.Result(ConfinedHelperProtocol.ResultTag.Expected, binding, 1, deadline))
        channel.receiveDescriptor(deadline).use { quarantine ->
            assertEquals(null, channel.pollFrame(deadline), "No DONE while parent receipt is withheld")
            assertEquals(1, descriptorsFor(daemonPid, path), "Daemon retains its claimed intermediate")
            assertEquals(1, descriptorsFor(helperPid, path), "Helper retains its source until the upstream receipt")
            channel.sendFrame(Frame.Result(ConfinedHelperProtocol.ResultTag.Received, binding, 1, deadline))
            assertEquals(Frame.Result(ConfinedHelperProtocol.ResultTag.Done, binding, 1, deadline), channel.receiveFrame(deadline).frame)
            assertEquals(0, descriptorsFor(daemonPid, path))
            assertEquals(0, descriptorsFor(helperPid, path))
            val identity = HelperSecurityObservation().observeReadable(quarantine.handle, native)
            assertEquals(Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString(), identity)
            val bytes = NativeArena.ofConfined().use { arena ->
                val buffer = arena.allocate(64)
                val count = native.memory.read(quarantine.handle, buffer, 64).getOrThrow("read committed relay descriptor")
                check(count in 1..64)
                ByteArray(count.toInt()).also { bytes ->
                    ManagedSegment.copy(buffer, 0, bytes, 0, bytes.size)
                    assertEquals(0L, native.memory.read(quarantine.handle, buffer, 64).getOrThrow("committed relay EOF"))
                }
            }
            assertEquals("managed-confined-read", bytes.toString(Charsets.UTF_8))
            println("MANAGED_RELAY identity=$identity bytes=${bytes.size} EOF=true before-receipt-DONE=false intermediate-before=1 helper-before=1 after=0")
        }
    }

    private fun descriptorsFor(
        pid: Long,
        path: Path,
    ): Int =
        Files.list(Path.of("/proc/$pid/fd")).use { descriptors ->
        descriptors.filter { Files.readSymbolicLink(it).toString() == path.toString() }.count().toInt()
    }

    private fun helperClasspath(directory: Path): List<Path> =
        listOf(
        SocketHelperMain::class.java,
        LinuxNative::class.java,
        Unit::class.java,
        kotlinx.serialization.KSerializer::class.java,
        kotlinx.serialization.json.Json::class.java,
        kotlinx.coroutines.CoroutineScope::class.java,
        org.slf4j.LoggerFactory::class.java,
    ).map { type ->
        val location = type.protectionDomain.codeSource.location
        Path.of(location.toURI()).toRealPath()
    }.distinct()
        .mapIndexed { index, origin ->
        if (Files.isDirectory(origin)) {
            directory.resolve("runtime-$index.jar").also { jar ->
                JarOutputStream(Files.newOutputStream(jar)).use { output ->
                    Files.walk(origin).use { files ->
                        files.filter(Files::isRegularFile).sorted().forEach { path ->
                            output.putNextEntry(JarEntry(origin.relativize(path).toString()).also { it.time = 0 })
                            Files.copy(path, output)
                            output.closeEntry()
                        }
                    }
                }
            }
        } else {
            origin
        }
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
