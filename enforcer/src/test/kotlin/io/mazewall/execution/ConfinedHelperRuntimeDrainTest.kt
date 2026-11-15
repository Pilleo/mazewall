package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.execution.Deadline
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.LiveValidationTransport
import io.mazewall.enforcer.internal.execution.LiveWorkerSlot
import io.mazewall.enforcer.internal.execution.OwnedDomain
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
import io.mazewall.enforcer.internal.execution.ScopingPreparation
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperControl
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperPreparation
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperSession
import io.mazewall.enforcer.internal.supervisor.LiveListenerHandler
import io.mazewall.enforcer.internal.supervisor.SupervisorExecRewriteExchange
import io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.enforcer.internal.supervisor.SupervisorReplyInterpreter
import io.mazewall.enforcer.internal.supervisor.TraceeDescriptorImporter
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.internal.RecordingCapture
import io.mazewall.ffi.internal.assertActualCaptureFreezesInputs
import io.mazewall.ffi.internal.captureSynthetic
import io.mazewall.ffi.internal.captureSyntheticWithSupport
import io.mazewall.ffi.internal.captureWithMissingAgent
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.ffi.memory.readInt
import io.mazewall.ffi.memory.readString
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperControlTest
import io.mazewall.ffi.networking.SeccompInstallHandshake
import io.mazewall.ffi.networking.SeccompInstallationGate
import io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.InvocationId
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.LiveAbsentReason
import io.mazewall.platform.native.LiveFailure
import io.mazewall.platform.native.LiveFailureReason
import io.mazewall.platform.native.LiveFailureStatus
import io.mazewall.platform.native.LiveIncompleteReason
import io.mazewall.platform.native.LiveRead
import io.mazewall.platform.native.LiveSetup
import io.mazewall.platform.native.LiveStaleReason
import io.mazewall.platform.native.LiveUnsupportedReason
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeProcess
import io.mazewall.platform.native.PublicationId
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.native.TaskId
import io.mazewall.platform.native.TaskLease
import io.mazewall.platform.native.WorkerOwner
import io.mazewall.platform.native.getFdOrThrow
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.seccomp.daemon.PreludeOutcome
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.platform.seccomp.daemon.SeccompSessionHandler
import io.mazewall.platform.socket.NativeCloseFailure
import io.mazewall.platform.socket.RealSocketManager
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real runtime, owners, sockets and resource pins; only irreversible installs and H effects are controlled. */
class ConfinedHelperRuntimeDrainTest {
    private fun runtimeState(runtime: ManagedRuntime): Any =
        ManagedRuntime::class.java.getDeclaredField("shared").let {
        it.isAccessible = true
        it.get(runtime)
    }

    @TempDir lateinit var directory: Path
    private val timeout = Duration.ofSeconds(5)
    private val installs = AtomicInteger()
    private var beforeNative: (Long) -> Unit = {}
    private var beforePeerCredentials: () -> Unit = {}
    private var beforeConnect: () -> Unit = {}
    private lateinit var previousNative: NativeEngine
    private val injectOwnedCloseError = java.util.concurrent.atomic
        .AtomicBoolean()
    private val countOwnedCloses = java.util.concurrent.atomic
        .AtomicBoolean()
    private val fatalOwnedClose = java.util.concurrent.atomic
        .AtomicReference<Error?>()
    private val fatalOwnedCloseObserved = java.util.concurrent.atomic
        .AtomicBoolean()
    private val ownedCloseCalls = AtomicInteger()
    private val targetRuleset = java.util.concurrent.atomic
        .AtomicReference<Any>()
    private val rulesetCloseFailure = java.util.concurrent.atomic
        .AtomicReference<Error>()
    private val rulesetCloseCalls = AtomicInteger()
    private var skipFixtureResourceClose = false
    private val supportOpenPath = AtomicReference<String>()
    private val otherSupportOpenPath = AtomicReference<String>()
    private val otherSupportFd = AtomicLong(-1)
    private val otherSupportToken = AtomicReference<Any>()
    private val otherSupportCloseCalls = AtomicInteger()
    private val supportCloseOrder = CopyOnWriteArrayList<String>()
    private val otherSupportCloseSucceeded = AtomicBoolean()
    private var beforeSupportOpen: (String) -> Unit = {}
    private val supportFd = AtomicLong(-1)
    private val supportCloseCalls = AtomicInteger()
    private val supportToken = AtomicReference<Any>()
    private val injectSupportCloseError = AtomicBoolean()
    private val supportCloseThrow = AtomicReference<Throwable>()

    @BeforeEach
    fun nativeBoundary() {
        previousNative = LinuxNative.engineIdentity as NativeEngine
        val real = LinuxNative.snapshot()
        val raw = object : RawSyscallOperations by real.raw {
            override fun syscall(
                nr: Long,
                a1: NativeArg,
                a2: NativeArg,
                a3: NativeArg,
                a4: NativeArg,
                a5: NativeArg,
                a6: NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                beforeNative(nr)
                if (nr == Arch.current().getsockopt.toLong() && a2.asLong == 1L && a3.asLong == 17L) beforePeerCredentials()
                return when (nr) {
                Arch.current().seccompSyscallNumber.toLong() -> {
                    installs.incrementAndGet()
                    LinuxNative.SyscallResult.Success(0L)
                }
                NativeConstants.LANDLOCK_RESTRICT_SELF_NR -> LinuxNative.SyscallResult.Success(0L)
                else -> real.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }

            override fun syscall4(
                nr: Long,
                a1: NativeArg,
                a2: NativeArg,
                a3: NativeArg,
                a4: NativeArg,
            ) = syscall(nr, a1, a2, a3, a4, NativeArg.LongArg(0), NativeArg.LongArg(0))
        }
        LinuxNative.setEngine(object : NativeEngine by real {
            override val raw = raw
            override val networking = object : io.mazewall.platform.native.NativeNetworking by real.networking {
                override fun connect(
                    sockfd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    addr: ManagedSegment,
                    addrlen: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    beforeConnect()
                    return real.networking.connect(sockfd, addr, addrlen)
                }
            }
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by real.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val requested = path.readString(0)
                    beforeSupportOpen(requested)
                    val result = real.fileSystem.open(path, flags)
                    if (requested == supportOpenPath.get() && result is LinuxNative.SyscallResult.Success) {
                        check(supportFd.compareAndSet(-1, result.value))
                    }
                    if (requested == otherSupportOpenPath.get() && result is LinuxNative.SyscallResult.Success) {
                        check(otherSupportFd.compareAndSet(-1, result.value))
                    }
                    return result
                }

                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (countOwnedCloses.get()) ownedCloseCalls.incrementAndGet()
                    if (fd.value.toLong() == supportFd.get() && supportToken.get() == null) supportToken.compareAndSet(null, fd)
                    val supportTarget = supportToken.get() === fd
                    if (supportTarget) {
                        supportCloseCalls.incrementAndGet()
                        supportCloseOrder.add("target")
                    }
                    if (fd.value.toLong() == otherSupportFd.get() && otherSupportToken.get() == null) otherSupportToken.compareAndSet(null, fd)
                    if (otherSupportToken.get() === fd) {
                        otherSupportCloseCalls.incrementAndGet()
                        supportCloseOrder.add("other")
                    }
                    val target = targetRuleset.get() === fd
                    if (target) rulesetCloseCalls.incrementAndGet()
                    val actual = real.fileSystem.close(fd)
                    if (otherSupportToken.get() === fd) {
                        check(actual is LinuxNative.SyscallResult.Success)
                        otherSupportCloseSucceeded.set(true)
                    }
                    if (target) {
                        check(actual is LinuxNative.SyscallResult.Success) { "Target ruleset close did not succeed before injection" }
                        rulesetCloseFailure.getAndSet(null)?.let { throw it }
                    }
                    fatalOwnedClose.getAndSet(null)?.let { fatal ->
                        check(actual is LinuxNative.SyscallResult.Success)
                        fatalOwnedCloseObserved.set(true)
                        throw fatal
                    }
                    if (supportTarget) {
                        supportCloseThrow.getAndSet(null)?.let {
                            check(actual is LinuxNative.SyscallResult.Success)
                            throw it
                        }
                    }
                    if (injectOwnedCloseError.compareAndSet(true, false) || supportTarget && injectSupportCloseError.compareAndSet(true, false)) {
                        check(actual is LinuxNative.SyscallResult.Success)
                        return LinuxNative.SyscallResult.Error(5, -1L)
                    }
                    return actual
                }
            }
            override val process = object : NativeProcess by real.process {
                override fun prctl(command: PrctlCommand) = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(if (command is PrctlCommand.GetSeccomp) 2 else 0)
            }
        })
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName() = "Linux"

            override fun hasKernelSeccompSupport() = true

            override fun checkSeccompSanity() = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun probeSeccompTsync() = true

            override fun probeSeccompUserNotif() = true

            override fun probeCetSupported() = false
        })
    }

    @AfterEach
    fun resetNative() {
        LinuxNative.setEngine(previousNative)
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    private fun observations(parent: Boolean): HelperSecurityObservation {
        val leader = ProcessHandle.current().pid().toString()
        return HelperSecurityObservation(readBounded = { path, limit ->
            val original = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
            if (path.fileName.toString() != "status" || (path.parent.fileName.toString() != leader) != parent) {
                original
            } else {
                original
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .joinToString("\n") { line ->
                    when (line.substringBefore(':')) {
                        "NoNewPrivs" -> "NoNewPrivs:\t1"
                        "Seccomp" -> "Seccomp:\t2"
                        "Seccomp_filters" -> "Seccomp_filters:\t${line.substringAfter(':').trim().toLong() + 1}"
                        else -> line
                    }
                }.toByteArray()
            }
        })
    }

    private fun implementationJar(): Path {
        val source = directory.resolve("Operation.java")
        Files.writeString(source, "package fixture; public class Operation { public int call() { return 42; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()))
        val jar = directory.resolve("operation.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/Operation.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/Operation.class")))
            it.closeEntry()
        }
        return jar
    }

    private fun withRuntime(
        preparationTimeout: Duration = timeout,
        canonical: Boolean = false,
        retainOnExit: Boolean = false,
        sharedObservations: HelperSecurityObservation? = null,
        body: (RuntimeFixture) -> Unit,
    ) {
        val jar = implementationJar()
        ConfinedHelperControlTest.RelayFixture(directory, observations(parent = false), sharedObservations = sharedObservations).use { f ->
            f.Endpoint().use { endpoint ->
                URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                    val policy = WorkerPolicyBuilder
                        .denyList(RuntimeProfile.HOTSPOT_JIT)
                        .allowFsRead(f.roots[0].requested)
                        .allowFsRead(f.roots[1].requested)
                        .allowFsRead(jar)
                        .block(Syscall.CONNECT)
                        .build()
                    PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet()).let { resources ->
                        try {
                        val binding = OperationBinding("operation", OperationAdapter.CALLABLE, resources.implementationSha256, resources.resourceGeneration, mapOf("entryPoint" to "call()I"))
                        val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), binding)
                        val support = listOf(ReviewedHelperSupport(f.roots[1].requested, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("reviewed".toByteArray()))))
                        val owner = if (canonical) ManagedRuntime.newCanonicalAcquisition() else null
                        val runtime = owner?.start(preparationTimeout, timeout)
                            ?: ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, preparationTimeout, timeout)
                        val fixture = RuntimeFixture(f, endpoint, resources, reviewed, support, runtime, owner)
                        try {
                            body(fixture)
                        } finally {
                            f.failReap.set(false)
                            fixture.preparing?.let { thread ->
                                thread.interrupt()
                                thread.join(6000)
                                check(!thread.isAlive)
                            }
                            if (!retainOnExit) {
                                runtime.drain(timeout)
                                runtime.drain(timeout)
                            }
                        }
                        } finally {
                            if (!skipFixtureResourceClose && !retainOnExit) resources.close()
                        }
                    }
                }
            }
        }
    }

    private fun scopingMethod(): java.lang.reflect.Method {
        val effects = Class.forName("io.mazewall.enforcer.api.execution.ScopingTestEffects")
        return ManagedRuntime::class.java.getDeclaredMethod("prepareScoping", Policy::class.java, DomainLimits::class.java, PreparedStackScoping::class.java, effects).apply { isAccessible = true }
    }

    private fun scopingEffects(
        afterTypedStore: () -> Unit = {},
        waitFailureObserver: () -> Unit = {},
        afterDomainCommit: () -> Unit = {},
        beforeWait: () -> Unit = {},
    ): Any {
        val effects = Class.forName("io.mazewall.enforcer.api.execution.ScopingTestEffects")
        val constructor = effects
            .getDeclaredConstructor(
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        return constructor.newInstance(afterTypedStore, waitFailureObserver, afterDomainCommit, beforeWait)
    }

    private inner class RuntimeFixture(
        val relay: ConfinedHelperControlTest.RelayFixture,
        val endpoint: ConfinedHelperControlTest.RelayFixture.Endpoint,
        val resources: PreparedOperationResources,
        val reviewed: ReviewedOperationArtifact,
        val support: List<ReviewedHelperSupport>,
        val runtime: ManagedRuntime,
        val canonicalOwner: ManagedRuntime.CanonicalAcquisition?,
    ) {
        val prepared = CompletableFuture<InProcessDomain>()
        var preparing: Thread? = null

        // Scheduling barriers use the real bounded executor without adding production test hooks.
        val manager = ManagedRuntime::class.java.getDeclaredField("manager").let { field ->
            field.isAccessible = true
            field.get(runtime) as ThreadPoolExecutor
        }
        val managerThread = manager.submit(Callable { Thread.currentThread() }).get(2, TimeUnit.SECONDS)

        private fun field(
            owner: Any,
            name: String,
        ): Any {
            val member = owner.javaClass.getDeclaredField(name)
            member.isAccessible = true
            return requireNotNull(member.get(owner))
        }

        fun queuedPreparationQuiescence(queued: Runnable): CountDownLatch {
            val gate = field(runtime, "lock")
            return synchronized(gate) {
                val state = runtimeState(runtime)
                assertEquals(1, field(state, "preparations") as Int)
                assertEquals(1, manager.queue.size)
                assertSame(queued, manager.queue.single())
                val control = field(runtime, "legacyControl")
                assertSame(control, field(queued, "jobControl"))
                (field(control, "quiescence") as CountDownLatch).also { assertEquals(1L, it.count) }
            }
        }

        fun confinedReapAttempt(): CountDownLatch {
            val pool = relay.pool
            val gate = field(pool, "gate")
            return synchronized(gate) {
                // This fixture owns one prestarted pool and one confined session.
                val sessions = field(pool, "sessions") as Set<*>
                val session = requireNotNull(sessions.single())
                val jobs = field(session, "jobs") as Set<*>
                val job = requireNotNull(jobs.single())
                val work = assertIs<io.mazewall.enforcer.internal.supervisor.SocketHelperPool.Work.Confined>(field(job, "work"))
                val confined = field(session, "confined")
                assertSame(confined, work)
                assertSame(field(confined, "slot"), work.slot)
                (field(job, "reaping") as CountDownLatch).also { assertEquals(1L, it.count) }
            }
        }

        fun dedicatedRetirementThread(): Thread {
            val gate = field(runtime, "lock")
            return synchronized(gate) {
                val domains = field(runtime, "domains") as Map<*, *>
                val domain = assertIs<OwnedDomain>(domains.values.single())
                val retirement = field(domain, "retirement")
                field(retirement, "thread") as Thread
            }
        }

        fun helperPhysicallyRetired(): Boolean {
            val owner = checkNotNull(canonicalOwner)
            val operations = runtime.javaClass
                .getDeclaredField("operations")
                .apply { isAccessible = true }
                .get(runtime)
            val permit = owner.javaClass
                .getDeclaredField("canonicalPermit")
                .apply { isAccessible = true }
                .get(owner)
            return operations.javaClass
                .getDeclaredMethod(
                    "canonicalTransferredHelperRetired",
                    Any::class.java,
                    owner.javaClass,
                    io.mazewall.enforcer.internal.supervisor.SupervisorContext::class.java,
                ).apply { isAccessible = true }
                .invoke(operations, permit, owner, endpoint.context) as Boolean
        }

        fun prepare(reviewedSupport: List<ReviewedHelperSupport> = support) =
            runtime.prepareConfinedRead(
                reviewed,
                DomainLimits(1, 1),
                resources,
                endpoint.context,
                reviewedSupport,
                HelperSupportLifetime.NoWriters,
                observations(parent = true),
            )

        fun startPreparation() {
            preparing = Thread.ofPlatform().start {
                try {
                    prepared.complete(prepare())
                } catch (failure: Throwable) {
                    prepared.completeExceptionally(failure)
                }
            }
        }

        fun completeSetup(): InProcessDomain {
            val cap = ConfinedDeadline.mint(relay.native) { 5_000_000_000 }
            val setup = assertIs<Frame.Setup>(relay.helper.receiveFrame(cap).frame)
            assertEquals(1, installs.get(), "Only the baseline may precede Ready")
            relay.helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
            setup.manifest.capabilities.forEachIndexed { index, capability ->
                assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), relay.helper.receiveFrame(cap).frame)
                relay.helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
                relay.helper.receiveDescriptor(cap).use { assertEquals(capability.identity, relay.observations.observeRoot(it.handle)) }
                relay.helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
            }
            assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), relay.helper.receiveFrame(cap).frame)
            assertEquals(1, installs.get(), "Root COMPLETE is not readiness")
            relay.helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
            return prepared.get(3, TimeUnit.SECONDS).also { assertEquals(2, installs.get()) }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val fixture = ConfinedHelperRuntimeDrainTest()
            fixture.directory = Path.of(args[0])
            try {
                fixture.nativeBoundary()
                when {
                    args[1] == "receiver_retirement" -> fixture.receiverRetirementProbe()
                    args[1] == "b6_unprepared_receiver" || args[1].startsWith("b6_handshake_frame_") -> fixture.standaloneTransportProbe(args[1])
                    args[1].startsWith("task_begin") -> fixture.liveInvocationBeginProbe(args[1])
                    args[1].startsWith("b6_handshake") -> fixture.liveHandshakeProbe(args[1])
                    args[1] == "history_only" -> fixture.historyOnlyProbe()
                    args[1] == "worker_refused" -> fixture.refusedWorkerRegistrationProbe(false)
                    args[1] == "worker_wrong_identity" -> fixture.refusedWorkerRegistrationProbe(true)
                    args[1].startsWith("worker_retire_") -> fixture.workerRetirementIncompleteProbe(args[1])
                    args[1].startsWith("live_session") -> fixture.liveSessionRetirementProbe(args[1])
                    else -> fixture.failedSupportCloseProbe(args[1])
                }
                kotlin.system.exitProcess(0)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                kotlin.system.exitProcess(1)
            }
        }
    }

    @Test
    fun `transferred live plan reaches exact Ready before B6 refusal`() = runSupportCloseProbe("history_only")

    @Test
    fun `synthetic original worker receipt reaches the confined listener consumer`() = runSupportCloseProbe("b6_handshake")

    @Test
    fun `drain revokes an unsent original worker receipt`() = runSupportCloseProbe("b6_handshake_drained")

    @Test
    fun `exited worker cannot send an unsent original receipt`() = runSupportCloseProbe("b6_handshake_worker_exit")

    @Test
    fun `drain after peer credentials prevents original receipt handoff`() = runSupportCloseProbe("b6_handshake_after_auth")

    @Test
    fun `drain after prelude prevents original receipt handoff`() = runSupportCloseProbe("b6_handshake_after_write")

    @Test
    fun `original Live session denies observation without a task and stays usable`() = runSupportCloseProbe("b6_handshake_observe_empty")

    @Test
    fun `original Live notification observes its owned task and still replies EPERM`() = runSupportCloseProbe("b6_handshake_observe_task")

    @Test
    fun `reactor deadline denies once while Live observation is blocked`() = runSupportCloseProbe("b6_handshake_observe_task_timeout")

    @Test
    fun `lost original Live endpoint fails queued work`() = runSupportCloseProbe("b6_handshake_endpoint_loss")

    @Test
    fun `receipt cannot install endpoint after original worker exits`() = runSupportCloseProbe("b6_handshake_ack_worker_exit")

    @Test
    fun `Live observation accepts a fragmented frame within one deadline`() = runSupportCloseProbe("b6_handshake_frame_short")

    @Test
    fun `Live observation accepts an active prefix lasting beyond one idle interval`() = runSupportCloseProbe("b6_handshake_frame_slow")

    @Test
    fun `Live observation survives idle polling before the next frame`() = runSupportCloseProbe("b6_handshake_frame_idle")

    @Test
    fun `Live observation poisons a timed out partial frame`() = runSupportCloseProbe("b6_handshake_frame_partial")

    @Test
    fun `Live observation poisons a replayed sequence`() = runSupportCloseProbe("b6_handshake_frame_replay")

    @Test
    fun `Live observation poisons an unsupported frame version`() = runSupportCloseProbe("b6_handshake_frame_malformed")

    @Test
    fun `Live observation poisons sequence overflow and reuse`() = runSupportCloseProbe("b6_handshake_frame_overflow")

    @Test
    fun `endpoint loss during Begin ends the exact task without entering its body`() = runSupportCloseProbe("b6_handshake_observe_task_loss_begin")

    @Test
    fun `changed publication between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_publication")

    @Test
    fun `changed invocation between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_invocation")

    @Test
    fun `wrong snapshot binding denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_wrong_binding")

    @Test
    fun `wrong notification TID denies without copying`() = runSupportCloseProbe("b6_handshake_observe_task_wrong_tid")

    @Test
    fun `uninstalled endpoint cannot observe a published task`() = runSupportCloseProbe("b6_handshake_observe_task_notinstalled")

    @Test
    fun `recovered Begin remains unobservable during exact End`() = runSupportCloseProbe("b6_handshake_observe_task_recovered_end")

    @Test
    fun `pending End remains unobservable`() = runSupportCloseProbe("b6_handshake_observe_task_pending_end")

    @Test
    fun `busy snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_busy")

    @Test
    fun `absent snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_absent")

    @Test
    fun `incomplete snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_incomplete")

    @Test
    fun `stale snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_stale")

    @Test
    fun `unsupported snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_unsupported")

    @Test
    fun `foreign task snapshot denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_foreign")

    @Test
    fun `changed frame between copies denies observation`() = runSupportCloseProbe("b6_handshake_observe_task_changed_frame")

    @Test
    fun `notification bytes are owned before reactor mutation`() = runSupportCloseProbe("b6_handshake_observe_task_input_mutation")

    @Test
    fun `task End during first copy revokes observation`() = runSupportCloseProbe("b6_handshake_observe_task_copy_end")

    @Test
    fun `cancellation during first copy revokes observation`() = runSupportCloseProbe("b6_handshake_observe_task_copy_cancel")

    @Test
    fun `endpoint loss during first copy prevents a matched result`() = runSupportCloseProbe("b6_handshake_observe_task_copy_loss")

    @Test
    fun `live installation lifetime exposes no public thread returning method`() {
        val lifetimeType = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")
        val exposed = lifetimeType
            .declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) && it.returnType == Thread::class.java
            }
        assertTrue(exposed.isEmpty(), "Live lifetime exposes receiver threads: $exposed")
    }

    @Test
    fun `receiver physical exit precedes original domain retirement`() = runSupportCloseProbe("receiver_retirement")

    // The socket and task are genuine component authorities. This does not exercise a kernel notification.
    private fun receiverRetirementProbe() =
        withRuntime { fixture ->
            skipFixtureResourceClose = true
            val recording = RecordingCapture(completeCapture = true)
            val invalidations = AtomicInteger()
            recording.beforeInvalidate = { invalidations.incrementAndGet() }
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(fixture.runtime, fixture.reviewed, fixture.resources, recording, helperContext = fixture.endpoint.context) {},
            )
            val preparation = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as ScopingPreparation
            }
            val frozen = assertIs<ScopingPreparation.State.Ready>(preparation.state).frozen
            val domainReady = CompletableFuture<OwnedDomain>()
            val receiptReady = CompletableFuture<LiveValidationTransport>()
            val invocationReady = CompletableFuture<io.mazewall.enforcer.internal.execution.OwnedInvocation<Int>>()
            val lifetimeReady = CompletableFuture<Any>()
            val closeFailure = CompletableFuture<Throwable>()
            val copyEntered = CountDownLatch(1)
            val copyRelease = CountDownLatch(1)
            val taskEntered = CountDownLatch(1)
            val taskRelease = CountDownLatch(1)
            val callerResult = CompletableFuture<Throwable?>()
            val lifetimeType = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")

            fun invoke(
                lifetime: Any,
                name: String,
                vararg values: Any,
            ): Any? =
                lifetimeType.declaredMethods
                    .single { it.name == name && it.parameterCount == values.size }
                    .apply { isAccessible = true }
                    .invoke(lifetime, *values)

            fun field(
                owner: Any,
                name: String,
            ): Any? =
                owner.javaClass.getDeclaredField(name).let {
                    it.isAccessible = true
                    it.get(owner)
                }
            recording.copyResult = { _, _ ->
                copyEntered.countDown()
                while (copyRelease.count != 0L) {
                    try {
                        copyRelease.await()
                    } catch (_: InterruptedException) {
                        // Preserve the blocked physical receiver until its test owner releases it.
                    }
                }
                LiveRead.Absent(LiveAbsentReason.NoTask)
            }
            val caller = Thread.ofPlatform().start {
                callerResult.complete(
                    runCatching {
                        scopingMethod().invoke(
                            fixture.runtime,
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                            scopingEffects(afterDomainCommit = {
                                val domain = assertIs<ScopingPreparation.State.DomainOwned>(preparation.state).domain
                                val plan = field(domain, "helperPlan") as PreparedHelperReadPlan
                                val helperSetup = SupervisorInstaller.prepareConfinedHelper(
                                    plan,
                                    fixture.endpoint.context,
                                    fixture.resources.native,
                                    ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 },
                                    retain = { session ->
                                        domain.javaClass
                                            .getDeclaredField("helperSession")
                                            .apply { isAccessible = true }
                                            .set(domain, session)
                                    },
                                    observations = observations(parent = true),
                                )
                                if (helperSetup is ConfinedHelperPreparation.Rejected) {
                                    throw AssertionError("Original committed helper setup rejected", helperSetup.cause)
                                }
                                assertIs<ConfinedHelperPreparation.Ready>(helperSetup)
                                val slots = checkNotNull(field(domain, "liveWorkers"))
                                val slot = slots.javaClass
                                    .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                                    .apply { isAccessible = true }
                                    .invoke(slots, 0) as LiveWorkerSlot
                                val worker = Thread.ofPlatform().unstarted {
                                    try {
                                        val registered = slots.javaClass.declaredMethods
                                            .single { it.name == "register" }
                                            .apply { isAccessible = true }
                                            .invoke(slots, slot, frozen) as WorkerOwner
                                        RuntimeWorkerContext.enter(domain, frozen)
                                        RuntimeWorkerContext.bindLiveWorker(registered)
                                        try {
                                            receiptReady.complete(domain.liveWorkerAdmission.reserveLiveValidation(slot))
                                            invocationReady.get(15, TimeUnit.SECONDS).run()
                                            val lifetime = lifetimeReady.get(15, TimeUnit.SECONDS)
                                            val failure = assertFails { invoke(lifetime, "closeAndAwait") }
                                            closeFailure.complete((failure as? java.lang.reflect.InvocationTargetException)?.targetException ?: failure)
                                        } finally {
                                            RuntimeWorkerContext.exit()
                                        }
                                    } catch (failure: Throwable) {
                                        receiptReady.completeExceptionally(failure)
                                        closeFailure.completeExceptionally(failure)
                                    }
                                }
                                @Suppress("UNCHECKED_CAST")
                                (field(domain, "threads") as MutableList<Thread>).add(worker)
                                slot.thread = worker
                                worker.start()
                                domainReady.complete(domain)
                                val failure = closeFailure.get(20, TimeUnit.SECONDS)
                                worker.join(5000)
                                check(!worker.isAlive)
                                throw failure
                            }),
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                try {
                    completeLiveRelaySetup(fixture)
                } catch (failure: Throwable) {
                    val setupFailure = callerResult.get(1, TimeUnit.SECONDS)
                    throw AssertionError("Committed domain setup failed before relay setup", setupFailure ?: failure)
                }
                val domain = domainReady.get(5, TimeUnit.SECONDS)
                val originalPlan = field(domain, "helperPlan") as PreparedHelperReadPlan
                assertFalse(field(originalPlan, "closed") as Boolean)
                val receipt = receiptReady.get(5, TimeUnit.SECONDS)
                fixture.relay.connect(holdClose = true).use { live ->
                    @Suppress("UNCHECKED_CAST")
                    val socket = field(live.parent, "socket") as FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
                    NativeArena.ofConfined().use { arena ->
                        val listener = fixture.relay.native.fileSystem
                            .open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY)
                            .getFdOrThrow("synthetic owned listener", FileDescriptorRole.SeccompNotif)
                        SupervisorOwnedFd(listener, fixture.relay.native.fileSystem).use {
                            assertTrue(SupervisorSeccompNotifInstaller.transferLiveListener(fixture.relay.native, socket, listener, receipt, SeccompInstallHandshake({})))
                            val accepted = assertIs<PreludeOutcome.LiveListener>(live.loopEnded.get(5, TimeUnit.SECONDS))
                            val received = checkNotNull(RealSocketManager.capture(fixture.relay.native).recvDescriptor(live.accepted))
                            SupervisorOwnedFd(received, fixture.relay.native.fileSystem).use {
                                assertIs<ConfinedHelperControl.LiveAdmission>(accepted.admission).attach(live.accepted)
                                val worker = checkNotNull(recording.workerThread.get())
                                val lifetime = lifetimeType
                                    .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java, LiveValidationTransport::class.java)
                                    .apply { isAccessible = true }
                                    .newInstance(worker, fixture.relay.native, receipt)
                                val receiverReady = CountDownLatch(1)
                                val channel = io.mazewall.ffi.networking
                                    .ConfinedHelperChannel(fixture.relay.native, live.accepted, { false }, { true })
                                try {
                                    invoke(lifetime, "start")
                                    invoke(lifetime, "attach", socket, receiverReady)
                                    val deadline = ConfinedDeadline.mint(fixture.relay.native) { 30_000_000_000 }
                                    channel.send(byteArrayOf(0xac.toByte()), deadline)
                                    check(receiverReady.await(5, TimeUnit.SECONDS))
                                    val phase = domain.javaClass.getDeclaredField("phase").apply { isAccessible = true }
                                    phase.set(domain, phase.type.enumConstants.single { (it as Enum<*>).name == "READY" })
                                    val invocation = io.mazewall.enforcer.internal.execution.OwnedInvocation(
                                        domain,
                                        Callable {
                                            taskEntered.countDown()
                                            check(taskRelease.await(15, TimeUnit.SECONDS))
                                            42
                                        },
                                        checkNotNull(field(domain, "liveCancellationPermit")),
                                    )
                                    (field(domain, "admission") as java.util.concurrent.Semaphore).acquire()
                                    @Suppress("UNCHECKED_CAST")
                                    check((field(domain, "pending") as MutableSet<Any>).add(invocation))
                                    lifetimeReady.complete(lifetime)
                                    invocationReady.complete(invocation)
                                    check(taskEntered.await(5, TimeUnit.SECONDS))
                                    val request = java.nio.ByteBuffer
                                        .allocate(104)
                                        .putInt(0x4d574f42)
                                        .putInt(1)
                                        .putInt(1)
                                        .putInt(104)
                                        .putLong(1)
                                        .putLong(77)
                                        .putLong(deadline.nanos)
                                        .putInt(checkNotNull(recording.workerOwner.get()).tid.value)
                                        .putInt(257)
                                        .putInt(0xc000003e.toInt())
                                        .putInt(0)
                                        .apply { repeat(6) { putLong(0) } }
                                        .array()
                                    channel.send(request, deadline)
                                    check(copyEntered.await(5, TimeUnit.SECONDS))
                                    taskRelease.countDown()
                                    val timeoutFailure = closeFailure.get(8, TimeUnit.SECONDS)
                                    assertEquals("Socket helper cleanup timed out", timeoutFailure.message)
                                    caller.join(5000)
                                    check(!caller.isAlive)
                                    assertNotNull(callerResult.get(1, TimeUnit.SECONDS))
                                    val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(500)))
                                    assertNotNull(report.cleanupFailure)
                                    assertTrue((field(lifetime, "reactor") as Thread).isAlive)
                                    org.junit.jupiter.api.Assertions.assertAll(
                                        "original resources remain owned while receiver is alive",
                                        { assertFalse(field(originalPlan, "closed") as Boolean, "original helper plan closed") },
                                        { assertEquals(0, recording.workerRetireCalls.get(), "original worker retired") },
                                        { assertFalse(domain.retired(), "original domain retired") },
                                        { assertEquals(0, recording.lifecycle.count { it == "installation" }, "installation retired") },
                                        { assertEquals(0, fixture.relay.reaps.get(), "original helper reaped") },
                                        { fixture.resources.validate() },
                                    )
                                } finally {
                                    copyRelease.countDown()
                                    taskRelease.countDown()
                                    val receiver = field(lifetime, "reactor") as? Thread
                                    receiver?.join(10000)
                                    check(receiver == null || !receiver.isAlive)
                                }
                                val settled = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                                assertNotNull(settled.cleanupFailure)
                                assertEquals(1, recording.copyCalls.get())
                                assertEquals(1, recording.workerRetireCalls.get())
                                assertEquals(1, invalidations.get())
                                assertEquals(1, recording.lifecycle.count { it == "installation" })
                                assertEquals(2, recording.retireCalls.get())
                                assertSame(frozen.bootstrap.staging, recording.retiredOwner.get())
                                assertEquals(1, fixture.relay.reaps.get())
                                assertTrue(field(originalPlan, "closed") as Boolean)
                                assertFails { fixture.resources.validate() }
                            }
                        }
                    }
                }
            } finally {
                copyRelease.countDown()
                taskRelease.countDown()
                caller.join(10000)
                check(!caller.isAlive)
            }
        }

    @Test
    fun `bound receiver without prepared retirement is refused and closes its socket once`() = runSupportCloseProbe("b6_unprepared_receiver")

    private fun standaloneTransportProbe(mode: String) {
        val shared = HelperSecurityObservation()
        withRuntime(sharedObservations = shared) { fixture ->
            // Reflection composes one synthetic domain with its own exact real relay plan and session;
            // it does not establish protected startup, native provenance or production custody.
            assertSame(shared, fixture.relay.observations)
            assertSame(shared, fixture.relay.controlObservationsUsed)
            val ownerBinding = fixture.relay.manifest.binding.owner
            val observed = shared.observeAncestry(
                ownerBinding.ownerPid,
                Tid(ownerBinding.ownerPid.value),
                HelperSecurityObservation.Lineage.Bootstrap,
            )
            assertEquals(ownerBinding.ownerStartTicks, observed.identity.startTimeTicks)
            assertEquals(fixture.relay.manifest.ancestry.canonical, observed.ancestry.canonical)
            val recording = RecordingCapture(completeCapture = true)
            val prepared = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val preparation = runtimeState(fixture.runtime)
                .javaClass
                .getDeclaredField("scopingPreparation")
                .apply { isAccessible = true }
                .get(runtimeState(fixture.runtime)) as ScopingPreparation
            val frozen = assertIs<ScopingPreparation.State.Ready>(preparation.state).frozen
            val policy = fixture.relay
                .javaClass
                .getDeclaredField("policy")
                .apply { isAccessible = true }
                .get(fixture.relay) as CanonicalPolicyArtifact.Worker
            val domain = OwnedDomain(
                fixture.relay.manifest.binding.owner.domainGeneration,
                DomainLimits(1, 1),
                policy.policy.definition,
                preparation.environment,
                null,
                null,
                "synthetic-b6-handshake-",
                frozen,
            )
            val session = CompletableFuture<ConfinedHelperSession>()
            val setup = CompletableFuture<ConfinedHelperPreparation>()
            val setupThread = Thread.ofPlatform().start {
                try {
                    setup.complete(
                        SupervisorInstaller.prepareConfinedHelper(
                            fixture.relay.plan,
                            fixture.endpoint.context,
                            fixture.relay.native,
                            ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 },
                            { session.complete(it) },
                            observations = fixture.relay.observations,
                        ),
                    )
                } catch (failure: Throwable) {
                    setup.completeExceptionally(failure)
                }
            }
            try {
                completeLiveRelaySetup(fixture)
            } catch (failure: Throwable) {
                val outcome = try {
                    setup.get(1, TimeUnit.SECONDS)
                } catch (setupFailure: Throwable) {
                    throw AssertionError("Original helper setup failed before relay setup", setupFailure)
                }
                if (outcome is ConfinedHelperPreparation.Rejected) {
                    throw AssertionError("Original helper setup rejected before relay setup", outcome.cause)
                }
                throw failure
            }
            assertIs<ConfinedHelperPreparation.Ready>(setup.get(5, TimeUnit.SECONDS))
            setupThread.join(5000)
            check(!setupThread.isAlive)
            val originalSession = session.get(1, TimeUnit.SECONDS)
            check(originalSession.matchesOriginal(fixture.relay.plan, fixture.endpoint.context))
            domain
                .javaClass
                .getDeclaredField("helperPlan")
                .apply { isAccessible = true }
                .set(domain, fixture.relay.plan)
            domain
                .javaClass
                .getDeclaredField("helperSession")
                .apply { isAccessible = true }
                .set(domain, originalSession)
            val slots = domain
                .javaClass
                .getDeclaredField("liveWorkers")
                .apply { isAccessible = true }
                .get(domain)
            val slot = slots
                .javaClass
                .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(slots, 0) as LiveWorkerSlot
            val receipt = CompletableFuture<LiveValidationTransport>()
            val release = CountDownLatch(1)
            val worker = Thread.ofPlatform().unstarted {
                try {
                    val owner = frozen.bootstrap.sealed
                    val registration = recording.contexts.prepareWorkerReceipt(owner)
                    slot.receipt = registration
                    slot.registrationAttempted = true
                    val registered = assertIs<LiveSetup.Ready<WorkerOwner>>(
                        recording.contexts.registerCurrent(owner, registration),
                    ).value
                    slot.tid = registered.tid
                    slot.worker = registered
                    slot.registrationReserved = true
                    RuntimeWorkerContext.enter(domain, frozen)
                    RuntimeWorkerContext.bindLiveWorker(registered)
                    try {
                        val sessionField = domain
                            .javaClass
                            .getDeclaredField("helperSession")
                            .apply { isAccessible = true }
                        val copiedContext = fixture.endpoint.context.copy()
                        check(copiedContext == fixture.endpoint.context && copiedContext !== fixture.endpoint.context)
                        val foreignSession = ConfinedHelperSession(
                            fixture.relay.plan,
                            copiedContext,
                            fixture.relay.native,
                            { false },
                            fixture.relay.observations,
                        )
                        sessionField.set(domain, foreignSession)
                        val failure = assertFailsWith<IllegalStateException> { domain.liveWorkerAdmission.reserveLiveValidation(slot) }
                        assertEquals("Live validation original binding differs", failure.message)
                        sessionField.set(domain, originalSession)
                        receipt.complete(domain.liveWorkerAdmission.reserveLiveValidation(slot))
                        check(release.await(10, TimeUnit.SECONDS))
                    } finally {
                        RuntimeWorkerContext.exit()
                    }
                } catch (failure: Throwable) {
                    receipt.completeExceptionally(failure)
                }
            }
            @Suppress("UNCHECKED_CAST")
            (
                domain.javaClass
                    .getDeclaredField("threads")
                    .apply { isAccessible = true }
                    .get(domain) as MutableList<Thread>
            ).add(worker)
            slot.thread = worker
            worker.start()
            try {
                val originalReceipt = receipt.get(5, TimeUnit.SECONDS)
                fixture.relay.connect(holdClose = true).use { live ->
                    @Suppress("UNCHECKED_CAST")
                    val socket = live.parent.javaClass
                        .getDeclaredField("socket")
                        .apply { isAccessible = true }
                        .get(live.parent) as FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
                    NativeArena.ofConfined().use { arena ->
                        val listener = fixture.relay.native.fileSystem
                            .open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY)
                            .getFdOrThrow("synthetic owned listener", FileDescriptorRole.SeccompNotif)
                        SupervisorOwnedFd(listener, fixture.relay.native.fileSystem).use {
                            assertTrue(
                                SupervisorSeccompNotifInstaller.transferLiveListener(
                                    fixture.relay.native,
                                    socket,
                                    listener,
                                    originalReceipt,
                                    SeccompInstallHandshake({}),
                                ),
                            )
                            val accepted = assertIs<PreludeOutcome.LiveListener>(live.loopEnded.get(5, TimeUnit.SECONDS))
                            val received = checkNotNull(RealSocketManager.capture(fixture.relay.native).recvDescriptor(live.accepted))
                            SupervisorOwnedFd(received, fixture.relay.native.fileSystem).use {
                                assertIs<ConfinedHelperControl.LiveAdmission>(accepted.admission).attach(live.accepted)
                                if (mode.startsWith("b6_handshake_frame_")) {
                                    runUnboundFrameProbe(mode, worker, socket, live.accepted, fixture.relay.native, recording)
                                    return@withRuntime
                                }
                                val closes = AtomicInteger()
                                val native = object : NativeEngine by fixture.relay.native {
                                    override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by fixture.relay.native.fileSystem {
                                        override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                                            if (fd === socket) closes.incrementAndGet()
                                            return fixture.relay.native.fileSystem
                                                .close(fd)
                                        }
                                    }
                                }
                                val lifetimeType = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")
                                val lifetime = lifetimeType
                                    .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java, LiveValidationTransport::class.java)
                                    .apply { isAccessible = true }
                                    .newInstance(worker, native, originalReceipt)
                                val ready = CountDownLatch(1)
                                val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                                    lifetimeType
                                        .getDeclaredMethod("attach", FileDescriptor::class.java, CountDownLatch::class.java)
                                        .apply { isAccessible = true }
                                        .invoke(lifetime, socket, ready)
                                }
                                assertEquals("Live receiver has no current retirement owner", failure.targetException.message)
                                assertEquals(0L, ready.count)
                                assertEquals(1, closes.get())
                                assertNull(lifetimeType.getDeclaredField("reactor").apply { isAccessible = true }.get(lifetime))
                                val closeFailure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                                    lifetimeType.getDeclaredMethod("closeAndAwait").apply { isAccessible = true }.invoke(lifetime)
                                }
                                assertSame(failure.targetException, closeFailure.targetException)
                                assertEquals(1, closes.get())
                            }
                        }
                    }
                }
            } finally {
                release.countDown()
                worker.join(10000)
                check(!worker.isAlive)
                recording.workerOwner.get()?.let { recording.contexts.retireWorker(it) }
                originalSession.retire(ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 })
                prepared.value.close()
            }
        }
    }

    // Unbound framing controls own no domain endpoint or receiver retirement handle.
    private fun runUnboundFrameProbe(
        mode: String,
        worker: Thread,
        socket: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        accepted: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        native: NativeEngine,
        recording: RecordingCapture,
    ) {
        val shortRead = CountDownLatch(1)
        val receiverNative = if (mode == "b6_handshake_frame_short" || mode == "b6_handshake_frame_slow") {
            object : NativeEngine by native {
                override val networking = object : io.mazewall.platform.native.NativeNetworking by native.networking {
                    override fun recvmsg(
                        sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        msg: ManagedSegment,
                        flags: Int,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        val result = native.networking.recvmsg(sockfd, msg, flags)
                        if (sockfd === socket && result is LinuxNative.SyscallResult.Success && result.value == 19L) shortRead.countDown()
                        return result
                    }
                }
            }
        } else {
            native
        }
        val lifetimeType = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")
        val lifetime = lifetimeType
            .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java)
            .apply { isAccessible = true }
            .newInstance(worker, receiverNative)

        fun invoke(
            name: String,
            vararg values: Any,
        ) = lifetimeType.declaredMethods
            .single { it.name == name && it.parameterCount == values.size }
            .apply { isAccessible = true }
            .invoke(lifetime, *values)
        val poisoned = mode != "b6_handshake_frame_short" && mode != "b6_handshake_frame_slow" && mode != "b6_handshake_frame_idle"
        try {
            invoke("start")
            val ready = CountDownLatch(1)
            invoke("attach", socket, ready)
            val channel = io.mazewall.ffi.networking
                .ConfinedHelperChannel(native, accepted, { false }, { true })
            val deadline = ConfinedDeadline.mint(native) { 5_000_000_000 }
            channel.send(byteArrayOf(0xac.toByte()), deadline)
            check(ready.await(5, TimeUnit.SECONDS))

            fun frame(serial: Long): ByteArray =
                java.nio.ByteBuffer
                    .allocate(104)
                    .putInt(0x4d574f42)
                    .putInt(1)
                    .putInt(1)
                    .putInt(104)
                    .putLong(serial)
                    .putLong(77L)
                    .putLong(deadline.nanos)
                    .putInt(checkNotNull(recording.workerOwner.get()).tid.value)
                    .putInt(257)
                    .putInt(0xc000003e.toInt())
                    .putInt(0)
                    .apply { repeat(6) { putLong(it.toLong()) } }
                    .array()
            val first = frame(if (mode == "b6_handshake_frame_overflow") Long.MAX_VALUE else 1)
            when (mode) {
                "b6_handshake_frame_short", "b6_handshake_frame_slow" -> {
                    channel.send(first.copyOfRange(0, 19), deadline)
                    check(shortRead.await(5, TimeUnit.SECONDS))
                    if (mode == "b6_handshake_frame_slow") assertFalse(CountDownLatch(1).await(1200, TimeUnit.MILLISECONDS))
                    channel.send(first.copyOfRange(19, first.size), deadline)
                    kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                    invoke("requireReusable")
                }
                "b6_handshake_frame_idle" -> {
                    assertFalse(CountDownLatch(1).await(1200, TimeUnit.MILLISECONDS))
                    channel.send(first, deadline)
                    kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                    invoke("requireReusable")
                }
                "b6_handshake_frame_partial" -> {
                    channel.send(first.copyOfRange(0, 40), deadline)
                    assertFails { channel.receive(108, deadline) }
                }
                "b6_handshake_frame_malformed" -> {
                    java.nio.ByteBuffer
                        .wrap(first)
                        .putInt(4, 2)
                    channel.send(first, deadline)
                    assertFails { channel.receive(108, deadline) }
                }
                "b6_handshake_frame_replay", "b6_handshake_frame_overflow" -> {
                    channel.send(first, deadline)
                    kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                    channel.send(frame(1), deadline)
                    assertFails { channel.receive(108, deadline) }
                }
                else -> error("Unknown frame case")
            }
            assertEquals(0, recording.copyCalls.get())
        } finally {
            try {
                if (poisoned) assertFails { invoke("closeAndAwait") } else invoke("closeAndAwait")
            } finally {
                val reactor = lifetimeType
                    .getDeclaredField("reactor")
                    .apply { isAccessible = true }
                    .get(lifetime) as? Thread
                reactor?.join(5000)
                check(reactor == null || !reactor.isAlive)
            }
        }
    }

    private fun liveHandshakeProbe(mode: String) =
        withRuntime { fixture ->
            skipFixtureResourceClose = true
            val recording = RecordingCapture(completeCapture = true)
            val prepared = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val preparation = runtimeState(fixture.runtime)
                .javaClass
                .getDeclaredField("scopingPreparation")
                .apply { isAccessible = true }
                .get(runtimeState(fixture.runtime)) as ScopingPreparation
            val frozen = assertIs<ScopingPreparation.State.Ready>(preparation.state).frozen
            val boundary = IllegalStateException("controlled committed handshake boundary")
            val result = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                result.complete(
                    runCatching {
                        scopingMethod().invoke(
                            fixture.runtime,
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            prepared.value,
                            scopingEffects(afterDomainCommit = {
                                val domain = assertIs<ScopingPreparation.State.DomainOwned>(preparation.state).domain
                                val plan = domain.javaClass
                                    .getDeclaredField("helperPlan")
                                    .apply { isAccessible = true }
                                    .get(domain) as PreparedHelperReadPlan
                                runCommittedHandshake(fixture, domain, frozen, plan, recording, mode)
                                throw boundary
                            }),
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                try {
                    completeLiveRelaySetup(fixture)
                } catch (failure: Throwable) {
                    val setupFailure = result.get(1, TimeUnit.SECONDS)
                    throw AssertionError("Committed handshake setup failed before relay setup", setupFailure ?: failure)
                }
                caller.join(35000)
                check(!caller.isAlive) { "Committed handshake did not finish" }
                val failure = result.get(1, TimeUnit.SECONDS)
                val actual = (failure as? java.lang.reflect.InvocationTargetException)?.targetException ?: failure
                if (actual !== boundary) throw AssertionError("Committed handshake failed", actual)
            } finally {
                caller.join(10000)
                check(!caller.isAlive)
            }
        }

    // Component composition only: the committed domain owns the plan, session, worker and retirement actor.
    private fun runCommittedHandshake(
        fixture: RuntimeFixture,
        domain: OwnedDomain,
        frozen: io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live,
        plan: PreparedHelperReadPlan,
        recording: RecordingCapture,
        mode: String,
    ) {
        val validationSocket = AtomicReference<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>()
        val drainedAfterCredentials = AtomicBoolean()
        val sentBytes = AtomicLong()
        val raw = object : RawSyscallOperations by fixture.relay.native.raw {
            override fun syscall(
                nr: Long,
                a1: NativeArg,
                a2: NativeArg,
                a3: NativeArg,
                a4: NativeArg,
                a5: NativeArg,
                a6: NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = fixture.relay.native.raw
                    .syscall(nr, a1, a2, a3, a4, a5, a6)
                if (mode == "b6_handshake_after_auth" &&
                    nr == Arch.current().getsockopt.toLong() &&
                    a1 is NativeArg.FdArg &&
                    a1.fd === validationSocket.get() &&
                    a2.asLong == 1L &&
                    a3.asLong == 17L &&
                    result is LinuxNative.SyscallResult.Success
                ) {
                    drainedAfterCredentials.set(true)
                    domain.beginDrain()
                }
                return result
            }
        }
        val networking = object : io.mazewall.platform.native.NativeNetworking by fixture.relay.native.networking {
            override fun sendmsg(
                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                msg: ManagedSegment,
                flags: Int,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                val result = fixture.relay.native.networking
                    .sendmsg(sockfd, msg, flags)
                if (mode == "b6_handshake_after_write" &&
                    sockfd === validationSocket.get() &&
                    result is LinuxNative.SyscallResult.Success &&
                    result.value > 0 &&
                    sentBytes.addAndGet(result.value) == 149L
                ) {
                    domain.beginDrain()
                }
                return result
            }
        }
        val sessionNative = object : NativeEngine by fixture.relay.native {
            override val raw = raw
            override val networking = networking
        }
        val session = CompletableFuture<ConfinedHelperSession>()
        val setup = SupervisorInstaller.prepareConfinedHelper(
            plan,
            fixture.endpoint.context,
            sessionNative,
            ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 },
            { session.complete(it) },
            observations = observations(parent = true),
        )
        if (setup is ConfinedHelperPreparation.Rejected) {
            throw AssertionError("Original committed helper setup rejected", setup.cause)
        }
        assertIs<ConfinedHelperPreparation.Ready>(setup)
        val originalSession = session.get(1, TimeUnit.SECONDS)
        check(originalSession.matchesOriginal(plan, fixture.endpoint.context))
        domain.javaClass
            .getDeclaredField("helperSession")
            .apply { isAccessible = true }
            .set(domain, originalSession)
        val slots = domain
            .javaClass
            .getDeclaredField("liveWorkers")
            .apply { isAccessible = true }
            .get(domain)
        val slot = slots
            .javaClass
            .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(slots, 0) as LiveWorkerSlot
        val receipt = CompletableFuture<LiveValidationTransport>()
        val release = CountDownLatch(1)
        val taskBody = CountDownLatch(1)
        val taskRelease = CountDownLatch(1)
        val copyEntered = CountDownLatch(1)
        val copyRelease = CountDownLatch(1)
        val copyResumed = CountDownLatch(1)
        val beginEntered = CountDownLatch(1)
        val beginRelease = CountDownLatch(1)
        val endEntered = CountDownLatch(1)
        val endRelease = CountDownLatch(1)
        val workerExit = CountDownLatch(1)
        val observationTask = AtomicReference<io.mazewall.enforcer.internal.execution.OwnedInvocation<Int>>()
        val worker = Thread.ofPlatform().unstarted {
            try {
                val registered = slots.javaClass.declaredMethods
                    .single { it.name == "register" }
                    .apply { isAccessible = true }
                    .invoke(slots, slot, frozen) as WorkerOwner
                RuntimeWorkerContext.enter(domain, frozen)
                RuntimeWorkerContext.bindLiveWorker(registered)
                try {
                    val sessionField = domain
                        .javaClass
                        .getDeclaredField("helperSession")
                        .apply { isAccessible = true }
                    val copiedContext = fixture.endpoint.context.copy()
                    check(copiedContext == fixture.endpoint.context && copiedContext !== fixture.endpoint.context)
                    val foreignSession = ConfinedHelperSession(
                        plan,
                        copiedContext,
                        fixture.relay.native,
                        { false },
                        fixture.relay.observations,
                    )
                    sessionField.set(domain, foreignSession)
                    val failure = assertFailsWith<IllegalStateException> { domain.liveWorkerAdmission.reserveLiveValidation(slot) }
                    assertEquals("Live validation original binding differs", failure.message)
                    sessionField.set(domain, originalSession)
                    receipt.complete(domain.liveWorkerAdmission.reserveLiveValidation(slot))
                    check(release.await(10, TimeUnit.SECONDS))
                    if (mode.startsWith("b6_handshake_observe_task")) {
                        checkNotNull(observationTask.get()).run()
                        if (mode.startsWith("b6_handshake_observe_task_copy_")) {
                            while (workerExit.count != 0L) {
                                try {
                                    workerExit.await()
                                } catch (_: InterruptedException) {
                                    // Keep the original socket owner alive until the observation finishes.
                                }
                            }
                        }
                    }
                } finally {
                    RuntimeWorkerContext.exit()
                }
            } catch (failure: Throwable) {
                receipt.completeExceptionally(failure)
            }
        }
        @Suppress("UNCHECKED_CAST")
        (
            domain.javaClass
                .getDeclaredField("threads")
                .apply { isAccessible = true }
                .get(domain) as MutableList<Thread>
        ).add(worker)
        slot.thread = worker
        worker.start()
        try {
            val originalReceipt = receipt.get(5, TimeUnit.SECONDS)
            assertEquals(fixture.endpoint.context.socketPath, originalReceipt.socketPath)
            assertFailsWith<IllegalStateException> { originalReceipt.requireCurrent() }
            val unsupportedNative = MockNativeEngine().apply {
                onSyscall = { _, _, _, _, _, _, _ -> error("unsupported Live install invoked a syscall") }
            }
            val filter = BpfFilter.build(
                Arch.current(),
                ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition,
            )
            assertFailsWith<UnsupportedKernelFeatureException> {
                SupervisorSeccompNotifInstaller.installLiveOwned(
                    native = unsupportedNative,
                    userNotifSupported = false,
                    socketPath = originalReceipt.socketPath,
                    filter = filter,
                    receipt = originalReceipt,
                    onFilterApplied = { error("unsupported Live install applied a filter") },
                    installationGate = SeccompInstallationGate.Direct,
                    onSocketConnected = { _, _ -> error("unsupported Live install connected a socket") },
                )
            }
            if (mode == "b6_handshake_after_auth" || mode == "b6_handshake_after_write") {
                fixture.relay.connect(holdClose = true).use { live ->
                    val socket = live.parent.javaClass
                        .getDeclaredField("socket")
                        .apply { isAccessible = true }
                        .get(live.parent)

                    @Suppress("UNCHECKED_CAST")
                    val ownedSocket = socket as FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
                    validationSocket.set(ownedSocket)
                    val failure = assertFailsWith<IllegalStateException> {
                        originalReceipt.send(ownedSocket, ConfinedDeadline.mint(fixture.relay.native) { 1_000_000_000 })
                    }
                    if (mode == "b6_handshake_after_auth") {
                        assertEquals("Live validation was revoked during authentication", failure.message)
                        assertTrue(drainedAfterCredentials.get())
                    } else {
                        assertEquals("Live validation was revoked during handoff", failure.message)
                        assertEquals(149L, sentBytes.get())
                        assertFailsWith<IllegalStateException> {
                            originalReceipt.send(ownedSocket, ConfinedDeadline.mint(fixture.relay.native) { 1_000_000_000 })
                        }
                    }
                    assertTrue(worker.isAlive)
                }
                return
            }
            if (mode != "b6_handshake" &&
                mode != "b6_handshake_observe_empty" &&
                !mode.startsWith("b6_handshake_observe_task") &&
                !mode.startsWith("b6_handshake_frame_") &&
                mode != "b6_handshake_endpoint_loss" &&
                mode != "b6_handshake_ack_worker_exit"
            ) {
                if (mode == "b6_handshake_drained") {
                    domain.beginDrain()
                    assertTrue(worker.isAlive)
                } else {
                    release.countDown()
                    worker.join(10000)
                    check(!worker.isAlive)
                }
                assertFailsWith<IllegalStateException> { originalReceipt.socketPath }
                fixture.relay.connect(holdClose = true).use { live ->
                    val socket = live.parent.javaClass
                        .getDeclaredField("socket")
                        .apply { isAccessible = true }
                        .get(live.parent)

                    @Suppress("UNCHECKED_CAST")
                    val ownedSocket = socket as FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
                    assertFailsWith<IllegalStateException> {
                        originalReceipt.send(ownedSocket, ConfinedDeadline.mint(fixture.relay.native) { 1_000_000_000 })
                    }
                    assertFalse(live.loopEnded.isDone)
                }
                return
            }
            fixture.relay.connect(holdClose = true).use { live ->
                val socket = live.parent
                    .javaClass
                    .getDeclaredField("socket")
                    .apply { isAccessible = true }
                    .get(live.parent)

                @Suppress("UNCHECKED_CAST")
                val ownedSocket = socket as FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>
                NativeArena.ofConfined().use { arena ->
                    val listener = fixture.relay.native.fileSystem
                        .open(arena.allocateFrom("/dev/null"), OpenFlags.RDONLY)
                        .getFdOrThrow("synthetic owned listener", FileDescriptorRole.SeccompNotif)
                    // A real owned regular FD is an SCM transport surrogate, not a seccomp listener.
                    SupervisorOwnedFd(listener, fixture.relay.native.fileSystem).use {
                        assertTrue(
                            SupervisorSeccompNotifInstaller.transferLiveListener(
                                fixture.relay.native,
                                ownedSocket,
                                listener,
                                originalReceipt,
                                SeccompInstallHandshake({}),
                            ),
                        )
                        val accepted = assertIs<PreludeOutcome.LiveListener>(live.loopEnded.get(5, TimeUnit.SECONDS))
                        val manager = RealSocketManager.capture(fixture.relay.native)
                        val received = checkNotNull(manager.recvDescriptor(live.accepted))
                        SupervisorOwnedFd(received, fixture.relay.native.fileSystem).use {
                            val liveAdmission = assertIs<ConfinedHelperControl.LiveAdmission>(accepted.admission)
                            liveAdmission.attach(live.accepted)
                            if (mode == "b6_handshake_observe_empty" ||
                                mode.startsWith("b6_handshake_observe_task") ||
                                mode.startsWith("b6_handshake_frame_") ||
                                mode == "b6_handshake_endpoint_loss" ||
                                mode == "b6_handshake_ack_worker_exit"
                            ) {
                                if (mode == "b6_handshake_ack_worker_exit") {
                                    release.countDown()
                                    worker.join(10000)
                                    check(!worker.isAlive)
                                    assertFails { originalReceipt.install(ownedSocket) }
                                    return
                                }
                                val lifetimeType = Class.forName("io.mazewall.enforcer.internal.supervisor.LiveInstallationLifetime")
                                val shortRead = CountDownLatch(1)
                                val lifetimeNative = if (mode == "b6_handshake_frame_short" || mode == "b6_handshake_frame_slow") {
                                    object : NativeEngine by fixture.relay.native {
                                        override val networking = object : io.mazewall.platform.native.NativeNetworking by fixture.relay.native.networking {
                                            override fun recvmsg(
                                                sockfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                                                msg: ManagedSegment,
                                                flags: Int,
                                            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                                                val result = fixture.relay.native.networking
                                                    .recvmsg(sockfd, msg, flags)
                                                if (sockfd === ownedSocket && result is LinuxNative.SyscallResult.Success && result.value == 19L) shortRead.countDown()
                                                return result
                                            }
                                        }
                                    }
                                } else {
                                    fixture.relay.native
                                }
                                val lifetime = if (mode != "b6_handshake_observe_empty" && mode != "b6_handshake_observe_task_notinstalled" && !mode.startsWith("b6_handshake_frame_")) {
                                    lifetimeType
                                        .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java, LiveValidationTransport::class.java)
                                        .apply { isAccessible = true }
                                        .newInstance(worker, lifetimeNative, originalReceipt)
                                } else {
                                    lifetimeType
                                        .getDeclaredConstructor(Thread::class.java, NativeEngine::class.java)
                                        .apply { isAccessible = true }
                                        .newInstance(worker, lifetimeNative)
                                }

                                fun invoke(
                                    name: String,
                                    vararg values: Any,
                                ) = lifetimeType.declaredMethods
                                    .single { method -> method.name == name && method.parameterCount == values.size }
                                    .apply { isAccessible = true }
                                    .invoke(lifetime, *values)
                                val ready = CountDownLatch(1)
                                val channel = io.mazewall.ffi.networking
                                    .ConfinedHelperChannel(fixture.relay.native, live.accepted, { false }, { true })
                                val poisonedFrame = mode.startsWith("b6_handshake_frame_") &&
                                    mode != "b6_handshake_frame_short" &&
                                    mode != "b6_handshake_frame_slow" &&
                                    mode != "b6_handshake_frame_idle"
                                AutoCloseable {
                                    try {
                                        if (poisonedFrame) assertFails { invoke("closeAndAwait") } else invoke("closeAndAwait")
                                    } finally {
                                        copyRelease.countDown()
                                        val reactor = lifetimeType.getDeclaredField("reactor").apply { isAccessible = true }.get(lifetime) as? Thread
                                        reactor?.join(5000)
                                        check(reactor == null || !reactor.isAlive) { "Original receiver did not physically exit" }
                                    }
                                }.use {
                                    invoke("start")
                                    invoke("attach", ownedSocket, ready)
                                    val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
                                    channel.send(byteArrayOf(0xac.toByte()), deadline)
                                    check(ready.await(5, TimeUnit.SECONDS))
                                    if (mode.startsWith("b6_handshake_frame_")) {
                                        fun frame(serial: Long): ByteArray =
                                            java.nio.ByteBuffer
                                                .allocate(104)
                                                .putInt(0x4d574f42)
                                                .putInt(1)
                                                .putInt(1)
                                                .putInt(104)
                                                .putLong(serial)
                                                .putLong(77L)
                                                .putLong(deadline.nanos)
                                                .putInt(checkNotNull(recording.workerOwner.get()).tid.value)
                                                .putInt(257)
                                                .putInt(0xc000003e.toInt())
                                                .putInt(0)
                                                .apply { repeat(6) { putLong(it.toLong()) } }
                                                .array()
                                        val first = frame(if (mode == "b6_handshake_frame_overflow") Long.MAX_VALUE else 1)
                                        when (mode) {
                                            "b6_handshake_frame_short", "b6_handshake_frame_slow" -> {
                                                channel.send(first.copyOfRange(0, 19), deadline)
                                                check(shortRead.await(5, TimeUnit.SECONDS))
                                                if (mode == "b6_handshake_frame_slow") {
                                                    assertFalse(CountDownLatch(1).await(1200, TimeUnit.MILLISECONDS))
                                                }
                                                channel.send(first.copyOfRange(19, first.size), deadline)
                                                kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                                                invoke("requireReusable")
                                            }
                                            "b6_handshake_frame_idle" -> {
                                                assertFalse(CountDownLatch(1).await(1200, TimeUnit.MILLISECONDS))
                                                channel.send(first, deadline)
                                                kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                                                invoke("requireReusable")
                                            }
                                            "b6_handshake_frame_partial" -> {
                                                channel.send(first.copyOfRange(0, 40), deadline)
                                                assertFails { channel.receive(108, deadline) }
                                            }
                                            "b6_handshake_frame_malformed" -> {
                                                java.nio.ByteBuffer
                                                    .wrap(first)
                                                    .putInt(4, 2)
                                                channel.send(first, deadline)
                                                assertFails { channel.receive(108, deadline) }
                                            }
                                            "b6_handshake_frame_replay", "b6_handshake_frame_overflow" -> {
                                                channel.send(first, deadline)
                                                kotlin.test.assertContentEquals(first, channel.receive(108, deadline).copyOfRange(0, 104))
                                                channel.send(frame(1), deadline)
                                                assertFails { channel.receive(108, deadline) }
                                            }
                                            else -> error("Unknown frame case")
                                        }
                                        assertEquals(0, recording.copyCalls.get())
                                        return@use
                                    }
                                    if (mode == "b6_handshake_endpoint_loss") {
                                        val phaseField = domain.javaClass
                                            .getDeclaredField("phase")
                                            .apply { isAccessible = true }
                                        phaseField.set(domain, phaseField.type.enumConstants.single { (it as Enum<*>).name == "READY" })
                                        val permit = domain.javaClass
                                            .getDeclaredField("liveCancellationPermit")
                                            .apply { isAccessible = true }
                                            .get(domain)
                                        val bodyEntries = AtomicInteger()
                                        val queued = io.mazewall.enforcer.internal.execution.OwnedInvocation(
                                            domain,
                                            Callable { bodyEntries.incrementAndGet() },
                                            permit,
                                        )
                                        val semaphore = domain.javaClass
                                            .getDeclaredField("admission")
                                            .apply { isAccessible = true }
                                            .get(domain) as java.util.concurrent.Semaphore
                                        semaphore.acquire()

                                        @Suppress("UNCHECKED_CAST")
                                        val pending = domain.javaClass
                                            .getDeclaredField("pending")
                                            .apply { isAccessible = true }
                                            .get(domain) as MutableSet<Any>
                                        check(pending.add(queued))
                                        invoke("close")
                                        assertIs<ExecutionEnd.NotStarted>(
                                            queued.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS),
                                        )
                                        assertEquals("FAILED", (phaseField.get(domain) as Enum<*>).name)
                                        assertEquals(0, bodyEntries.get())
                                        return@use
                                    }
                                    if (mode.startsWith("b6_handshake_observe_task")) {
                                        val inputs = frozen.bootstrap.javaClass
                                            .getDeclaredField("inputs")
                                            .apply { isAccessible = true }
                                            .get(frozen.bootstrap)

                                        @Suppress("UNCHECKED_CAST")
                                        val corridors = inputs.javaClass
                                            .getDeclaredField("corridors")
                                            .apply { isAccessible = true }
                                            .get(inputs) as List<List<java.lang.reflect.Executable>>
                                        val corridor = corridors.first()
                                        val actualBinding = if (corridor.first().name == "open0") {
                                            io.mazewall.platform.native.LiveBinding.Open0
                                        } else {
                                            io.mazewall.platform.native.LiveBinding.Openat0
                                        }
                                        val binding = if (mode == "b6_handshake_observe_task_wrong_binding") {
                                            when (actualBinding) {
                                                io.mazewall.platform.native.LiveBinding.Open0 -> io.mazewall.platform.native.LiveBinding.Openat0
                                                io.mazewall.platform.native.LiveBinding.Openat0 -> io.mazewall.platform.native.LiveBinding.Open0
                                            }
                                        } else {
                                            actualBinding
                                        }
                                        val frames = corridor.map { method ->
                                            io.mazewall.platform.native.LiveFrame(
                                                frozen.bootstrap.enrollment.methods
                                                    .single { it.identity == method }
                                                    .token,
                                                0,
                                            )
                                        }
                                        recording.copyResult = { lease, expected ->
                                            if ((mode == "b6_handshake_observe_task_timeout" || mode.startsWith("b6_handshake_observe_task_copy_")) && expected == null) {
                                                copyEntered.countDown()
                                                check(copyRelease.await(5, TimeUnit.SECONDS))
                                                copyResumed.countDown()
                                            }
                                            check(expected == null || expected == InvocationId(1))
                                            val unavailable = when (mode) {
                                                "b6_handshake_observe_task_busy" -> LiveRead.Busy
                                                "b6_handshake_observe_task_absent" -> LiveRead.Absent(LiveAbsentReason.NoTask)
                                                "b6_handshake_observe_task_incomplete" -> LiveRead.Incomplete(LiveIncompleteReason.CaptureFailed)
                                                "b6_handshake_observe_task_stale" -> LiveRead.Stale(LiveStaleReason.IdentityMismatch)
                                                "b6_handshake_observe_task_unsupported" -> LiveRead.Unsupported(LiveUnsupportedReason.AdapterNotImplemented)
                                                else -> null
                                            }
                                            unavailable ?: io.mazewall.platform.native.LiveRead.Available(
                                                io.mazewall.platform.native.LiveSnapshot(
                                                    if (mode == "b6_handshake_observe_task_foreign") TaskLease(lease.worker, TaskId(lease.task.value + 1)) else lease,
                                                    InvocationId(if (expected != null && mode == "b6_handshake_observe_task_changed_invocation") 2 else 1),
                                                    PublicationId(if (expected != null && mode == "b6_handshake_observe_task_changed_publication") 2 else 1),
                                                    binding,
                                                    if (expected != null && mode == "b6_handshake_observe_task_changed_frame") frames.map { it.copy(location = it.location + 1) } else frames,
                                                ),
                                            )
                                        }
                                        val phaseField = domain.javaClass
                                            .getDeclaredField("phase")
                                            .apply { isAccessible = true }
                                        phaseField.set(domain, phaseField.type.enumConstants.single { (it as Enum<*>).name == "READY" })
                                        val permit = domain.javaClass
                                            .getDeclaredField("liveCancellationPermit")
                                            .apply { isAccessible = true }
                                            .get(domain)
                                        val invocation = io.mazewall.enforcer.internal.execution.OwnedInvocation(
                                            domain,
                                            Callable {
                                                taskBody.countDown()
                                                if (mode != "b6_handshake_observe_task_pending_end") check(taskRelease.await(5, TimeUnit.SECONDS))
                                                42
                                            },
                                            permit,
                                        )
                                        val semaphore = domain.javaClass
                                            .getDeclaredField("admission")
                                            .apply { isAccessible = true }
                                            .get(domain) as java.util.concurrent.Semaphore
                                        semaphore.acquire()

                                        @Suppress("UNCHECKED_CAST")
                                        val pending = domain.javaClass
                                            .getDeclaredField("pending")
                                            .apply { isAccessible = true }
                                            .get(domain) as MutableSet<Any>
                                        check(pending.add(invocation))
                                        observationTask.set(invocation)
                                        if (mode == "b6_handshake_observe_task_recovered_end" || mode == "b6_handshake_observe_task_pending_end") {
                                            if (mode == "b6_handshake_observe_task_recovered_end") recording.taskBeginFailure.set(IllegalStateException("controlled Begin failure"))
                                            if (mode == "b6_handshake_observe_task_pending_end") recording.taskEndResult.set(recording.pendingRetireOutcome)
                                            recording.duringTaskEnd = {
                                                endEntered.countDown()
                                                check(endRelease.await(5, TimeUnit.SECONDS))
                                            }
                                        }
                                        if (mode == "b6_handshake_observe_task_loss_begin") {
                                            recording.afterTaskBegin = {
                                                beginEntered.countDown()
                                                while (beginRelease.count != 0L) {
                                                    try {
                                                        beginRelease.await()
                                                    } catch (_: InterruptedException) {
                                                        // The domain may interrupt the worker while physical Begin remains owned.
                                                    }
                                                }
                                            }
                                        }
                                        release.countDown()
                                        if (mode == "b6_handshake_observe_task_recovered_end" || mode == "b6_handshake_observe_task_pending_end") {
                                            check(endEntered.await(5, TimeUnit.SECONDS))
                                            val request = java.nio.ByteBuffer
                                                .allocate(104)
                                                .putInt(0x4d574f42)
                                                .putInt(1)
                                                .putInt(1)
                                                .putInt(104)
                                                .putLong(1)
                                                .putLong(77)
                                                .putLong(deadline.nanos)
                                                .putInt(checkNotNull(recording.workerOwner.get()).tid.value)
                                                .putInt(257)
                                                .putInt(0xc000003e.toInt())
                                                .putInt(0)
                                                .apply { repeat(6) { putLong(0) } }
                                                .array()
                                            channel.send(request, deadline)
                                            val reply = channel.receive(108, deadline)
                                            kotlin.test.assertContentEquals(request, reply.copyOfRange(0, 104))
                                            assertEquals(
                                                0,
                                                java.nio.ByteBuffer
                                                    .wrap(reply, 104, 4)
                                                    .int,
                                            )
                                            assertEquals(0, recording.copyCalls.get())
                                            endRelease.countDown()
                                            invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                                            assertEquals(1, recording.endCalls.get())
                                            return@use
                                        }
                                        if (mode == "b6_handshake_observe_task_loss_begin") {
                                            check(beginEntered.await(5, TimeUnit.SECONDS))
                                            invoke("close")
                                            beginRelease.countDown()
                                            invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                                            assertEquals(1L, taskBody.count)
                                            assertEquals(1, recording.endCalls.get())
                                            assertEquals(0, recording.copyCalls.get())
                                            return@use
                                        }
                                        check(taskBody.await(5, TimeUnit.SECONDS))
                                        NativeArena.ofConfined().use { arena ->
                                            val sends = AtomicInteger()
                                            val errors = CopyOnWriteArrayList<Int>()
                                            val raw = object : RawSyscallOperations by fixture.relay.native.raw {
                                                override fun <Req, Res> ioctl(
                                                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                                                    command: io.mazewall.ffi.IoctlCommand<Req, Res>,
                                                    arg: Req,
                                                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                                                    assertSame(received, fd)
                                                    val buffer = (arg as io.mazewall.ffi.TypedSegment<*>).segment
                                                    if (command.code == io.mazewall.ffi.IoctlCommand.SECCOMP_IOCTL_NOTIF_RECV.code) {
                                                        buffer.writeLong(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_ID_OFFSET, 77L)
                                                        val tid = checkNotNull(recording.workerOwner.get()).tid.value
                                                        buffer.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, if (mode == "b6_handshake_observe_task_wrong_tid") tid + 1 else tid)
                                                    } else {
                                                        assertEquals(io.mazewall.ffi.IoctlCommand.SECCOMP_IOCTL_NOTIF_SEND.code, command.code)
                                                        errors.add(buffer.readInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET))
                                                        sends.incrementAndGet()
                                                    }
                                                    return LinuxNative.SyscallResult.Success(0L)
                                                }
                                            }
                                            val native = object : NativeEngine by fixture.relay.native {
                                                override val raw = raw
                                            }
                                            val replies = SupervisorReplyInterpreter(
                                                native,
                                                received,
                                                SupervisorExecRewriteExchange(native, live.accepted),
                                                TraceeDescriptorImporter(native),
                                            )
                                            val actual = LiveListenerHandler(received, replies, native, liveAdmission)
                                            val capturedWork = AtomicReference<io.mazewall.platform.seccomp.daemon.NotificationWork>()
                                            val handler = object : io.mazewall.platform.seccomp.daemon.SeccompNotifHandler by actual {
                                                context(scope: NativeArena) override fun beginNotification(
                                                    notif: ManagedSegment,
                                                    resp: ManagedSegment,
                                                    listenerFd: FileDescriptor<FileDescriptorRole.SeccompNotif, FdState.Open, FdOwnership.Owned>,
                                                    socketFd: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
                                                    deadline: io.mazewall.platform.lifecycle.Deadline,
                                                ): io.mazewall.platform.seccomp.daemon.NotificationWork =
                                                    actual
                                                        .beginNotification(notif, resp, listenerFd, socketFd, deadline)
                                                        .also {
                                                            capturedWork.set(it)
                                                            if (mode == "b6_handshake_observe_task_input_mutation") {
                                                                notif.writeInt(io.mazewall.ffi.Layouts.SECCOMP_NOTIF_PID_OFFSET, 1)
                                                            }
                                                        }
                                            }
                                            var now = System.nanoTime()
                                            SeccompSessionHandler(
                                                live.accepted,
                                                received,
                                                handler,
                                                engine = native,
                                                nanoTime = { now },
                                            ).use { reactor ->
                                                val poll = arena.allocate(io.mazewall.ffi.Layouts.POLLFD, 2)
                                                PollFdSegment.of(poll).setRevents(NativeConstants.POLLIN)
                                                with(arena) { reactor.handleActiveListener(poll) }
                                                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                                                val blockCopy = mode == "b6_handshake_observe_task_timeout" || mode.startsWith("b6_handshake_observe_task_copy_")
                                                while (sends.get() == 0 && System.nanoTime() < end && (!blockCopy || copyEntered.count != 0L)) {
                                                    with(arena) { reactor.handleActiveListener(poll) }
                                                    Thread.yield()
                                                }
                                                if (mode == "b6_handshake_observe_task_timeout") {
                                                    assertEquals(0L, copyEntered.count)
                                                    now += TimeUnit.SECONDS.toNanos(31)
                                                    with(arena) { reactor.handleActiveListener(poll) }
                                                    copyRelease.countDown()
                                                    check(copyResumed.await(5, TimeUnit.SECONDS))
                                                    with(arena) { reactor.handleActiveListener(poll) }
                                                } else {
                                                    if (mode.startsWith("b6_handshake_observe_task_copy_")) {
                                                        assertEquals(0L, copyEntered.count)
                                                        when (mode) {
                                                            "b6_handshake_observe_task_copy_end" -> {
                                                                taskRelease.countDown()
                                                                invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                                                            }
                                                            "b6_handshake_observe_task_copy_cancel" -> invocation.requestCancellation()
                                                            "b6_handshake_observe_task_copy_loss" -> invoke("close")
                                                        }
                                                        copyRelease.countDown()
                                                        check(copyResumed.await(5, TimeUnit.SECONDS))
                                                        while (sends.get() == 0 && System.nanoTime() < end) {
                                                            try {
                                                                with(arena) { reactor.handleActiveListener(poll) }
                                                            } catch (expected: Exception) {
                                                                check(sends.get() == 1) { "Transport failed before terminal denial" }
                                                            }
                                                            Thread.yield()
                                                        }
                                                    }
                                                    val work = checkNotNull(capturedWork.get())
                                                    val observation = work.javaClass
                                                        .getDeclaredField("observation")
                                                        .apply { isAccessible = true }
                                                        .get(work)
                                                    val expected = if (mode == "b6_handshake_observe_task" || mode == "b6_handshake_observe_task_input_mutation") {
                                                        "ObservationMatched"
                                                    } else {
                                                        "ObservationDenied"
                                                    }
                                                    if (mode == "b6_handshake_observe_task_copy_loss") {
                                                        assertTrue(observation?.toString() != "ObservationMatched")
                                                    } else {
                                                        assertEquals(expected, observation.toString())
                                                    }
                                                    val expectedCopies = when (mode) {
                                                        "b6_handshake_observe_task_wrong_tid", "b6_handshake_observe_task_notinstalled" -> 0
                                                        "b6_handshake_observe_task_wrong_binding", "b6_handshake_observe_task_busy", "b6_handshake_observe_task_absent",
                                                        "b6_handshake_observe_task_incomplete", "b6_handshake_observe_task_stale", "b6_handshake_observe_task_unsupported",
                                                        "b6_handshake_observe_task_foreign",
                                                        -> 1
                                                        "b6_handshake_observe_task_copy_loss" -> null
                                                        else -> 2
                                                    }
                                                    expectedCopies?.let { assertEquals(it, recording.copyCalls.get()) }
                                                }
                                                assertEquals(listOf(-NativeConstants.EPERM), errors)
                                                assertEquals(1, sends.get())
                                            }
                                        }
                                        return@use
                                    }
                                    repeat(2) { index ->
                                        // Frozen v1 observation vector: header, sequence, notification,
                                        // absolute monotonic deadline, TID, raw nr/arch, reserved and six args.
                                        val request = java.nio.ByteBuffer
                                            .allocate(104)
                                            .putInt(0x4d574f42)
                                            .putInt(1)
                                            .putInt(1)
                                            .putInt(104)
                                            .putLong(index.toLong() + 1)
                                            .putLong(77L)
                                            .putLong(deadline.nanos)
                                            .putInt(checkNotNull(recording.workerOwner.get()).tid.value)
                                            .putInt(257)
                                            .putInt(0xc000003e.toInt())
                                            .putInt(0)
                                            .apply { repeat(6) { putLong(it.toLong()) } }
                                            .array()
                                        channel.send(request, deadline)
                                        val response = channel.receive(108, deadline)
                                        kotlin.test.assertContentEquals(request, response.copyOfRange(0, 104))
                                        assertEquals(
                                            0,
                                            java.nio.ByteBuffer
                                                .wrap(response, 104, 4)
                                                .int,
                                        )
                                    }
                                    assertEquals(0, recording.copyCalls.get())
                                    invoke("requireReusable")
                                }
                            }
                        }
                        if (mode == "b6_handshake_observe_empty" ||
                            mode.startsWith("b6_handshake_observe_task") ||
                            mode.startsWith("b6_handshake_frame_") ||
                            mode == "b6_handshake_endpoint_loss" ||
                            mode == "b6_handshake_ack_worker_exit"
                        ) {
                            return
                        }
                        originalReceipt.requireCurrent()
                        assertFailsWith<IllegalStateException> { originalReceipt.socketPath }
                        assertFails { originalReceipt.send(ownedSocket, ConfinedDeadline.mint(fixture.relay.native) { 1_000_000_000 }) }
                        release.countDown()
                        worker.join(10000)
                        check(!worker.isAlive)
                        assertFailsWith<IllegalStateException> { originalReceipt.requireCurrent() }
                    }
                }
            }
        } finally {
            workerExit.countDown()
            endRelease.countDown()
            beginRelease.countDown()
            copyRelease.countDown()
            taskRelease.countDown()
            release.countDown()
            worker.join(10000)
            check(!worker.isAlive)
        }
    }

    private fun historyOnlyProbe() =
        withRuntime(canonical = true) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val owner = checkNotNull(fixture.canonicalOwner)
            // This reflection-only composition models ownership transitions; it is not native provenance evidence.
            val historyHelper = io.mazewall.enforcer.internal.supervisor
                .SupervisorDaemonManagerTest()
                .attachSyntheticHistoryHelper(owner, fixture.endpoint.context)
            val unsafe = sun.misc.Unsafe::class.java
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null) as sun.misc.Unsafe
            val association = unsafe.allocateInstance(io.mazewall.ffi.internal.CheckedBootstrapAssociation::class.java)
            val associationContext = association.javaClass.getDeclaredField("helperContext").apply { isAccessible = true }
            associationContext.set(association, historyHelper.context)
            val originalAssociation = owner.javaClass.getDeclaredField("originalAssociation").apply { isAccessible = true }
            originalAssociation.set(owner, association)
            val helperManager = owner.javaClass.getDeclaredField("helperManager").apply { isAccessible = true }
            val ownerContext = owner.javaClass.getDeclaredField("startedHelperContext").apply { isAccessible = true }
            val managerContext = historyHelper.manager.javaClass
                .getDeclaredField("confinedOriginalContext")
                .apply { isAccessible = true }
            assertSame(historyHelper.manager, helperManager.get(owner))
            assertSame(historyHelper.context, ownerContext.get(owner))
            assertSame(historyHelper.context, managerContext.get(historyHelper.manager))
            val operations = fixture.runtime.javaClass
                .getDeclaredField("operations")
                .apply { isAccessible = true }
                .get(fixture.runtime)
            val permit = owner.javaClass
                .getDeclaredField("canonicalPermit")
                .apply { isAccessible = true }
                .get(owner)
            val physical = operations.javaClass
                .getDeclaredMethod(
                    "canonicalTransferredHelperRetired",
                    Any::class.java,
                    owner.javaClass,
                    io.mazewall.enforcer.internal.supervisor.SupervisorContext::class.java,
                ).apply { isAccessible = true }
            assertEquals(false, physical.invoke(operations, permit, owner, historyHelper.context))
            // The relay and capture are synthetic; this proves the domain/session protocol only.
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = historyHelper.context,
                ) {},
            )
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                val first = CompletableFuture.anyOf(fixture.endpoint.firstAccepted, outcome).get(5, TimeUnit.SECONDS)
                if (first is Throwable) throw AssertionError("B6 refused before helper SETUP", first)
                val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
                val helper = fixture.relay.helper
                val setup = assertIs<Frame.Setup>(helper.receiveFrame(deadline).frame)
                assertEquals(1, installs.get())
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
                setup.manifest.capabilities.forEachIndexed { index, capability ->
                    assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), helper.receiveFrame(deadline).frame)
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
                    helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, fixture.relay.observations.observeRoot(it.handle)) }
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
                }
                assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
                assertIs<UnsupportedOperationException>(outcome.get(5, TimeUnit.SECONDS))
                assertEquals(1, recording.registerCalls.get(), "exact Ready must reach current-worker registration")
                assertEquals(0, recording.beginCalls.get())
                assertEquals(1, installs.get(), "B6 refusal must precede worker restrictions")
                fixture.runtime.drain(timeout)
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
                historyHelper.process.setAlive(false)
                assertEquals(true, physical.invoke(operations, permit, owner, historyHelper.context))
                assertEquals(false, physical.invoke(operations, permit, owner, historyHelper.context.copy()))
                val foreign = ManagedRuntime.newCanonicalAcquisition()
                val rejected = assertFailsWith<java.lang.reflect.InvocationTargetException> { physical.invoke(operations, permit, foreign, historyHelper.context) }
                assertIs<IllegalStateException>(rejected.cause)
                assertEquals(1, recording.workerRetireCalls.get())
                assertFalse(recording.workerThread.get().isAlive)
                assertTrue(recording.lifecycle.indexOf("worker") < recording.lifecycle.indexOf("installation"))
                assertEquals(1, fixture.relay.reaps.get())
                val firstHistory = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
                val stopped = CompletableFuture<ManagedRuntimeAcquisitionStatus>()
                val stopper = Thread.ofPlatform().start { stopped.complete(owner.retire(timeout)) }
                try {
                    assertTrue(historyHelper.entered.await(5, TimeUnit.SECONDS))
                    val concurrent = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
                    assertSame(firstHistory.history, concurrent.history)
                    assertEquals(1, historyHelper.stops.get())
                } finally {
                    historyHelper.release.countDown()
                    stopper.join(6000)
                }
                assertFalse(stopper.isAlive)
                val final = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(stopped.get(1, TimeUnit.SECONDS))
                assertSame(firstHistory.history, final.history)
                assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
                assertEquals(1, historyHelper.stops.get())
            } finally {
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
        }

    @Test
    fun `synthetic installed live invocation begins before its physical body`() = runSupportCloseProbe("task_begin")

    @Test
    fun `recovered live begin failure ends its exact task without entering body`() = runSupportCloseProbe("task_begin_throw")

    @Test
    fun `fatal recovered live begin ends its exact task and retains identity`() = runSupportCloseProbe("task_begin_fatal")

    @Test
    fun `pending live task end closes admission and retains its exact task`() = runSupportCloseProbe("task_begin_pending")

    @Test
    fun `pending live task end retains invalidation failure and typed outcome`() = runSupportCloseProbe("task_begin_pending_throw")

    @Test
    fun `refused live task end closes admission and retains its exact task`() = runSupportCloseProbe("task_begin_refused")

    @Test
    fun `live delivery callback retains canonical context failure before task end`() = runSupportCloseProbe("task_begin_late")

    @Test
    fun `foreign live task lease is neither published nor ended`() = runSupportCloseProbe("task_begin_foreign")

    @Test
    fun `foreign recovered live task is neither published nor ended`() = runSupportCloseProbe("task_begin_foreign_recovered")

    @Test
    fun `live task delivery callback holds its lease until callback returns`() = runSupportCloseProbe("task_begin_callback_blocked")

    @Test
    fun `queued live cancellation invalidates once without task context`() = runSupportCloseProbe("task_begin_cancel")

    @Test
    fun `running live cancellation retains the task until its physical end`() = runSupportCloseProbe("task_begin_cancel_running")

    @Test
    fun `concurrent live cancellations share one invalidation`() = runSupportCloseProbe("task_begin_cancel_concurrent")

    @Test
    fun `live cancellation before worker invocation prevents begin`() = runSupportCloseProbe("task_begin_cancel_before_run")

    @Test
    fun `live cancellation delivers after invalidation throws without replay`() = runSupportCloseProbe("task_begin_cancel_throw")

    @Test
    fun `live dependent stage failure does not become invocation failure`() = runSupportCloseProbe("task_begin_dependent")

    @Test
    fun `live restoration and private inline body retain the same lease`() = runSupportCloseProbe("task_begin_context_inline")

    @Test
    fun `late ordinary failure and refused end retain both outcomes`() = runSupportCloseProbe("task_begin_late_refused")

    @Test
    fun `late ordinary failure and fatal end preserve fatal precedence`() = runSupportCloseProbe("task_begin_late_fatal_end")

    @Test
    fun `late fatal failure and pending end preserve both outcomes`() = runSupportCloseProbe("task_begin_fatal_pending")

    @Test
    fun `first invocation one shot end guard cannot clear the active second task`() = runSupportCloseProbe("task_begin_stale")

    @Test
    fun `revoked task tombstone rejects a different cleanup lease`() = runSupportCloseProbe("task_begin_tombstone")

    @Test
    fun `retained original lease remains endable before slot cleanup record`() = runSupportCloseProbe("task_begin_retained_frontier")

    @Test
    fun `retained slot lease remains endable before publication`() = runSupportCloseProbe("task_begin_retained_slot")

    @Test
    fun `live cancellation rejects wrong permit foreign domain and unregistered task`() = runSupportCloseProbe("task_begin_cancel_identity")

    @Test
    fun `live failure before task claim prevents begin without early invalidation`() = runSupportCloseProbe("task_begin_fail_before_run")

    @Test
    fun `live failure after task claim invalidates and retains task until end`() = runSupportCloseProbe("task_begin_fail_running")

    @Test
    fun `refused live begin and fatal recovery retain both failures without end`() = runSupportCloseProbe("task_begin_refused_recovery")

    private fun liveInvocationBeginProbe(mode: String) =
        withRuntime(retainOnExit = mode != "task_begin") { fixture ->
            // Test-only already-installed domain: this is not a successful Live preparation or native qualification.
            val recording = RecordingCapture(completeCapture = true)
            val beginFailure = if (mode == "task_begin_fatal") {
                AssertionError("controlled fatal Begin failure")
            } else {
                IllegalStateException("controlled attached Begin failure")
            }
            val contextFailure = if (mode == "task_begin_fatal_pending") {
                LinkageError("controlled fatal context failure")
            } else {
                IllegalStateException("controlled callback context failure")
            }
            val endFailure = LinkageError("controlled fatal End failure")
            val refusalCause = IllegalArgumentException("controlled Begin refusal")
            val recoveryFailure = LinkageError("controlled task recovery fatal")
            val invalidationFailure = IllegalStateException("controlled pending End invalidation failure")
            if (mode == "task_begin_throw" || mode == "task_begin_fatal" || mode == "task_begin_foreign_recovered") recording.taskBeginFailure.set(beginFailure)
            if (mode == "task_begin_foreign" || mode == "task_begin_foreign_recovered") recording.foreignTaskWorker.set(true)
            if (mode == "task_begin_pending" || mode == "task_begin_pending_throw") recording.taskEndResult.set(recording.pendingRetireOutcome)
            if (mode == "task_begin_pending_throw") recording.beforeInvalidate = { throw invalidationFailure }
            if (mode == "task_begin_refused") recording.taskEndResult.set(recording.refusedOutcome)
            if (mode == "task_begin_late_refused") recording.taskEndResult.set(recording.refusedOutcome)
            if (mode == "task_begin_fatal_pending") recording.taskEndResult.set(recording.pendingRetireOutcome)
            if (mode == "task_begin_late_fatal_end") recording.taskEndFailure.set(endFailure)
            if (mode == "task_begin_refused_recovery") {
                recording.taskBeginRefusal.set(
                    LiveFailure(LiveFailureStatus.Stale, LiveFailureReason.IdentityMismatch, refusalCause),
                )
                recording.taskRecoveryFailure.set(recoveryFailure)
            }
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val state = runtimeState(fixture.runtime)
            val preparation = state.javaClass
                .getDeclaredField("scopingPreparation")
                .apply { isAccessible = true }
                .get(state) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            val frozen = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(preparation.state).frozen
            val domain = io.mazewall.enforcer.internal.execution.OwnedDomain(
                1,
                DomainLimits(1, if (mode == "task_begin_cancel_concurrent") 2 else 1),
                (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition,
                preparation.environment,
                null,
                null,
                "synthetic-installed-live-",
                frozen,
            )
            val phase = domain.javaClass.getDeclaredField("phase").apply { isAccessible = true }
            phase.set(domain, phase.type.enumConstants.single { (it as Enum<*>).name == "READY" })
            val cancellationPermit = domain.javaClass
                .getDeclaredField("liveCancellationPermit")
                .apply { isAccessible = true }
                .get(domain)
            val bodyEntries = AtomicInteger()
            val firstLease = AtomicReference<io.mazewall.platform.native.TaskLease>()
            val secondLease = AtomicReference<io.mazewall.platform.native.TaskLease>()
            val callbackCalls = AtomicInteger()
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val bodyEntered = CountDownLatch(1)
            val releaseBody = CountDownLatch(1)
            val beforeRun = CountDownLatch(1)
            val releaseRun = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            lateinit var invocation: io.mazewall.enforcer.internal.execution.OwnedInvocation<Int>
            invocation = io.mazewall.enforcer.internal.execution.OwnedInvocation(
                domain,
                Callable {
                    val current = checkNotNull(io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask)
                    assertSame(recording.workerOwner.get(), current.worker)
                    firstLease.set(current)
                    bodyEntries.incrementAndGet()
                    if (mode == "task_begin_tombstone" || mode == "task_begin_retained_frontier" || mode == "task_begin_retained_slot") {
                        val workers = domain.javaClass
                            .getDeclaredField("liveWorkers")
                            .apply { isAccessible = true }
                            .get(domain)
                        val slot = workers.javaClass
                            .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                            .invoke(workers, 0) as LiveWorkerSlot
                        if (mode == "task_begin_retained_frontier" || mode == "task_begin_retained_slot") {
                            // Synthetic state frontier: model a retained lease before slot record publication.
                            val gate = slot.javaClass
                                .getDeclaredField("gate")
                                .apply { isAccessible = true }
                                .get(slot)
                            synchronized(gate) {
                                slot.javaClass
                                    .getDeclaredField("publication")
                                    .apply { isAccessible = true }
                                    .set(slot, null)
                                if (mode == "task_begin_retained_slot") slot.retainCleanup(invocation, current)
                            }
                            domain.liveTaskAdmission.revokeLiveTask(slot, invocation, current)
                            assertEquals(0, recording.copyCalls.get())
                        } else {
                            domain.liveTaskAdmission.revokeLiveTask(slot, invocation, current)
                            val foreign = TaskLease(current.worker, TaskId(current.task.value + 1))
                            assertFailsWith<IllegalStateException> { domain.liveTaskAdmission.revokeLiveTask(slot, invocation, foreign) }
                        }
                    }
                    if (mode == "task_begin_context_inline") {
                        val snapshot = InvocationContextSnapshot(emptyMap()) {
                            assertSame(current, io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask)
                            AutoCloseable { assertSame(current, io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask) }
                        }
                        invocation.withContext(
                            snapshot,
                            Callable {
                                val inline = fixture.runtime.javaClass
                                    .getDeclaredMethod("callInline", Callable::class.java)
                                    .apply { isAccessible = true }
                                inline.invoke(
                                    fixture.runtime,
                                    Callable { assertSame(current, io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask) },
                                )
                            },
                        )
                    }
                    if (mode == "task_begin_cancel_running" || mode == "task_begin_fail_running") {
                        bodyEntered.countDown()
                        while (releaseBody.count != 0L) {
                            try {
                                releaseBody.await()
                            } catch (_: InterruptedException) {
                                // Cancellation is not physical completion.
                            }
                        }
                    }
                    42
                },
                cancellationPermit,
            )
            val callback = invocation.result().thenRun {
                assertSame(invocation, io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.invocation)
                assertSame(firstLease.get(), io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask)
                assertEquals(1, recording.beginCalls.get())
                assertEquals(0, recording.endCalls.get())
                if (mode in setOf("task_begin_late", "task_begin_late_refused", "task_begin_late_fatal_end", "task_begin_fatal_pending")) {
                    val snapshot = InvocationContextSnapshot(emptyMap()) {
                        if (mode == "task_begin_fatal_pending") AutoCloseable { throw contextFailure } else throw contextFailure
                    }
                    val observed = assertFailsWith<Throwable> { invocation.withContext(snapshot, Callable { 1 }) }
                    assertSame(contextFailure, observed)
                }
                callbackCalls.incrementAndGet()
                if (mode == "task_begin_callback_blocked") {
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                }
            }
            val dependent = invocation.result().thenApply<Int> { throw IllegalStateException("dependent stage only") }
            val second = io.mazewall.enforcer.internal.execution.OwnedInvocation(
                domain,
                Callable {
                    secondLease.set(checkNotNull(io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask))
                    bodyEntries.incrementAndGet()
                    if (mode == "task_begin_stale") {
                        secondEntered.countDown()
                        check(releaseSecond.await(5, TimeUnit.SECONDS))
                    }
                    43
                },
                cancellationPermit,
            )

            fun field(
                target: Any,
                name: String,
            ): java.lang.reflect.Field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
            val admission = field(domain, "admission").get(domain) as java.util.concurrent.Semaphore
            admission.acquire()
            @Suppress("UNCHECKED_CAST")
            val pending = field(domain, "pending").get(domain) as MutableSet<Any>
            check(pending.add(invocation))
            val error = AtomicReference<Throwable?>()
            val worker = Thread.ofPlatform().unstarted {
                try {
                    val owner = frozen.bootstrap.sealed
                    val receipt = recording.contexts.prepareWorkerReceipt(owner)
                    val registered = assertIs<LiveSetup.Ready<io.mazewall.platform.native.WorkerOwner>>(
                        recording.contexts.registerCurrent(owner, receipt),
                    ).value
                    val slots = field(domain, "liveWorkers").get(domain)
                    val slot = slots.javaClass
                        .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(slots, 0)
                    field(slot, "thread").set(slot, Thread.currentThread())
                    field(slot, "tid").set(slot, registered.tid)
                    field(slot, "worker").set(slot, registered)
                    field(slot, "registrationReserved").setBoolean(slot, true)
                    io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
                        .enter(domain, frozen)
                    io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
                        .bindLiveWorker(registered)
                    try {
                        if (mode == "task_begin_cancel_before_run" || mode == "task_begin_fail_before_run") {
                            beforeRun.countDown()
                            check(releaseRun.await(5, TimeUnit.SECONDS))
                        }
                        invocation.run()
                        if (mode == "task_begin" || mode == "task_begin_stale") {
                            admission.acquire()
                            check(pending.add(second))
                            second.run()
                        }
                    } finally {
                        io.mazewall.enforcer.internal.execution.RuntimeWorkerContext
                            .exit()
                    }
                } catch (problem: Throwable) {
                    error.set(problem)
                }
            }
            try {
                if (mode == "task_begin_cancel_identity") {
                    val foreign = io.mazewall.enforcer.internal.execution.OwnedDomain(
                        2,
                        DomainLimits(1, 1),
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition,
                        preparation.environment,
                        null,
                        null,
                        "synthetic-foreign-live-",
                        frozen,
                    )
                    val foreignPhase = field(foreign, "phase")
                    foreignPhase.set(foreign, foreignPhase.type.enumConstants.single { (it as Enum<*>).name == "READY" })
                    val foreignPermit = field(foreign, "liveCancellationPermit").get(foreign)
                    val unregistered = io.mazewall.enforcer.internal.execution
                        .OwnedInvocation(domain, Callable { 7 }, cancellationPermit)
                    assertFailsWith<IllegalStateException> { invocation.markLiveCancellation(Any()) }
                    assertFailsWith<IllegalStateException> { domain.cancelLiveInvocation(invocation, Any()) }
                    assertFailsWith<IllegalStateException> { foreign.cancelLiveInvocation(invocation, foreignPermit) }
                    assertEquals(
                        io.mazewall.enforcer.internal.execution.OwnedInvocation.LiveCancellation.ENDED,
                        domain.cancelLiveInvocation(unregistered, cancellationPermit),
                    )
                    assertEquals("READY", (phase.get(domain) as Enum<*>).name)
                    assertEquals("READY", (foreignPhase.get(foreign) as Enum<*>).name)
                    assertEquals(0, recording.retireCalls.get())
                    assertEquals(0, recording.beginCalls.get())
                    return@withRuntime
                }
                if (mode == "task_begin_cancel_concurrent") {
                    admission.acquire()
                    check(pending.add(second))
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val entries = AtomicInteger()
                    recording.beforeInvalidate = {
                        entries.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    val firstCancellation = AtomicReference<CancellationDisposition>()
                    val canceller = Thread.ofPlatform().start { firstCancellation.set(invocation.requestCancellation()) }
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        assertEquals(CancellationDisposition.REQUESTED, second.requestCancellation())
                        assertEquals(1, entries.get())
                    } finally {
                        release.countDown()
                        canceller.join(6000)
                    }
                    assertFalse(canceller.isAlive)
                    assertEquals(CancellationDisposition.REQUESTED, firstCancellation.get())
                    assertEquals(1, recording.retireCalls.get())
                    assertEquals(0, recording.beginCalls.get())
                    return@withRuntime
                }
                if (mode == "task_begin_cancel") {
                    val cancellationContext = AtomicReference<Any?>()
                    invocation.result().whenComplete { _, _ ->
                        cancellationContext.set(io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask)
                    }
                    assertEquals(CancellationDisposition.REQUESTED, invocation.requestCancellation())
                    assertEquals(CancellationDisposition.ALREADY_ENDED, invocation.requestCancellation())
                    assertNull(cancellationContext.get())
                    assertIs<ExecutionEnd.NotStarted>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                    assertEquals(0, recording.beginCalls.get())
                    assertEquals(1, recording.retireCalls.get())
                    return@withRuntime
                }
                if (mode == "task_begin_cancel_throw") {
                    val failure = IllegalStateException("controlled invalidation failure")
                    val attempts = AtomicInteger()
                    recording.beforeInvalidate = {
                        attempts.incrementAndGet()
                        throw failure
                    }
                    assertSame(failure, assertFailsWith<IllegalStateException> { invocation.requestCancellation() })
                    assertIs<ExecutionEnd.NotStarted>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                    assertEquals(CancellationDisposition.ALREADY_ENDED, invocation.requestCancellation())
                    assertEquals(1, attempts.get())
                    assertEquals(0, recording.beginCalls.get())
                    return@withRuntime
                }
                worker.start()
                if (mode == "task_begin_stale") {
                    try {
                        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                        val slots = field(domain, "liveWorkers").get(domain)
                        val slot = slots.javaClass
                            .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                            .invoke(slots, 0)
                        val staleEnd = invocation.javaClass.declaredMethods
                            .single { it.name == "endLiveTask" }
                            .apply { isAccessible = true }
                        assertFailsWith<java.lang.reflect.InvocationTargetException> { staleEnd.invoke(invocation, slot, firstLease.get()) }
                        assertSame(second, field(slot, "activeTask").get(slot))
                        assertEquals(1, recording.endCalls.get())
                    } finally {
                        releaseSecond.countDown()
                    }
                }
                if (mode == "task_begin_cancel_before_run" || mode == "task_begin_fail_before_run") {
                    try {
                        assertTrue(beforeRun.await(5, TimeUnit.SECONDS))
                        if (mode == "task_begin_fail_before_run") {
                            domain.fail(IllegalStateException("controlled pre-claim failure"))
                            assertEquals(0, recording.retireCalls.get())
                        } else {
                            assertEquals(CancellationDisposition.REQUESTED, invocation.requestCancellation())
                        }
                        assertEquals(0, recording.beginCalls.get())
                    } finally {
                        releaseRun.countDown()
                    }
                }
                if (mode == "task_begin_cancel_running") {
                    try {
                        assertTrue(bodyEntered.await(5, TimeUnit.SECONDS))
                        val callbackContext = AtomicReference<Pair<Any?, Any?>>()
                        invocation.result().whenComplete { _, _ ->
                            callbackContext.set(
                                io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.invocation to
                                    io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.liveTask,
                            )
                        }
                        assertEquals(CancellationDisposition.REQUESTED, invocation.requestCancellation())
                        assertNull(callbackContext.get().first)
                        assertNull(callbackContext.get().second)
                        assertEquals(1, recording.retireCalls.get())
                        assertEquals(0, recording.endCalls.get())
                        assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
                        assertEquals(0, admission.availablePermits())
                    } finally {
                        releaseBody.countDown()
                    }
                }
                if (mode == "task_begin_fail_running") {
                    try {
                        assertTrue(bodyEntered.await(5, TimeUnit.SECONDS))
                        domain.fail(IllegalStateException("controlled active-task failure"))
                        assertEquals(1, recording.retireCalls.get())
                        assertEquals(0, recording.endCalls.get())
                        assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
                    } finally {
                        releaseBody.countDown()
                    }
                }
                if (mode == "task_begin_callback_blocked") {
                    try {
                        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                        assertEquals(0, recording.endCalls.get())
                        assertFalse(invocation.executionEnd().toCompletableFuture().isDone)
                        assertEquals(0, admission.availablePermits())
                    } finally {
                        releaseCallback.countDown()
                    }
                }
                worker.join(6000)
                assertFalse(worker.isAlive)
                when (mode) {
                    "task_begin_fatal" -> assertSame(beginFailure, error.get())
                    "task_begin_late_fatal_end" -> assertSame(endFailure, error.get())
                    "task_begin_fatal_pending" -> assertSame(contextFailure, error.get())
                    "task_begin_refused_recovery" -> assertSame(recoveryFailure, error.get())
                    else -> error.get()?.let { throw it }
                }
                when (mode) {
                    "task_begin_cancel_before_run", "task_begin_fail_before_run" -> {
                        assertEquals(0, recording.beginCalls.get())
                        assertEquals(0, bodyEntries.get())
                        assertIs<ExecutionEnd.NotStarted>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                    }
                    "task_begin" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        callback.toCompletableFuture().get(1, TimeUnit.SECONDS)
                        assertEquals(43, second.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertEquals(2, bodyEntries.get())
                        assertEquals(2, recording.beginCalls.get())
                        assertEquals(2, recording.endCalls.get())
                        assertEquals(1, callbackCalls.get())
                        assertTrue(firstLease.get() !== secondLease.get())
                    }
                    "task_begin_throw", "task_begin_fatal" -> {
                        val failure = assertFailsWith<ExecutionException> { invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS) }
                        assertSame(beginFailure, failure.cause)
                        assertEquals(0, bodyEntries.get())
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.endCalls.get())
                    }
                    "task_begin_cancel_running", "task_begin_fail_running" -> {
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.endCalls.get())
                        assertEquals(1, recording.retireCalls.get())
                        assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                    }
                    "task_begin_callback_blocked" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        callback.toCompletableFuture().get(1, TimeUnit.SECONDS)
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.endCalls.get())
                        assertEquals(1, callbackCalls.get())
                    }
                    "task_begin_foreign" -> {
                        assertFailsWith<ExecutionException> { invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS) }
                        assertEquals(0, bodyEntries.get())
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(0, recording.endCalls.get())
                        assertNull(firstLease.get())
                    }
                    "task_begin_foreign_recovered" -> {
                        val failure = assertFailsWith<ExecutionException> { invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS) }
                        assertSame(beginFailure, failure.cause)
                        assertEquals(0, bodyEntries.get())
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(0, recording.endCalls.get())
                        assertNull(firstLease.get())
                    }
                    "task_begin_refused_recovery" -> {
                        val failure = assertFailsWith<ExecutionException> { invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS) }
                        assertSame(recoveryFailure, failure.cause)
                        assertTrue(recoveryFailure.suppressed.any { it.cause === refusalCause })
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.taskRecoveryCalls.get())
                        assertEquals(0, recording.endCalls.get())
                        assertEquals(0, bodyEntries.get())
                        assertNotNull(field(invocation, "taskReceipt").get(invocation))
                        assertNull(firstLease.get())
                        assertFailsWith<RejectedExecutionException> { domain.submit(Callable { 7 }) }
                        val invalidations = recording.retireCalls.get()
                        domain.fail(recoveryFailure)
                        assertEquals(invalidations, recording.retireCalls.get())
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.taskRecoveryCalls.get())
                        assertEquals(0, recording.endCalls.get())
                    }
                    "task_begin_late" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        callback.toCompletableFuture().get(1, TimeUnit.SECONDS)
                        val end = assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertSame(contextFailure, end.failure)
                        assertSame(contextFailure, invocation.evidence().observedFailure)
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.endCalls.get())
                        assertEquals(1, callbackCalls.get())
                    }
                    "task_begin_late_refused", "task_begin_late_fatal_end", "task_begin_fatal_pending" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        val end = assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        val expected = if (mode == "task_begin_late_fatal_end") endFailure else contextFailure
                        assertSame(expected, end.failure)
                        when (mode) {
                            "task_begin_late_fatal_end" -> assertTrue(expected.suppressed.any { it === contextFailure })
                            "task_begin_late_refused" -> {
                                assertSame(recording.refusedOutcome, field(invocation, "taskEnd").get(invocation))
                                assertTrue(expected.suppressed.any { it.message?.startsWith("Live task end refused:") == true })
                            }
                            else -> {
                                assertSame(recording.pendingRetireOutcome, field(invocation, "taskEnd").get(invocation))
                                assertTrue(expected.suppressed.any { it.javaClass.simpleName == "LiveTaskEndPending" })
                            }
                        }
                        assertEquals(1, recording.endCalls.get())
                    }
                    "task_begin_dependent", "task_begin_context_inline", "task_begin_tombstone", "task_begin_retained_frontier", "task_begin_retained_slot" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertFailsWith<ExecutionException> { dependent.toCompletableFuture().get(1, TimeUnit.SECONDS) }
                        assertNull(assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS)).failure)
                        assertEquals(1, recording.endCalls.get())
                    }
                    "task_begin_stale" -> {
                        assertEquals(43, second.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertEquals(2, recording.beginCalls.get())
                        assertEquals(2, recording.endCalls.get())
                    }
                    "task_begin_pending", "task_begin_pending_throw", "task_begin_refused" -> {
                        assertEquals(42, invocation.result().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertEquals(1, recording.beginCalls.get())
                        assertEquals(1, recording.endCalls.get())
                        val end = assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(1, TimeUnit.SECONDS))
                        assertNotNull(end.failure)
                        if (mode == "task_begin_pending_throw") {
                            assertSame(invalidationFailure, end.failure)
                            assertSame(recording.pendingRetireOutcome, field(invocation, "taskEnd").get(invocation))
                        }
                        val slots = field(domain, "liveWorkers").get(domain)
                        val slot = slots.javaClass
                            .getDeclaredMethod("slot", Int::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                            .invoke(slots, 0)
                        assertSame(invocation, field(slot, "activeTask").get(slot))
                        assertFailsWith<RejectedExecutionException> { domain.submit(Callable { 7 }) }
                    }
                    else -> error("Unexpected task test mode")
                }
            } finally {
                worker.join()
                if (mode == "task_begin") {
                    recording.workerOwner.get()?.let { recording.contexts.retireWorker(it) }
                    ready.value.close()
                }
            }
        }

    @Test
    fun `reserved live registration remains counted across drain until worker joins`() =
        withRuntime(canonical = true) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            recording.beforeRegister = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                completeLiveRelaySetup(fixture)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertFalse(fixture.helperPhysicallyRetired())
                assertEquals(1, recording.registerCalls.get())
                assertEquals(0, recording.workerRetireCalls.get())
                assertEquals(0, recording.retireCalls.get())
                assertEquals(0, fixture.relay.reaps.get())
                fixture.resources.validate()
                assertEquals(1, installs.get())
            } finally {
                release.countDown()
                caller.join(6000)
            }
            assertFalse(caller.isAlive)
            assertNotNull(outcome.get(1, TimeUnit.SECONDS))
            fixture.runtime.drain(timeout)
            assertEquals(1, recording.workerRetireCalls.get())
            assertFalse(recording.workerThread.get().isAlive)
            assertTrue(recording.lifecycle.indexOf("worker") < recording.lifecycle.indexOf("installation"))
            assertEquals(1, fixture.relay.reaps.get())
        }

    private fun completeLiveRelaySetup(fixture: RuntimeFixture) {
        val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
        val helper = fixture.relay.helper
        val setup = assertIs<Frame.Setup>(helper.receiveFrame(deadline).frame)
        helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
        setup.manifest.capabilities.forEachIndexed { index, capability ->
            assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), helper.receiveFrame(deadline).frame)
            helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
            helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, fixture.relay.observations.observeRoot(it.handle)) }
            helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
        }
        assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), helper.receiveFrame(deadline).frame)
        helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
    }

    @Test
    fun `drain before live worker prestart prevents registration`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val checks = AtomicInteger()
            beforePeerCredentials = {
                if (Thread.currentThread() === fixture.managerThread && checks.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                completeLiveRelaySetup(fixture)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, recording.registerCalls.get())
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
                assertEquals(1, installs.get())
            } finally {
                beforePeerCredentials = {}
                release.countDown()
                caller.join(6000)
            }
            assertFalse(caller.isAlive)
            assertNotNull(outcome.get(1, TimeUnit.SECONDS))
            fixture.runtime.drain(timeout)
            assertEquals(0, recording.registerCalls.get())
            assertEquals(0, recording.workerRetireCalls.get())
            assertEquals(1, installs.get())
        }

    @Test
    fun `refused live registration retains ambiguous receipt without invented worker`() = runSupportCloseProbe("worker_refused")

    @Test
    fun `wrong recovered live worker identity retains originals without retirement`() = runSupportCloseProbe("worker_wrong_identity")

    private fun refusedWorkerRegistrationProbe(wrongIdentity: Boolean) =
        withRuntime { fixture ->
            skipFixtureResourceClose = true
            val recording = RecordingCapture(completeCapture = true)
            recording.refuseRegister.set(!wrongIdentity)
            recording.wrongWorkerTid.set(wrongIdentity)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                completeLiveRelaySetup(fixture)
                assertNotNull(outcome.get(5, TimeUnit.SECONDS))
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                val failure = assertNotNull(first.cleanupFailure)
                assertEquals(1, recording.registerCalls.get())
                if (wrongIdentity) assertNotNull(recording.workerOwner.get()) else assertNull(recording.workerOwner.get())
                assertEquals(1, recording.recoverWorkerCalls.get())
                assertEquals(0, recording.workerRetireCalls.get())
                assertFalse(recording.lifecycle.contains("installation"))
                assertEquals(0, fixture.relay.reaps.get())
                fixture.resources.validate()
                val second = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(failure, second.cleanupFailure)
                assertEquals(1, recording.registerCalls.get())
                assertEquals(1, recording.recoverWorkerCalls.get())
                assertEquals(0, recording.workerRetireCalls.get())
                assertEquals(1, installs.get())
            } finally {
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
        }

    @Test
    fun `pending live worker retirement retains installation and originals without replay`() = runSupportCloseProbe("worker_retire_pending")

    @Test
    fun `refused live worker retirement retains installation and originals without replay`() = runSupportCloseProbe("worker_retire_refused")

    private fun workerRetirementIncompleteProbe(mode: String) =
        withRuntime(canonical = true) { fixture ->
            skipFixtureResourceClose = true
            val recording = RecordingCapture(completeCapture = true)
            recording.workerRetirement.set(
                if (mode == "worker_retire_pending") {
                    io.mazewall.platform.native.LiveCompletion
                        .Pending(io.mazewall.platform.native.LivePendingReason.NotPhysicallyRetired)
                } else {
                    recording.refusedOutcome
                },
            )
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                completeLiveRelaySetup(fixture)
                assertIs<UnsupportedOperationException>(outcome.get(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertFalse(fixture.helperPhysicallyRetired())
                assertEquals(1, recording.registerCalls.get())
                assertEquals(1, recording.workerRetireCalls.get())
                assertFalse(recording.workerThread.get().isAlive)
                assertFalse(recording.lifecycle.contains("installation"))
                assertEquals(0, fixture.relay.reaps.get())
                fixture.resources.validate()
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertEquals(1, recording.workerRetireCalls.get())
                assertEquals(0, fixture.relay.reaps.get())
                fixture.resources.validate()
            } finally {
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
        }

    @Test
    fun `fatal live registration after attachment recovers exact worker after join`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val fatal = AssertionError("controlled post-attachment failure")
            recording.afterRegisterFailure.set(fatal)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                completeLiveRelaySetup(fixture)
                assertSame(fatal, outcome.get(5, TimeUnit.SECONDS))
                fixture.runtime.drain(timeout)
                assertEquals(1, recording.registerCalls.get())
                assertNotNull(recording.workerOwner.get())
                assertEquals(1, recording.recoverWorkerCalls.get())
                assertEquals(1, recording.workerRetireCalls.get())
                assertFalse(recording.workerThread.get().isAlive)
                assertTrue(recording.lifecycle.indexOf("worker") < recording.lifecycle.indexOf("installation"))
                assertEquals(1, fixture.relay.reaps.get())
                assertEquals(1, installs.get())
            } finally {
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
        }

    @Test
    fun `transferred live plan rejects Ready for another binding before worker startup`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
                val helper = fixture.relay.helper
                val setup = assertIs<Frame.Setup>(helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
                setup.manifest.capabilities.forEachIndexed { index, capability ->
                    assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), helper.receiveFrame(deadline).frame)
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
                    helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, fixture.relay.observations.observeRoot(it.handle)) }
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
                }
                assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), helper.receiveFrame(deadline).frame)
                val wrong = setup.binding.copy(manifestDigest = "0".repeat(64))
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, wrong, setup.deadline))
                assertIs<io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection>(outcome.get(5, TimeUnit.SECONDS))
                assertEquals(1, installs.get(), "wrong Ready must not start a worker")
            } finally {
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
        }

    @Test
    fun `live Ready revalidation failure quarantines session before worker startup`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val peerChecks = AtomicInteger()
            val failure = IllegalStateException("controlled Ready peer revalidation failure")
            val reapEntered = CountDownLatch(1)
            val releaseReap = CountDownLatch(1)
            fixture.relay.beforeReap = {
                reapEntered.countDown()
                check(releaseReap.await(10, TimeUnit.SECONDS))
            }
            supportOpenPath.set(
                fixture.support
                    .single()
                    .path
                    .toRealPath()
                    .toString(),
            )
            beforePeerCredentials = {
                if (Thread.currentThread() === fixture.managerThread && peerChecks.incrementAndGet() == 2) throw failure
            }
            val outcome = CompletableFuture<Throwable?>()
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
                val helper = fixture.relay.helper
                val setup = assertIs<Frame.Setup>(helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
                setup.manifest.capabilities.forEachIndexed { index, capability ->
                    assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), helper.receiveFrame(deadline).frame)
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
                    helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, fixture.relay.observations.observeRoot(it.handle)) }
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
                }
                assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
                val rejected = assertIs<io.mazewall.enforcer.internal.supervisor.ConfinedHelperRejection>(outcome.get(5, TimeUnit.SECONDS))
                assertSame(failure, rejected.cause)
                assertEquals(2, peerChecks.get())
                assertEquals(1, installs.get())
                assertTrue(reapEntered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, supportCloseCalls.get())
                fixture.resources.validate()
            } finally {
                beforePeerCredentials = {}
                releaseReap.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
            fixture.runtime.drain(timeout)
            assertEquals(1, fixture.relay.reaps.get())
            assertEquals(1, supportCloseCalls.get())
        }

    @Test
    fun `transferred live setup remains counted while connect is blocked`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = CompletableFuture<Throwable?>()
            val reapEntered = CountDownLatch(1)
            val releaseReap = CountDownLatch(1)
            fixture.relay.beforeReap = {
                reapEntered.countDown()
                check(releaseReap.await(10, TimeUnit.SECONDS))
            }
            supportOpenPath.set(
                fixture.support
                    .single()
                    .path
                    .toRealPath()
                    .toString(),
            )
            beforeConnect = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        fixture.runtime.prepare(
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, supportCloseCalls.get())
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
                assertEquals(1, installs.get())
            } finally {
                beforeConnect = {}
                release.countDown()
                caller.join(6000)
            }
            assertFalse(caller.isAlive)
            assertNotNull(outcome.get(1, TimeUnit.SECONDS))
            val failure: Throwable
            lateinit var actualRetirement: Thread
            lateinit var reapAttempt: CountDownLatch
            try {
                assertTrue(reapEntered.await(5, TimeUnit.SECONDS))
                actualRetirement = fixture.dedicatedRetirementThread()
                reapAttempt = fixture.confinedReapAttempt()
                assertFalse(actualRetirement === fixture.managerThread)
                assertFalse(actualRetirement === Thread.currentThread())
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                failure = assertNotNull(first.cleanupFailure)
                assertEquals(0, supportCloseCalls.get())
                fixture.resources.validate()
                assertEquals(1, fixture.relay.reaps.get())
            } finally {
                releaseReap.countDown()
            }
            val completionDeadline = System.nanoTime() + timeout.toNanos()
            assertTrue(reapAttempt.await(timeout.toNanos(), TimeUnit.NANOSECONDS))
            val remaining = completionDeadline - System.nanoTime()
            assertTrue(remaining > 0, "retirement completion budget exhausted")
            TimeUnit.NANOSECONDS.timedJoin(actualRetirement, remaining)
            assertFalse(actualRetirement.isAlive, "original retirement actor must physically exit")
            val second = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(failure, second.cleanupFailure)
            assertEquals(1, fixture.relay.reaps.get())
            assertEquals(1, supportCloseCalls.get())
            assertFails { fixture.resources.validate() }
            fixture.runtime.drain(timeout)
            assertEquals(1, fixture.relay.reaps.get())
            assertEquals(1, supportCloseCalls.get())
        }

    @Test
    fun `drain after live commit retains helper session before refusing connection`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val committed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val domainRef = AtomicReference<io.mazewall.enforcer.internal.execution.OwnedDomain>()
            val result = CompletableFuture<Throwable?>()
            val connects = AtomicInteger()
            supportOpenPath.set(
                fixture.support
                    .single()
                    .path
                    .toRealPath()
                    .toString(),
            )
            beforeConnect = { connects.incrementAndGet() }
            val caller = Thread.ofPlatform().start {
                result.complete(
                    runCatching {
                        scopingMethod().invoke(
                            fixture.runtime,
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                            scopingEffects(afterDomainCommit = {
                                val state = runtimeState(fixture.runtime)
                                    .javaClass
                                    .getDeclaredField("scopingPreparation")
                                    .apply { isAccessible = true }
                                    .get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
                                domainRef.set(assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(state.state).domain)
                                committed.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }),
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, connects.get())
                assertEquals(0, supportCloseCalls.get())
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                release.countDown()
                caller.join(6000)
                beforeConnect = {}
            }
            assertFalse(caller.isAlive)
            assertNotNull(result.get(1, TimeUnit.SECONDS))
            val session = domainRef
                .get()
                .javaClass
                .getDeclaredField("helperSession")
                .apply { isAccessible = true }
                .get(domainRef.get())
            assertNotNull(session, "session must be retained before the phase refusal")
            assertEquals(1, installs.get())
        }

    @Test
    fun `live committed callback failure settles helper preparation obligation`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSyntheticWithSupport(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    helperContext = fixture.endpoint.context,
                ) {},
            )
            val failure = IllegalStateException("controlled postcommit failure")
            val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                scopingMethod().invoke(
                    fixture.runtime,
                    (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                    DomainLimits(1, 1),
                    ready.value,
                    scopingEffects(afterDomainCommit = { throw failure }),
                )
            }
            assertSame(failure, thrown.targetException)
            fixture.runtime.drain(timeout)
            assertFalse(fixture.managerThread.isAlive)
            assertEquals(0, fixture.relay.reaps.get())
        }

    @Test
    fun `live session retirement completes before helper pins and originals`() = runSupportCloseProbe("live_session")

    @Test
    fun `live session retirement failure remains counted without retry`() = runSupportCloseProbe("live_session_failure")

    private fun liveSessionRetirementProbe(mode: String) =
        withRuntime { fixture ->
            skipFixtureResourceClose = true
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val outcome = CompletableFuture<Throwable?>()
            val supportPath = fixture.support
                .single()
                .path
                .toRealPath()
            supportOpenPath.set(supportPath.toString())
            val failing = mode == "live_session_failure"
            fixture.relay.failReap.set(failing)
            fixture.relay.beforeReap = {
                entered.countDown()
                if (!failing) check(release.await(10, TimeUnit.SECONDS))
            }
            val caller = Thread.ofPlatform().start {
                outcome.complete(
                    runCatching {
                        scopingMethod().invoke(
                            fixture.runtime,
                            (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                            DomainLimits(1, 1),
                            ready.value,
                            scopingEffects(afterDomainCommit = {
                                val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
                                val plan = domain.javaClass.getDeclaredField("helperPlan").let {
                                    it.isAccessible = true
                                    it.get(domain) as PreparedHelperReadPlan
                                }
                                val prepared = io.mazewall.enforcer.internal.supervisor.SupervisorInstaller.prepareConfinedHelper(
                                    plan,
                                    fixture.endpoint.context,
                                    fixture.resources.native,
                                    ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 },
                                    retain = { session ->
                                        domain.javaClass
                                            .getDeclaredField("helperSession")
                                            .apply { isAccessible = true }
                                            .set(domain, session)
                                    },
                                    observations = observations(parent = true),
                                )
                                assertIs<io.mazewall.enforcer.internal.supervisor.ConfinedHelperPreparation.Ready>(prepared)
                            }),
                        )
                    }.exceptionOrNull(),
                )
            }
            try {
                val deadline = ConfinedDeadline.mint(fixture.relay.native) { 5_000_000_000 }
                val helper = fixture.relay.helper
                val setup = assertIs<Frame.Setup>(helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, setup.binding, setup.deadline))
                setup.manifest.capabilities.forEachIndexed { index, capability ->
                    assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Offer, setup.binding, index, setup.deadline), helper.receiveFrame(deadline).frame)
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Expected, setup.binding, index, setup.deadline))
                    helper.receiveDescriptor(deadline).use { assertEquals(capability.identity, fixture.relay.observations.observeRoot(it.handle)) }
                    helper.sendFrame(Frame.Root(ConfinedHelperProtocol.RootTag.Received, setup.binding, index, setup.deadline))
                }
                assertEquals(Frame.Root(ConfinedHelperProtocol.RootTag.Complete, setup.binding, 2, setup.deadline), helper.receiveFrame(deadline).frame)
                helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
                assertTrue(entered.await(5, TimeUnit.SECONDS), "live session retirement must begin before support pins close")
                if (failing) {
                    val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                    val failure = assertNotNull(first.cleanupFailure)
                    assertEquals(1, fixture.relay.reaps.get())
                    assertEquals(0, supportCloseCalls.get())
                    fixture.resources.validate()
                    val second = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                    assertSame(failure, second.cleanupFailure)
                    assertEquals(1, fixture.relay.reaps.get())
                    assertEquals(0, supportCloseCalls.get())
                    fixture.resources.validate()
                } else {
                    assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                    assertEquals(0, supportCloseCalls.get())
                    fixture.resources.validate()
                }
            } finally {
                release.countDown()
                caller.join(6000)
            }
            assertFalse(caller.isAlive)
            assertIs<java.lang.reflect.InvocationTargetException>(outcome.get(2, TimeUnit.SECONDS))
            if (!failing) {
                fixture.runtime.drain(timeout)
                assertEquals(1, supportCloseCalls.get())
                assertEquals(1, fixture.relay.reaps.get())
                assertFails { fixture.resources.validate() }
            }
        }

    @Test
    fun `failed support pin close remains counted and retains original resources`() = runSupportCloseProbe("error")

    @Test
    fun `thrown support pin close remains counted and is never repeated`() = runSupportCloseProbe("throw")

    @Test
    fun `fatal support close retains original error and never repeats`() = runSupportCloseProbe("fatal")

    @Test
    fun `interrupted support close is not redispatched on later manager sweep`() = runSupportCloseProbe("interrupt")

    @Test
    fun `postcapture support close failure remains counted and is never repeated`() = runSupportCloseProbe("postcapture")

    @Test
    fun `independent support owner closes once when another close becomes unknown`() = runSupportCloseProbe("multiple")

    @Test
    fun `live carrier retains unknown pin and closes independent pin once`() = runSupportCloseProbe("live_multiple")

    private fun runSupportCloseProbe(mode: String) {
        val agent = System.getProperty("mazewall.test.childCoverage.agent")
        val coverageDirectory = System.getProperty("mazewall.test.childCoverage.directory")
        check((agent == null) == (coverageDirectory == null))
        val coverageArguments = if (agent == null) {
            emptyList()
        } else {
            val root = Path.of(checkNotNull(coverageDirectory))
            check(root.isAbsolute && Files.isDirectory(root) && !Files.isSymbolicLink(root))
            check(root.toRealPath() == root && root == Path.of(System.getProperty("user.dir")).toRealPath().resolve("build/jacoco/unit-children"))
            val agentPath = Path.of(agent)
            check(agentPath == root.resolve("jacocoagent.jar") && Files.isRegularFile(agentPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            val output = root.resolve("ownership-${java.util.UUID.randomUUID()}.exec")
            check(!Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            listOf("-javaagent:$agentPath=destfile=$output,append=false,includes=io.mazewall.*")
        }
        val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) +
            coverageArguments +
            listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            ConfinedHelperRuntimeDrainTest::class.java.name,
            directory.toString(),
            mode,
        )
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { JvmChildProcess.stripInheritedJvmOptions(it.environment()) }
            .start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.use { input ->
                val retained = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    retained.write(buffer, 0, minOf(count, (65536 - retained.size()).coerceAtLeast(0)))
                }
                retained.toString(Charsets.UTF_8)
            }
        }
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "support ownership child did not finish")
            assertEquals(0, process.exitValue(), output.get(5, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "support ownership child was not reaped")
        }
    }

    private fun failedSupportCloseProbe(mode: String) =
        withRuntime { f ->
        skipFixtureResourceClose = true
        val firstSupport = f.support
            .single()
            .path
            .toRealPath()
        val secondSupport = directory.resolve("operation.jar").toRealPath()
        if (mode == "multiple" || mode == "live_multiple") {
            otherSupportOpenPath.set(firstSupport.toString())
            supportOpenPath.set(secondSupport.toString())
        } else {
            supportOpenPath.set(firstSupport.toString())
        }
        val thrown = when (mode) {
            "error", "postcapture", "multiple", "live_multiple" -> null
            "throw" -> IllegalStateException("controlled support close throw")
            "fatal" -> LinkageError("controlled support close fatal")
            "interrupt" -> InterruptedException("controlled support close interrupt")
            else -> error("Unknown support close probe")
        }
        val domain = if (mode == "postcapture" || mode == "interrupt") {
            f.startPreparation()
            f.completeSetup().resource
        } else {
            null
        }
        if (thrown == null) injectSupportCloseError.set(true) else supportCloseThrow.set(thrown)
        if (domain == null) {
            val incorrect = if (mode == "multiple" || mode == "live_multiple") {
                f.support + ReviewedHelperSupport(secondSupport, "0".repeat(64))
            } else {
                f.support.map { it.copy(sha256 = "0".repeat(64)) }
            }
            val failure = if (mode == "live_multiple") {
                val recording = RecordingCapture(completeCapture = true)
                val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSyntheticWithSupport(f.runtime, f.reviewed, f.resources, recording, helperSupport = incorrect) {})
                assertFails {
                    f.runtime.prepare((f.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }
            } else {
                assertFails { f.prepare(incorrect) }
            }
            assertTrue(failure.message.orEmpty().contains("reviewed bytes"), failure.toString())
        }
        val first = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        assertFalse(injectSupportCloseError.get(), "target support close was not injected")
        assertEquals(null, supportCloseThrow.get(), "target support throw was not injected")
        assertEquals(1, supportCloseCalls.get())
        if (mode == "multiple" || mode == "live_multiple") {
            assertEquals(1, otherSupportCloseCalls.get())
            assertTrue(otherSupportCloseSucceeded.get())
            assertEquals(listOf("target", "other"), supportCloseOrder.toList())
        }
        val cleanup = assertNotNull(first.cleanupFailure)
        if (thrown is Error) assertSame(thrown, cleanup)
        assertTrue(cleanup.toString().contains("close") || cleanup.suppressed.any { it.toString().contains("close") }, cleanup.toString())
        assertTrue(f.managerThread.isAlive, "unknown support close must keep its manager counted")
        f.resources.validate()
        if (mode == "interrupt") {
            val actualDomain = checkNotNull(domain)
            assertTrue(actualDomain.initialConfinedRetirementEnded())
            val observation = CompletableFuture<Pair<Throwable?, Boolean>>()
            val retry = Thread.ofPlatform().start {
                val problem = runCatching {
                    actualDomain.retryConfinedRetirement(ConfinedDeadline.mint(f.relay.native) { 5_000_000_000 })
                }.exceptionOrNull()
                observation.complete(problem to Thread.currentThread().isInterrupted)
            }
            retry.join(6000)
            assertFalse(retry.isAlive)
            val observed = observation.get(1, TimeUnit.SECONDS)
            assertSame(thrown, observed.first)
            assertFalse(observed.second, "historical interruption must not interrupt a later cleanup actor")
        }
        assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        assertEquals(1, supportCloseCalls.get())
        if (mode == "multiple" || mode == "live_multiple") {
            assertEquals(1, otherSupportCloseCalls.get())
            assertTrue(otherSupportCloseSucceeded.get())
            assertEquals(listOf("target", "other"), supportCloseOrder.toList())
        }
        f.resources.validate()
        }

    @Test
    fun `foreign plan close leaves domain helper usable`() =
        withRuntime { f ->
        f.startPreparation()
        val domain = f.completeSetup()
        val field = domain.resource.javaClass
            .getDeclaredField("helperPlan")
            .apply { isAccessible = true }
        val plan = field.get(domain.resource) as PreparedHelperReadPlan
        assertFailsWith<IllegalStateException> { plan.close() }
        plan.validate()
        val value = f.runtime
            .submit(domain, Callable { 42 })
            .result()
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS)
        assertEquals(42, value)
        }

    @Test
    fun `drain waits for in flight second support open before closing first owner`() =
        withRuntime { f ->
        val firstPath = f.support
            .single()
            .path
            .toRealPath()
        val secondPath = directory.resolve("operation.jar").toRealPath()
        val secondDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(secondPath)))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = CompletableFuture<Throwable?>()
        supportOpenPath.set(firstPath.toString())
        beforeSupportOpen = { path ->
            if (path == secondPath.toString()) {
                arrived.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val caller = Thread.ofPlatform().start {
            result.complete(runCatching { f.prepare(f.support + ReviewedHelperSupport(secondPath, secondDigest)) }.exceptionOrNull())
        }
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            assertTrue(supportFd.get() >= 0)
            assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(100)))
            assertEquals(0, supportCloseCalls.get())
        } finally {
            release.countDown()
            caller.join(6000)
            beforeSupportOpen = {}
        }
        assertFalse(caller.isAlive)
        assertNotNull(result.get(1, TimeUnit.SECONDS))
        f.runtime.drain(timeout)
        assertEquals(1, supportCloseCalls.get())
        assertEquals(1, installs.get())
        }

    @Test
    fun `drainWinsBeforeAdmissionRejectsWithoutEffects`() =
        withRuntime { fixture ->
            assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
            assertFailsWith<java.util.concurrent.RejectedExecutionException> {
                captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources)
            }
        }

    @Test
    fun `reserveReturnedBeforeAttachmentBlocksDrainUntilOwnerRetained`() =
        withRuntime { fixture ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recording = RecordingCapture()
            val finished = CompletableFuture<Any>()
            val caller = Thread.ofPlatform().start {
                try {
                    finished.complete(
                        captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) { owner ->
                        assertSame(recording.reserved.get(), owner)
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    },
                    )
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS), "Reserve hook was not reached")
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertFalse(fixture.manager.isShutdown)
                assertEquals(0, recording.retireCalls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
            assertIs<LiveSetup.Refused>(finished.get(5, TimeUnit.SECONDS))
            fixture.runtime.drain(timeout)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
        }

    @Test
    fun `throwingAfterReserveHookAttachesOwnerBeforeQuiescence`() =
        withRuntime { fixture ->
            val recording = RecordingCapture()
            val original = OutOfMemoryError("controlled reserve boundary")
            val failure = assertFailsWith<OutOfMemoryError> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) { throw original }
            }
            assertSame(original, failure)
            fixture.runtime.drain(timeout)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
        }

    private fun assertRetainedNativeOutcome(
        fixture: RuntimeFixture,
        expected: io.mazewall.platform.native.LiveCompletion,
    ) {
        val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
            it.isAccessible = true
            it.get(runtimeState(fixture.runtime))
        }
            as io.mazewall.enforcer.internal.execution.ScopingPreparation
        val state = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
        assertTrue(
            state.retained.javaClass.declaredFields.any { field ->
            field.isAccessible = true
            field.get(state.retained) === expected
        },
            "actual native outcome was not retained",
        )
    }

    @Test
    fun `pendingInvalidateOutcomeRemainsAttachedUntilExplicitRetry`() =
        withRuntime { fixture ->
            val recording = RecordingCapture()
            recording.pendingInvalidate.set(1)
            assertIs<LiveSetup.Refused>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertRetainedNativeOutcome(fixture, recording.pendingInvalidateOutcome)
            assertEquals(1, recording.retireCalls.get())
            assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
            assertEquals(3, recording.retireCalls.get())
        }

    @Test
    fun `refusedNativeOutcomeRemainsAttachedWithoutRetry`() =
        withRuntime { fixture ->
            val recording = RecordingCapture()
            recording.refuseInvalidate.set(true)
            assertIs<LiveSetup.Refused>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            try {
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertRetainedNativeOutcome(fixture, recording.refusedOutcome)
                assertEquals(1, recording.retireCalls.get())
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertEquals(1, recording.retireCalls.get())
                assertFalse(fixture.manager.isShutdown)
            } finally {
                if (!fixture.manager.isShutdown) {
                    fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                    fixture.manager.shutdown()
                }
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `pendingCloseAndDrainRetainOwnerOnSameManager`() =
        withRuntime { fixture ->
            val recording = RecordingCapture()
            recording.pendingRetire.set(1)
            assertIs<LiveSetup.Refused>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertFalse(fixture.manager.isShutdown)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
            assertRetainedNativeOutcome(fixture, recording.pendingRetireOutcome)
            assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
            assertEquals(3, recording.retireCalls.get())
        }

    @Test
    fun `drainWinsAfterReadyAllocationBeforePublicationKeepsOriginalsBorrowed`() =
        withRuntime { fixture ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recording = RecordingCapture(completeCapture = true)
            val finished = CompletableFuture<Any>()
            val caller = Thread.ofPlatform().start {
                try {
                    finished.complete(
                        captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, {
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }, {}),
                    )
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS), "pre-publication hook was not reached")
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, recording.retireCalls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
            val failure = assertIs<LiveSetup.Refused>(finished.get(5, TimeUnit.SECONDS)).failure
            assertEquals(LiveFailureReason.Shutdown, failure.reason)
            fixture.runtime.drain(timeout)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            fixture.resources.validate()
        }

    @Test
    fun `prepublicationHookFailureRetainsOriginalCauseAndOwner`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val original = OutOfMemoryError("controlled pre-publication failure")
            val preallocated = java.util.concurrent.atomic
                .AtomicReference<Any>()
            val failure = assertFailsWith<OutOfMemoryError> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, {
                    preallocated.set(it)
                    throw original
                }, {})
            }
            assertSame(original, failure)
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime))
            }
                as io.mazewall.enforcer.internal.execution.ScopingPreparation
            val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
            assertSame(preallocated.get(), retiring.retained.result)
            val wrapper = assertIs<LiveSetup.Ready<*>>(preallocated.get()).value
            val bootstrap = wrapper!!::class.java.getDeclaredField("bootstrap").let {
                it.isAccessible = true
                it.get(wrapper)
            }
            assertSame(bootstrap, retiring.retained.frozen?.bootstrap)
            fixture.runtime.drain(timeout)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `readyReturnedButUnobservedRemainsCounted`() =
        withRuntime { fixture ->
            val returned = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recording = RecordingCapture(completeCapture = true)
            val finished = CompletableFuture<Any>()
            val actualReady = java.util.concurrent.atomic
                .AtomicReference<Any>()
            val preallocatedReady = java.util.concurrent.atomic
                .AtomicReference<Any>()
            val caller = Thread.ofPlatform().start {
                try {
                    val result = captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, { preallocatedReady.set(it) }) {}
                    actualReady.set(result)
                    returned.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    finished.complete(result)
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
            try {
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                assertIs<LiveSetup.Ready<*>>(actualReady.get())
                assertSame(preallocatedReady.get(), actualReady.get())
                val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                    it.isAccessible = true
                    it.get(runtimeState(fixture.runtime))
                }
                val state = association.javaClass.getDeclaredField("state").let {
                    it.isAccessible = true
                    it.get(association)
                }
                val fields = state.javaClass.declaredFields.map {
                    it.isAccessible = true
                    it.get(state)
                }
                assertTrue(fields.any { it === actualReady.get() }, "association must retain the exact Ready")
                val wrapper = assertIs<LiveSetup.Ready<*>>(actualReady.get()).value
                val bootstrap = wrapper!!::class.java.getDeclaredField("bootstrap").let {
                    it.isAccessible = true
                    it.get(wrapper)
                }
                val frozen = fields.filterIsInstance<io.mazewall.enforcer.internal.execution.FrozenStackScoping.Live>().single()
                assertSame(bootstrap, frozen.bootstrap)
                assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
                assertSame(recording.reserved.get(), recording.retiredOwner.get())
                assertFails { fixture.resources.validate() }
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
            assertSame(actualReady.get(), assertIs<LiveSetup.Ready<*>>(finished.get(5, TimeUnit.SECONDS)))
        }

    @Test
    fun `wrong live limits refuse without consuming exact ready or resources`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            try {
                assertFailsWith<IllegalArgumentException> {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(2, 1), ready.value)
                }
                assertSame(original, association.state)
                assertSame(ready, original.result)
                fixture.resources.validate()
                assertEquals(0, recording.retireCalls.get())
            } finally {
                ready.value.close()
            }
        }

    @Test
    fun `wrong live policy refuses without consuming ready or resources`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            try {
                assertFailsWith<IllegalArgumentException> { fixture.runtime.prepare(Policy.NO_EXEC_HOTSPOT, DomainLimits(1, 1), ready.value) }
                assertSame(original, association.state)
                fixture.resources.validate()
                assertEquals(0, recording.retireCalls.get())
            } finally {
                ready.value.close()
            }
        }

    @Test
    fun `selected log policy rejects before checked bootstrap`() = assertSelectedActionRejected(io.mazewall.platform.syscall.SeccompAction.ACT_LOG)

    @Test
    fun `selected notify policy rejects before checked bootstrap`() = assertSelectedActionRejected(io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY)

    @Test
    fun `selected kill process policy rejects before checked bootstrap`() = assertSelectedActionRejected(io.mazewall.platform.syscall.SeccompAction.ACT_KILL_PROCESS)

    @Test
    fun `selected kill thread policy rejects before checked bootstrap`() = assertSelectedActionRejected(io.mazewall.platform.syscall.SeccompAction.ACT_KILL_THREAD)

    @Test
    fun `selected trap policy rejects before checked bootstrap`() = assertSelectedActionRejected(io.mazewall.platform.syscall.SeccompAction.ACT_TRAP)

    @Test
    fun `selected errno policy rejects before checked bootstrap`() =
        assertSelectedActionRejected(
        io.mazewall.platform.syscall.SeccompAction
        .ACT_ERRNO(),
    )

    @Test
    fun `selected trace policy rejects before checked bootstrap`() =
        assertSelectedActionRejected(
        io.mazewall.platform.syscall.SeccompAction
        .ACT_TRACE(1),
    )

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["kill", "trap", "errno", "trace", "log", "notify"])
    fun `selected default action rejects before checked bootstrap`(kind: String) =
        withRuntime { fixture ->
        val action = when (kind) {
            "kill" -> io.mazewall.platform.syscall.SeccompAction.ACT_KILL_PROCESS
            "trap" -> io.mazewall.platform.syscall.SeccompAction.ACT_TRAP
            "errno" ->
                io.mazewall.platform.syscall.SeccompAction
                .ACT_ERRNO()
            "trace" ->
                io.mazewall.platform.syscall.SeccompAction
                .ACT_TRACE(1)
            "log" -> io.mazewall.platform.syscall.SeccompAction.ACT_LOG
            "notify" -> io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY
            else -> error("unexpected action")
        }
        val base = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
        val changed = Policy<PolicyScope.ThreadLocalOnly, Uncompiled>(base.copy(defaultAction = action, syscallActions = base.syscallActions - Syscall.OPEN))
        val document = OperationPolicyDocument(fixture.reviewed.document.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, changed), emptySet(), emptyMap())
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), document.binding)
        val calls = AtomicInteger()
        val baselineInstalls = installs.get()
        beforeNative = { calls.incrementAndGet() }
        try {
            val failure = assertFailsWith<IllegalArgumentException> { captureWithMissingAgent(fixture.runtime, reviewed, fixture.resources) }
            assertTrue(failure.message.orEmpty().contains("Static policy"))
            assertEquals(0, calls.get())
            assertEquals(baselineInstalls, installs.get())
            fixture.resources.validate()
        } finally {
            beforeNative = {}
        }
    }

    @Test
    fun `direct result is refused before Live handler capture`() =
        withRuntime { fixture ->
            val policy = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy
            val owner = DirectResolutionCapture.create()
            val direct = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, fixture.resources.implementationClass, emptySet())
            val getters = AtomicInteger()
            val nativeCalls = AtomicInteger()
            beforeNative = { nativeCalls.incrementAndGet() }
            val scoping = object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler>
                    get() {
                        getters.incrementAndGet()
                        error("handler getter must not run")
                    }
            }
            try {
                val failure = assertFailsWith<IllegalStateException> { captureWithMissingAgent(fixture.runtime, fixture.reviewed, direct, scopingPolicy = scoping) }
                assertTrue(failure.message.orEmpty().contains("Direct result Live scoping is not available"))
                assertEquals(0, getters.get())
                assertEquals(0, nativeCalls.get())
                val preparation = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").apply { isAccessible = true }
                assertTrue(preparation.get(runtimeState(fixture.runtime)) == null)
                direct.validate()
            } finally {
                beforeNative = {}
                direct.close()
            }
        }

    @Test
    fun `direct result is refused before confined manager admission`() =
        withRuntime { fixture ->
            val policy = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy
            val owner = DirectResolutionCapture.create()
            val direct = owner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, fixture.resources.implementationClass, emptySet())
            val completed = fixture.manager.completedTaskCount
            val nativeCalls = AtomicInteger()
            beforeNative = { nativeCalls.incrementAndGet() }
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    fixture.runtime.prepareConfinedRead(fixture.reviewed, DomainLimits(1, 1), direct, fixture.endpoint.context, fixture.support, HelperSupportLifetime.NoWriters)
                }
                assertTrue(failure.message.orEmpty().contains("Direct result confined preparation is not available"))
                assertEquals(completed, fixture.manager.completedTaskCount)
                assertEquals(0, nativeCalls.get())
                val helperDrain = ManagedRuntime::class.java.getDeclaredField("helperDrainMode").apply { isAccessible = true }
                assertFalse(helperDrain.getBoolean(fixture.runtime))
                direct.validate()
            } finally {
                beforeNative = {}
                direct.close()
            }
        }

    @Test
    fun `selected explicit allow overrides denied default before checked bootstrap`() =
        withRuntime { fixture ->
        val base = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
        val changed = Policy<PolicyScope.ThreadLocalOnly, Uncompiled>(
            base.copy(
            defaultAction = io.mazewall.platform.syscall.SeccompAction.ACT_KILL_PROCESS,
            syscallActions = base.syscallActions + (Syscall.OPEN to io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW),
        ),
        )
        val document = OperationPolicyDocument(fixture.reviewed.document.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, changed), emptySet(), emptyMap())
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), document.binding)
        assertIs<LiveSetup.Refused>(captureWithMissingAgent(fixture.runtime, reviewed, fixture.resources))
        fixture.resources.validate()
    }

    private fun assertSelectedActionRejected(action: io.mazewall.platform.syscall.SeccompAction) {
            withRuntime { fixture ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).addAction(action, Syscall.OPEN).build()
                val document = OperationPolicyDocument(
                    fixture.reviewed.document.binding,
                    CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy),
                    emptySet(),
                    emptyMap(),
                )
                val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), document.binding)
                val calls = AtomicInteger()
                val baselineInstalls = installs.get()
                beforeNative = { calls.incrementAndGet() }
                try {
                    val failure = assertFailsWith<IllegalArgumentException> {
                        captureWithMissingAgent(fixture.runtime, reviewed, fixture.resources)
                    }
                    assertTrue(failure.message.orEmpty().contains("Static policy"))
                    assertEquals(0, calls.get())
                    assertEquals(baselineInstalls, installs.get())
                    fixture.resources.validate()
                } finally {
                    beforeNative = {}
                }
            }
    }

    @Test
    fun `live planner notifies only selected open route and denies unused routes`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            try {
                val frozen = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state).frozen
                val original = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
                val planned = ContainedExecutors.augmentForScoping(original, frozen)
                assertEquals(io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY, planned.syscallActions[Syscall.OPEN])
                assertFalse(planned.isSyscallAllowed(Syscall.OPENAT))
                assertFalse(planned.isSyscallAllowed(Syscall.OPENAT2))
                assertEquals(original.argumentRules, planned.argumentRules)
                assertFailsWith<IllegalArgumentException> {
                    ContainedExecutors.augmentForScoping(original.copy(defaultAction = io.mazewall.platform.syscall.SeccompAction.ACT_KILL_PROCESS), frozen)
                }
                val arch = Arch.AMD64
                val filter = io.mazewall.enforcer.internal.seccomp.BpfFilter
                    .build(arch, planned)
                    .instructions
                val baseFilter = io.mazewall.enforcer.internal.seccomp.BpfFilter
                    .build(arch, original)
                    .instructions

                fun verdict(
                    program: List<io.mazewall.seccomp.BpfInstruction>,
                    syscall: Syscall,
                    args: LongArray = LongArray(6),
                ): Int? =
                    io.mazewall.seccomp.BpfSimulator
                    .simulate(program, syscall.numberFor(arch), arch.audit, args)
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, verdict(filter, Syscall.OPEN))
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, verdict(filter, Syscall.OPENAT))
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, verdict(filter, Syscall.OPENAT2))
                for ((syscall, args) in listOf(
                    Syscall.MMAP to longArrayOf(0, 0, 4, 0, 0, 0),
                    Syscall.MPROTECT to longArrayOf(0, 0, 4, 0, 0, 0),
                    Syscall.CLONE to longArrayOf(0x10000, 0, 0, 0, 0, 0),
                    Syscall.CLONE to LongArray(6),
                    Syscall.PRCTL to longArrayOf(15, 0, 0, 0, 0, 0),
                    Syscall.PRCTL to longArrayOf(0x7fffffff, 0, 0, 0, 0, 0),
                    Syscall.FUTEX to LongArray(6),
                )) {
                    assertEquals(verdict(baseFilter, syscall, args), verdict(filter, syscall, args), "argument behavior changed for $syscall")
                }
                assertEquals(io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW, original.defaultAction)
                val additional = AtomicInteger()
                beforeNative = { additional.incrementAndGet() }
                assertFailsWith<UnsupportedOperationException> {
                    ContainedExecutors.installOnCurrentThread(original, frozen, association.environment, null)
                }
                assertEquals(0, additional.get())
                val virtualResult = CompletableFuture<Throwable>()
                val virtual = Thread.ofVirtual().start {
                    virtualResult.complete(
                        runCatching {
                        ContainedExecutors.installOnCurrentThread(original, frozen, association.environment, null)
                    }.exceptionOrNull() ?: AssertionError("virtual installer unexpectedly returned"),
                    )
                }
                assertIs<IllegalStateException>(virtualResult.get(5, TimeUnit.SECONDS))
                virtual.join(5000)
                assertFalse(virtual.isAlive)
                assertEquals(0, additional.get())
            } finally {
                beforeNative = {}
                ready.value.close()
            }
        }

    @Test
    fun `live planner 257 route notifies openat and denies open and openat2`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val (preparation, ready) = io.mazewall.ffi.internal
                .captureSynthetic257(fixture.runtime, fixture.reviewed, fixture.resources, recording)
            try {
                val frozen = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(preparation.state).frozen
                val original = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
                val planned = ContainedExecutors.augmentForScoping(original, frozen)
                val program = io.mazewall.enforcer.internal.seccomp.BpfFilter
                    .build(Arch.AMD64, planned)
                    .instructions

                fun verdict(syscall: Syscall) =
                    io.mazewall.seccomp.BpfSimulator
                    .simulate(program, syscall.numberFor(Arch.AMD64), Arch.AMD64)
                assertEquals(NativeConstants.SECCOMP_RET_USER_NOTIF, verdict(Syscall.OPENAT))
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, verdict(Syscall.OPEN))
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM, verdict(Syscall.OPENAT2))
                assertEquals(original.argumentRules, planned.argumentRules)
                val baseProgram = io.mazewall.enforcer.internal.seccomp.BpfFilter
                    .build(Arch.AMD64, original)
                    .instructions
                for ((syscall, args) in listOf(
                    Syscall.MMAP to longArrayOf(0, 0, 4, 0, 0, 0),
                    Syscall.MPROTECT to longArrayOf(0, 0, 4, 0, 0, 0),
                    Syscall.CLONE to longArrayOf(0x10000, 0, 0, 0, 0, 0),
                    Syscall.CLONE to LongArray(6),
                    Syscall.PRCTL to longArrayOf(15, 0, 0, 0, 0, 0),
                    Syscall.PRCTL to longArrayOf(0x7fffffff, 0, 0, 0, 0, 0),
                    Syscall.FUTEX to LongArray(6),
                )) {
                    val nr = syscall.numberFor(Arch.AMD64)
                    assertEquals(
                        io.mazewall.seccomp.BpfSimulator
                            .simulate(baseProgram, nr, Arch.AMD64.audit, args),
                        io.mazewall.seccomp.BpfSimulator
                            .simulate(program, nr, Arch.AMD64.audit, args),
                    )
                }
            } finally {
                ready.close()
            }
        }

    @Test
    fun `live planner rejects unrelated existing notification`() =
        withRuntime { fixture ->
        val base = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
        val changed = Policy<PolicyScope.ThreadLocalOnly, Uncompiled>(
            base.copy(
            syscallActions = base.syscallActions + (Syscall.CONNECT to io.mazewall.platform.syscall.SeccompAction.ACT_NOTIFY),
        ),
        )
        val document = OperationPolicyDocument(fixture.reviewed.document.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, changed), emptySet(), emptyMap())
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), document.binding)
        val recording = RecordingCapture(completeCapture = true)
        val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, reviewed, fixture.resources, recording) {})
        val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
            it.isAccessible = true
            it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
        }
        try {
            val frozen = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state).frozen
            val failure = assertFailsWith<IllegalArgumentException> { ContainedExecutors.augmentForScoping(changed.definition, frozen) }
            assertTrue(failure.message.orEmpty().contains("Unrelated notification"))
        } finally {
            ready.value.close()
        }
    }

    @Test
    fun `synthetic live planning preserves restrictive argument verdicts`() =
        withRuntime { fixture ->
        val base = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy.definition
        val definition = base.copy(
            allowMmapExec = false,
            allowNonThreadClone = false,
            allowUnsafePrctl = false,
            syscallActions = base.syscallActions +
                mapOf(
                Syscall.CLONE to io.mazewall.platform.syscall.SeccompAction
                    .ACT_ERRNO(),
                Syscall.PRCTL to io.mazewall.platform.syscall.SeccompAction.ACT_ALLOW,
            ),
        )
        val policy = Policy<PolicyScope.ThreadLocalOnly, Uncompiled>(definition)
        val document = OperationPolicyDocument(fixture.reviewed.document.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
        val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), document.binding)
        val recording = RecordingCapture(completeCapture = true)
        val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, reviewed, fixture.resources, recording) {})
        val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
            it.isAccessible = true
            it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
        }
        try {
            val frozen = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state).frozen
            val planned = ContainedExecutors.augmentForScoping(definition, frozen)
            assertEquals(definition.argumentRules, planned.argumentRules)
            val program = io.mazewall.enforcer.internal.seccomp.BpfFilter
                .build(Arch.AMD64, planned)
                .instructions

            fun verdict(
                syscall: Syscall,
                args: LongArray,
            ) = io.mazewall.seccomp.BpfSimulator
                .simulate(program, syscall.numberFor(Arch.AMD64), Arch.AMD64.audit, args)
            val denied = NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EPERM
            assertEquals(denied, verdict(Syscall.MMAP, longArrayOf(0, 0, 4, 0, 0, 0)))
            assertEquals(denied, verdict(Syscall.MPROTECT, longArrayOf(0, 0, 4, 0, 0, 0)))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, verdict(Syscall.CLONE, longArrayOf(0x10100, 0, 0, 0, 0, 0)))
            assertEquals(denied, verdict(Syscall.CLONE, LongArray(6)))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, verdict(Syscall.PRCTL, longArrayOf(15, 0, 0, 0, 0, 0)))
            assertEquals(denied, verdict(Syscall.PRCTL, longArrayOf(0x7fffffff, 0, 0, 0, 0, 0)))
            assertEquals(NativeConstants.SECCOMP_RET_ALLOW, verdict(Syscall.FUTEX, LongArray(6)))
        } finally {
            ready.value.close()
        }
    }

    @Test
    fun `missing helper authority leaves the actual live association ready`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSyntheticWithSupport(fixture.runtime, fixture.reviewed, fixture.resources, recording, omitHelperAuthority = true) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val generation = runtimeState(fixture.runtime).javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            val before = generation.getLong(runtimeState(fixture.runtime))
            val supportOpens = AtomicInteger()
            val supportPath = fixture.support
                .single()
                .path
                .toString()
            beforeSupportOpen = { path -> if (path == supportPath) supportOpens.incrementAndGet() }
            try {
                repeat(2) {
                    assertFailsWith<UnsupportedOperationException> {
                        fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                    }
                    assertSame(original, association.state, "missing authority must not claim or transfer Ready")
                    assertEquals(before, generation.getLong(runtimeState(fixture.runtime)))
                    fixture.resources.validate()
                    assertEquals(0, recording.retireCalls.get())
                    assertEquals(0, supportOpens.get())
                    assertEquals(0, supportCloseCalls.get())
                }
            } finally {
                beforeSupportOpen = {}
                ready.value.close()
            }
        }

    @Test
    fun `live carrier retains in flight support until drain can retire both pins`() =
        withRuntime { fixture ->
            val firstPath = fixture.support
                .single()
                .path
                .toRealPath()
            val secondPath = directory.resolve("operation.jar").toRealPath()
            val secondDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(secondPath)))
            val recording = RecordingCapture(completeCapture = true)
            val support = fixture.support + ReviewedHelperSupport(secondPath, secondDigest)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSyntheticWithSupport(fixture.runtime, fixture.reviewed, fixture.resources, recording, helperSupport = support) {})
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = CompletableFuture<Throwable?>()
            supportOpenPath.set(firstPath.toString())
            otherSupportOpenPath.set(secondPath.toString())
            beforeSupportOpen = { path ->
                if (path == secondPath.toString()) {
                    arrived.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val caller = Thread.ofPlatform().start {
                result.complete(
                    runCatching {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }.exceptionOrNull(),
                )
            }
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                assertTrue(supportFd.get() >= 0)
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertEquals(0, supportCloseCalls.get())
                assertEquals(0, otherSupportCloseCalls.get())
                fixture.resources.validate()
            } finally {
                release.countDown()
                caller.join(6000)
                beforeSupportOpen = {}
            }
            assertFalse(caller.isAlive)
            assertNotNull(result.get(1, TimeUnit.SECONDS))
            val drained = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertNotNull(drained.cleanupFailure)
            assertFalse(fixture.managerThread.isAlive)
            assertFails { fixture.resources.validate() }
            assertEquals(1, supportCloseCalls.get())
            assertEquals(1, otherSupportCloseCalls.get())
            assertTrue(otherSupportCloseSucceeded.get())
            assertEquals(listOf("other", "target"), supportCloseOrder.toList())
        }

    @Test
    fun `transferred live carrier refuses another support open before native dispatch`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val supportPath = fixture.support
                .single()
                .path
                .toRealPath()
            supportOpenPath.set(supportPath.toString())
            val extraPath = directory.resolve("operation.jar").toRealPath()
            val extraOpens = AtomicInteger()
            beforeSupportOpen = { path -> if (path == extraPath.toString()) extraOpens.incrementAndGet() }
            try {
                val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(afterDomainCommit = {
                            val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
                            val capture = domain.javaClass.getDeclaredField("helperCapture").let {
                                it.isAccessible = true
                                it.get(domain) as io.mazewall.enforcer.internal.execution.HelperSupportCapture
                            }
                            val ownerField = capture.javaClass
                                .getDeclaredField("owner")
                                .apply { isAccessible = true }
                            val owner = ownerField.get(capture)
                            val carrier = owner.javaClass.getDeclaredField("carrier").let {
                                it.isAccessible = true
                                it.get(owner) as io.mazewall.enforcer.internal.execution.ScopingPreparation.ClaimProgress
                            }
                            assertFailsWith<IllegalStateException> { capture.prepareTransfer(carrier, domain) }
                            val foreignResult = CompletableFuture<Throwable?>()
                            val foreign = Thread.ofPlatform().start {
                                foreignResult.complete(runCatching { capture.prepareTransfer(carrier, domain) }.exceptionOrNull())
                            }
                            foreign.join(6000)
                            assertFalse(foreign.isAlive)
                            assertIs<IllegalStateException>(foreignResult.get(1, TimeUnit.SECONDS))
                            assertSame(owner, ownerField.get(capture))
                            assertEquals(0, supportCloseCalls.get())
                            assertFailsWith<IllegalStateException> { capture.open(extraPath) }
                        }),
                    )
                }
                assertIs<UnsupportedOperationException>(failure.targetException)
                assertEquals(0, extraOpens.get())
            } finally {
                beforeSupportOpen = {}
                ready.value.close()
            }
        }

    @Test
    fun `matching live claim transfers before unsupported start and preserves ownership`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            try {
                assertFailsWith<UnsupportedOperationException> {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }
                val transferred = association.state
                assertTrue(transferred.javaClass.simpleName in setOf("DomainOwned", "TransferredSettled"), "claim must have committed the exact domain before guarded refusal")
                val domain = transferred.javaClass.getDeclaredField("domain").let {
                    it.isAccessible = true
                    it.get(transferred)
                }
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.values.any { it === domain }, "the transferred domain must be the registered object")
                val captured = domain.javaClass.getDeclaredField("scoping").let {
                    it.isAccessible = true
                    it.get(domain)
                }
                assertSame(original.frozen, captured)
                val helperPlan = domain.javaClass.getDeclaredField("helperPlan").let {
                    it.isAccessible = true
                    it.get(domain) as PreparedHelperReadPlan
                }
                val helperCapture = domain.javaClass.getDeclaredField("helperCapture").let {
                    it.isAccessible = true
                    it.get(domain)
                }
                val planOwner = helperPlan.javaClass.getDeclaredField("supportOwner").let {
                    it.isAccessible = true
                    it.get(helperPlan)
                }
                assertSame(helperCapture, planOwner, "the genuine captured plan must retain the transferred owner")
                assertEquals(fixture.resources.resources.generation, helperPlan.binding.resourceGeneration)
                assertEquals(ProcessHandle.current().pid().toInt(), helperPlan.binding.ownerPid.value)
                assertEquals(1, installs.get(), "no worker restriction may run")
            } finally {
                ready.value.close()
            }
        }

    @Test
    fun `actual claim stores owned ruleset before post store failure`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val original = OutOfMemoryError("post-store sentinel")
            val observedFd = java.util.concurrent.atomic
                .AtomicReference<Any>()
            val observedShell = java.util.concurrent.atomic
                .AtomicReference<Any>()
            val enteredRetirement = CountDownLatch(1)
            val releaseRetirement = CountDownLatch(1)
            recording.beforeInvalidate = {
                enteredRetirement.countDown()
                check(releaseRetirement.await(5, TimeUnit.SECONDS))
            }
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val hook = {
                val claiming = association.state
                check(claiming.javaClass.simpleName == "Claiming")
                val acquisition = claiming.javaClass.getDeclaredField("acquisition").let {
                    it.isAccessible = true
                    it.get(claiming)
                }
                val shell = acquisition.javaClass.getDeclaredField("landlock").let {
                    it.isAccessible = true
                    it.get(acquisition)
                }
                val fd = shell.javaClass.getDeclaredField("acquiredRuleset").let {
                    it.isAccessible = true
                    it.get(shell)
                }
                observedShell.set(shell)
                observedFd.set(checkNotNull(fd))
                throw original
            }
            try {
                val method = scopingMethod()
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    method.invoke(fixture.runtime, (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value, scopingEffects(afterTypedStore = hook))
                }
                assertSame(original, thrown.targetException)
                assertNotNull(observedFd.get())
                assertTrue(enteredRetirement.await(5, TimeUnit.SECONDS))
                val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(observedShell.get(), retiring.retained.landlock)
                val retainedFd = retiring.retained.landlock!!.javaClass.getDeclaredField("acquiredRuleset").let {
                    it.isAccessible = true
                    it.get(retiring.retained.landlock)
                }
                assertSame(observedFd.get(), retainedFd)
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                fixture.resources.validate()
            } finally {
                releaseRetirement.countDown()
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `transferred live native pending retries only on later explicit drain`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingRetire.set(1)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val failure = assertFailsWith<UnsupportedOperationException> {
                fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
            }
            val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!domain.initialConfinedRetirementEnded() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(domain.initialConfinedRetirementEnded(), "initial retirement thread must physically finish")
            assertFalse(domain.retired())
            assertEquals(2, recording.retireCalls.get(), "Pending must not be retried in the initial pass")
            fixture.resources.validate()
            ready.value.close()
            assertEquals(2, recording.retireCalls.get(), "transferred wrapper close must not retry")
            val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(failure, report.cleanupFailure)
            assertTrue(domain.retired(), "a later explicit drain may complete Pending")
            assertEquals(3, recording.retireCalls.get())
            assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
            ready.value.close()
            assertEquals(3, recording.retireCalls.get())
        }

    @Test
    fun `startup live invalidation pending retries only on later explicit drain`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingInvalidate.set(1)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val failure = assertFailsWith<UnsupportedOperationException> {
                fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
            }
            val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!domain.initialConfinedRetirementEnded() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(domain.initialConfinedRetirementEnded())
            assertFalse(domain.retired())
            assertEquals(1, recording.retireCalls.get(), "automatic retirement must retain early Pending")
            fixture.resources.validate()
            ready.value.close()
            assertEquals(1, recording.retireCalls.get())
            val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(failure, report.cleanupFailure)
            assertTrue(domain.retired())
            assertEquals(3, recording.retireCalls.get(), "explicit drain retries invalidation then retires installation")
        }

    @Test
    fun `rejected claim enqueue retains available owner until manager is free`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val queuedDone = CountDownLatch(1)
            fixture.manager.execute {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.manager.execute { queuedDone.countDown() }
                val original = assertFailsWith<java.util.concurrent.RejectedExecutionException> {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }
                val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(original, retiring.retained.failure)
                assertSame(fixture.resources, retiring.retained.resources)
                assertNotNull(retiring.retained.landlock)
                assertEquals(io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned, retiring.retained.disposition)
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                release.countDown()
                assertTrue(queuedDone.await(5, TimeUnit.SECONDS))
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `timed out live prepare cannot commit after accepted claim resumes`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val interrupted = java.util.concurrent.atomic
                .AtomicBoolean()
            val result = CompletableFuture<Throwable>()
            beforeNative = { nr ->
                if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: Throwable) {
                    result.complete(failure)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val failure = result.get(4, TimeUnit.SECONDS)
                assertIs<java.util.concurrent.TimeoutException>(failure)
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(failure, assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Failed>(claiming.request).cause)
                assertFalse(association.claimFinished.isDone)
                fixture.resources.validate()
                release.countDown()
                association.claimFinished.get(5, TimeUnit.SECONDS)
                assertFalse(interrupted.get(), "accepted manager job must not be interrupted")
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.isEmpty(), "failed waiter must prevent later domain registration without drain")
                assertEquals(1, installs.get())
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(failure, report.cleanupFailure)
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    internal fun transferredUnknownProbe(rulesetFailure: Boolean) =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val nativeFailure = LinkageError("native invalidation unknown")
            val closeFailure = OutOfMemoryError("ruleset close unknown")
            if (!rulesetFailure) recording.invalidateFailure.set(nativeFailure)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            if (rulesetFailure) {
                val hook = {
                    val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                    val shell = claiming.acquisition.landlock
                    val fd = shell.javaClass.getDeclaredField("acquiredRuleset").let {
                        it.isAccessible = true
                        it.get(shell)
                    }
                    targetRuleset.set(checkNotNull(fd))
                    rulesetCloseFailure.set(closeFailure)
                    Unit
                }
                val method = scopingMethod()
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    method.invoke(fixture.runtime, (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value, scopingEffects(afterTypedStore = hook))
                }
                assertIs<UnsupportedOperationException>(thrown.targetException)
            } else {
                assertFailsWith<UnsupportedOperationException> {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }
            }
            val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!domain.initialConfinedRetirementEnded() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(domain.initialConfinedRetirementEnded())
            assertFalse(domain.retired())
            fixture.resources.validate()
            val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(500)))
            assertSame(if (rulesetFailure) closeFailure else nativeFailure, first.cleanupFailure)
            val calls = recording.retireCalls.get()
            val closeCalls = rulesetCloseCalls.get()
            val replacements = mutableListOf<io.mazewall.ffi.internal.PreparedPath>()
            var reused: io.mazewall.ffi.internal.PreparedPath? = null
            if (rulesetFailure) {
                val originalFd = targetRuleset.get() as io.mazewall.platform.fd.FileDescriptor<*, *, *>
                repeat(128) {
                    if (reused == null) {
                        val replacement = io.mazewall.ffi.internal.PreparedPath
                            .open(fixture.relay.roots[0].requested, LinuxNative.snapshot())
                        replacements.add(replacement)
                        if (replacement.descriptor.value == originalFd.value) reused = replacement
                    }
                }
                assertNotNull(reused, "a real newly owned descriptor must reuse the closed ruleset number")
                println("REUSED_RULESET_FD original=${originalFd.value} replacement=${reused!!.descriptor.value}")
            }
            ready.value.close()
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(500)))
            assertEquals(calls, recording.retireCalls.get(), "Unknown native effects cannot be replayed")
            assertEquals(closeCalls, rulesetCloseCalls.get(), "Unknown ruleset close cannot be replayed")
            reused?.validate()
            if (rulesetFailure) assertEquals(1, closeCalls) else assertEquals(1, calls)
            // Replacement owners are closed by disposable child-process teardown, never reclosed after an unknown outcome.
            assertFalse(domain.retired())
            assertTrue(fixture.managerThread.isAlive)
        }

    internal fun transferredRefusedProbe() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.refuseInvalidate.set(true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            assertFailsWith<UnsupportedOperationException> {
                fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
            }
            val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!domain.initialConfinedRetirementEnded() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(domain.initialConfinedRetirementEnded())
            assertFalse(domain.retired())
            val release = domain.javaClass.getDeclaredField("liveRelease").let {
                it.isAccessible = true
                checkNotNull(it.get(domain))
            }
            val outcome = release.javaClass.getDeclaredField("nativeOutcome").let {
                it.isAccessible = true
                it.get(release)
            }
            assertSame(recording.refusedOutcome, outcome)
            val calls = recording.retireCalls.get()
            assertEquals(1, calls)
            skipFixtureResourceClose = true
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            ready.value.close()
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertEquals(calls, recording.retireCalls.get())
            assertFalse(domain.retired())
            fixture.resources.validate()
        }

    internal fun managerRulesetFatalProbe() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val claimFailure = IllegalStateException("post-store claim failure")
            val closeFailure = OutOfMemoryError("actual manager ruleset close failure")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            skipFixtureResourceClose = true
            val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                scopingMethod().invoke(
                    fixture.runtime,
                    (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                    DomainLimits(1, 1),
                    ready.value,
                    scopingEffects(afterTypedStore = {
                        val state = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                        val shell = state.acquisition.landlock
                        val fd = shell.javaClass.getDeclaredField("acquiredRuleset").let {
                            it.isAccessible = true
                            it.get(shell)
                        }
                        targetRuleset.set(checkNotNull(fd))
                        rulesetCloseFailure.set(closeFailure)
                        throw claimFailure
                    }),
                )
            }
            assertSame(claimFailure, thrown.targetException)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val observed = CompletableFuture<Throwable>()
            val closer = Thread.ofPlatform().start {
                observed.complete(runCatching { ready.value.close() }.exceptionOrNull() ?: AssertionError("close unexpectedly succeeded"))
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (closer.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.TIMED_WAITING, closer.state, "close must wait on the original release future")
            } finally {
                release.countDown()
            }
            assertSame(closeFailure, observed.get(5, TimeUnit.SECONDS))
            closer.join(6000)
            assertFalse(closer.isAlive)
            assertEquals(1, rulesetCloseCalls.get())
            val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(closeFailure, report.cleanupFailure)
            assertTrue(closeFailure.suppressed.any { it === claimFailure })
            assertSame(closeFailure, assertFailsWith<IllegalStateException> { ready.value.close() }.cause)
            assertEquals(1, rulesetCloseCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `ordinary wait observer failure retains actual timeout and claim`() = waitObserverFailureProbe(false)

    @Test
    fun `fatal wait observer failure retains actual timeout and claim`() = waitObserverFailureProbe(true)

    @Test
    fun `direct throwable wait observer retains actual timeout and claim`() = waitObserverFailureProbe(false, true)

    private fun waitObserverFailureProbe(
        fatal: Boolean,
        direct: Boolean = false,
    ) = withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val observerFailure: Throwable = if (direct) {
                object : Throwable("observer direct") {}
            } else if (fatal) {
                LinkageError("observer fatal")
            } else {
                IllegalStateException("observer ordinary")
            }
            val outcome = CompletableFuture<Throwable>()
            beforeNative = { nr ->
                if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(waitFailureObserver = {
                        throw observerFailure
                    }),
                    )
                    outcome.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    outcome.complete(failure.targetException)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val thrown = outcome.get(4, TimeUnit.SECONDS)
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                val timeoutFailure = assertIs<java.util.concurrent.TimeoutException>(assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Failed>(claiming.request).cause)
                if (fatal) {
                    assertSame(observerFailure, thrown)
                    assertTrue(thrown.suppressed.any { it === timeoutFailure })
                } else {
                    assertSame(timeoutFailure, thrown)
                    assertTrue(thrown.suppressed.any { it === observerFailure })
                }
                assertSame(if (fatal) observerFailure else timeoutFailure, claiming.history)
                val forged = io.mazewall.enforcer.internal.execution.ScopingPreparation.evaluate(
                    claiming,
                    io.mazewall.enforcer.internal.execution.ScopingPreparation.Event.PrepareWaitFailed(
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.WaitObservation.HistoryObservation(
                            timeoutFailure,
                            observerFailure,
                            io.mazewall.enforcer.internal.execution.ScopingPreparation.ObservationOrigin
                                .Claim(Any()),
                        ),
                    ),
                )
                assertSame(claiming, forged.state)
                assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.Effect.Reject>(forged.effects.single())
                assertFalse(association.claimFinished.isDone)
                release.countDown()
                association.claimFinished.get(5, TimeUnit.SECONDS)
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.isEmpty())
                assertSame(if (fatal) observerFailure else timeoutFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `physical settlement before duplicate waiter observation never repeats domain failure`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val observed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val outcome = CompletableFuture<Throwable>()
            val observer = {
                if (calls.incrementAndGet() == 1) {
                    observed.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else {
                    throw AssertionError("duplicate waiter must not reserve another FailDomain")
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(waitFailureObserver = observer),
                    )
                    outcome.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    outcome.complete(failure.targetException)
                }
            }
            try {
                assertTrue(observed.await(5, TimeUnit.SECONDS))
                val owned = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                val original = assertIs<UnsupportedOperationException>(owned.history)
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(original, first.cleanupFailure)
                val settled = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(owned.domain, settled.domain)
                assertTrue(settled.domain.retired())
                val nativeCalls = recording.retireCalls.get()
                ready.value.close()
                release.countDown()
                assertSame(original, outcome.get(5, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertSame(original, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(nativeCalls, recording.retireCalls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `committed live timeout retains exact failure across physical settlement before reserved effect`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val originalReady = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val method = scopingMethod()
            val committed = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val effectReserved = CountDownLatch(1)
            val releaseEffect = CountDownLatch(1)
            val observerCalls = AtomicInteger()
            val outcome = CompletableFuture<Throwable>()
            val afterCommit = {
                committed.countDown()
                check(releaseManager.await(5, TimeUnit.SECONDS))
            }
            val waitObserver = {
                if (observerCalls.incrementAndGet() == 2) {
                    effectReserved.countDown()
                    check(releaseEffect.await(5, TimeUnit.SECONDS))
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(waitFailureObserver = waitObserver, afterDomainCommit = afterCommit),
                    )
                    outcome.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    outcome.complete(failure.targetException)
                }
            }
            try {
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                val owned = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                val scoping = owned.domain.javaClass.getDeclaredField("scoping").let {
                    it.isAccessible = true
                    it.get(owned.domain)
                }
                assertSame(originalReady.frozen, scoping)
                assertTrue(effectReserved.await(5, TimeUnit.SECONDS), "actual timed-out caller must reserve exact-domain failure")
                val reserved = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                assertSame(owned.domain, reserved.domain)
                val timeoutFailure = assertIs<java.util.concurrent.TimeoutException>(reserved.history)
                releaseManager.countDown()
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(timeoutFailure, first.cleanupFailure)
                val settled = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(owned.domain, settled.domain)
                assertTrue(settled.domain.retired())
                assertSame(timeoutFailure, settled.history)
                assertTrue(timeoutFailure.suppressed.any { it is UnsupportedOperationException })
                val nativeCalls = recording.retireCalls.get()
                ready.value.close()
                releaseEffect.countDown()
                assertSame(timeoutFailure, outcome.get(5, TimeUnit.SECONDS))
                assertEquals(2, observerCalls.get())
                assertSame(timeoutFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(nativeCalls, recording.retireCalls.get())
                assertEquals(1, installs.get())
                ready.value.close()
            } finally {
                releaseManager.countDown()
                releaseEffect.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `fatal observer after reserved effect survives physical settlement`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val originalReady = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val method = scopingMethod()
            val committed = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val effectReserved = CountDownLatch(1)
            val releaseEffect = CountDownLatch(1)
            val observerCalls = AtomicInteger()
            val observerFailure = LinkageError("observer after settlement")
            val outcome = CompletableFuture<Throwable>()
            val afterCommit = {
                committed.countDown()
                check(releaseManager.await(5, TimeUnit.SECONDS))
            }
            val waitObserver = {
                if (observerCalls.incrementAndGet() == 2) {
                    effectReserved.countDown()
                    check(releaseEffect.await(5, TimeUnit.SECONDS))
                    throw observerFailure
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(waitFailureObserver = waitObserver, afterDomainCommit = afterCommit),
                    )
                    outcome.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    outcome.complete(failure.targetException)
                }
            }
            try {
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                val owned = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                val scoping = owned.domain.javaClass.getDeclaredField("scoping").let {
                    it.isAccessible = true
                    it.get(owned.domain)
                }
                assertSame(originalReady.frozen, scoping)
                assertTrue(effectReserved.await(5, TimeUnit.SECONDS), "actual timed-out caller must reserve exact-domain failure")
                val reserved = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                assertSame(owned.domain, reserved.domain)
                val timeoutFailure = assertIs<java.util.concurrent.TimeoutException>(reserved.history)
                releaseManager.countDown()
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(timeoutFailure, first.cleanupFailure)
                val settled = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(owned.domain, settled.domain)
                assertTrue(settled.domain.retired())
                assertSame(timeoutFailure, settled.history)
                assertTrue(timeoutFailure.suppressed.any { it is UnsupportedOperationException })
                val nativeCalls = recording.retireCalls.get()
                ready.value.close()
                releaseEffect.countDown()
                assertSame(observerFailure, outcome.get(5, TimeUnit.SECONDS))
                assertTrue(observerFailure.suppressed.any { it === timeoutFailure })
                val finalState = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(observerFailure, finalState.history)
                val duplicate = io.mazewall.enforcer.internal.execution.ScopingPreparation.evaluate(
                    finalState,
                    io.mazewall.enforcer.internal.execution.ScopingPreparation.Event.PrepareWaitFailed(
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.WaitObservation
                            .Initial(timeoutFailure),
                    ),
                )
                assertSame(finalState, duplicate.state)
                assertTrue(duplicate.effects.isEmpty())
                val wrongOrigin = io.mazewall.enforcer.internal.execution.ScopingPreparation.evaluate(
                    finalState,
                    io.mazewall.enforcer.internal.execution.ScopingPreparation.Event.PrepareWaitFailed(
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.WaitObservation.HistoryObservation(
                            timeoutFailure,
                            observerFailure,
                            io.mazewall.enforcer.internal.execution.ScopingPreparation.ObservationOrigin
                                .Claim(Any()),
                        ),
                    ),
                )
                assertSame(finalState, wrongOrigin.state)
                assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.Effect.Reject>(wrongOrigin.effects.single())
                val cycleA = IllegalStateException("cycle A")
                val cycleB = IllegalStateException("cycle B")
                cycleA.addSuppressed(cycleB)
                cycleB.addSuppressed(cycleA)
                val cyclicState = finalState.copy(history = cycleA)
                val cyclicDuplicate = io.mazewall.enforcer.internal.execution.ScopingPreparation.evaluate(
                    cyclicState,
                    io.mazewall.enforcer.internal.execution.ScopingPreparation.Event.PrepareWaitFailed(
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.WaitObservation
                            .Initial(cycleB),
                    ),
                )
                assertSame(cyclicState, cyclicDuplicate.state)
                assertTrue(cyclicDuplicate.effects.isEmpty())
                assertEquals(2, observerCalls.get())
                assertSame(observerFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(nativeCalls, recording.retireCalls.get())
                assertEquals(1, installs.get())
                ready.value.close()
            } finally {
                releaseManager.countDown()
                releaseEffect.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `distinct waiter failure never invokes retained throwable cause under runtime gate`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val gate = ManagedRuntime::class.java.getDeclaredField("lock").let {
                it.isAccessible = true
                it.get(fixture.runtime)
            }
            val getterCalledUnderGate = java.util.concurrent.atomic
                .AtomicBoolean()
            val retained = object : Throwable("retained custom throwable") {
                override val cause: Throwable?
                    get() {
                        if (Thread.holdsLock(gate)) {
                            getterCalledUnderGate.set(true)
                            throw AssertionError("custom cause getter ran under runtime gate")
                        }
                        return null
                    }
            }
            val committed = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val waitObserved = CountDownLatch(1)
            val releaseObserver = CountDownLatch(1)
            val result = CompletableFuture<Throwable>()
            val caller = Thread.ofPlatform().start {
                try {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(
                            afterDomainCommit = {
                                committed.countDown()
                                check(releaseManager.await(5, TimeUnit.SECONDS))
                                throw retained
                            },
                            waitFailureObserver = {
                                waitObserved.countDown()
                                check(releaseObserver.await(5, TimeUnit.SECONDS))
                            },
                        ),
                    )
                    result.complete(AssertionError("prepare unexpectedly succeeded"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    result.complete(failure.targetException)
                }
            }
            try {
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                assertTrue(waitObserved.await(5, TimeUnit.SECONDS))
                releaseManager.countDown()
                association.claimFinished.get(5, TimeUnit.SECONDS)
                val owned = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                assertSame(retained, owned.history)
                releaseObserver.countDown()
                val observedFailure = result.get(5, TimeUnit.SECONDS)
                assertFalse(getterCalledUnderGate.get(), "retained cause getter must not run under runtime gate")
                val timeoutFailure = assertIs<java.util.concurrent.TimeoutException>(observedFailure)
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(retained, report.cleanupFailure)
                assertTrue(retained.suppressed.any { it === timeoutFailure })
            } finally {
                releaseManager.countDown()
                releaseObserver.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `postcommit observer failure retains original and retires exact domain`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = LinkageError("postcommit observer")
            val method = scopingMethod()
            try {
                val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(afterDomainCommit = { throw original }),
                    )
                }
                assertSame(original, failure.targetException)
                val transferred = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                val domain = transferred.domain
                val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(original, first.cleanupFailure)
                val settled = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(domain, settled.domain)
                assertTrue(domain.retired())
                ready.value.close()
                assertEquals(1, installs.get())
            } finally {
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `wrong live wrapper and runtime cannot consume ready`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val otherWrapper = PreparedStackScoping(original.frozen.bootstrap)
            val policy = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy
            val other = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)
            try {
                assertFailsWith<IllegalArgumentException> { fixture.runtime.prepare(policy, DomainLimits(1, 1), otherWrapper) }
                assertSame(original, association.state)
                assertFailsWith<IllegalArgumentException> { other.prepare(policy, DomainLimits(1, 1), ready.value) }
                assertSame(original, association.state)
                assertSame(ready, original.result)
                fixture.resources.validate()
                assertEquals(0, recording.retireCalls.get())
                assertIs<DrainReport.Complete>(other.drain(timeout))
            } finally {
                other.drain(timeout)
                ready.value.close()
            }
        }

    @Test
    fun `ordinary reserved effect observer failure still fails exact domain`() = reservedEffectObserverFailureProbe(false)

    @Test
    fun `fatal reserved effect observer failure still fails exact domain`() = reservedEffectObserverFailureProbe(true)

    @Test
    fun `direct throwable reserved effect observer failure still fails exact domain`() = reservedEffectObserverFailureProbe(false, true)

    private fun reservedEffectObserverFailureProbe(
        fatal: Boolean,
        direct: Boolean = false,
    ) = withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val committed = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val observerCalls = AtomicInteger()
            val observerFailure: Throwable = when {
                fatal -> LinkageError("reserved effect observer fatal")
                direct -> object : Throwable("reserved effect observer direct throwable") {}
                else -> IllegalStateException("reserved effect observer ordinary")
            }
            val outcome = CompletableFuture<Throwable>()
            val afterCommit = {
                committed.countDown()
                check(releaseManager.await(5, TimeUnit.SECONDS))
            }
            val observer = { if (observerCalls.incrementAndGet() == 2) throw observerFailure }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(waitFailureObserver = observer, afterDomainCommit = afterCommit),
                    )
                    outcome.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    outcome.complete(failure.targetException)
                }
            }
            try {
                assertTrue(committed.await(5, TimeUnit.SECONDS))
                val thrown = outcome.get(4, TimeUnit.SECONDS)
                val state = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state)
                val timeoutFailure = if (fatal) {
                    assertSame(observerFailure, state.history)
                    assertIs<java.util.concurrent.TimeoutException>(observerFailure.suppressed.single { it is java.util.concurrent.TimeoutException })
                } else {
                    assertIs<java.util.concurrent.TimeoutException>(state.history)
                }
                assertEquals(2, observerCalls.get())
                if (fatal) {
                    assertSame(observerFailure, thrown)
                    assertTrue(thrown.suppressed.any { it === timeoutFailure })
                } else {
                    assertSame(timeoutFailure, thrown)
                    assertTrue(thrown.suppressed.any { it === observerFailure })
                }
                releaseManager.countDown()
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(if (fatal) observerFailure else timeoutFailure, report.cleanupFailure)
                assertSame(report.cleanupFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertTrue(state.domain.retired())
                val settled = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled>(association.state)
                assertSame(state.domain, settled.domain)
                val nativeCalls = recording.retireCalls.get()
                ready.value.close()
                assertEquals(nativeCalls, recording.retireCalls.get())
            } finally {
                releaseManager.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `concurrent close and drain observe active claim without queuing a sweep`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val callerDone = CompletableFuture<Throwable>()
            val closeDone = CompletableFuture<Throwable?>()
            val hook = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Unit
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(fixture.runtime, (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value, scopingEffects(afterTypedStore = hook))
                    callerDone.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    callerDone.complete(failure.targetException)
                }
            }
            var closer: Thread? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                closer = Thread.ofPlatform().start {
                    closeStarted.countDown()
                    closeDone.complete(runCatching { ready.value.close() }.exceptionOrNull())
                }
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                assertFalse(closeDone.isDone)
                assertTrue(fixture.manager.queue.isEmpty(), "no sweep may queue ahead of the active claimant")
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
                release.countDown()
                assertIs<java.util.concurrent.RejectedExecutionException>(callerDone.get(5, TimeUnit.SECONDS))
                assertNotNull(closeDone.get(5, TimeUnit.SECONDS))
                assertTrue(association.claimFinished.isDone)
                assertEquals(1, installs.get())
            } finally {
                release.countDown()
                caller.join(6000)
                closer?.join(6000)
                assertFalse(caller.isAlive)
                assertFalse(closer?.isAlive == true)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `foreign captured native engine refuses before consuming ready or generation`() =
        withRuntime { fixture ->
            val originalEngine = LinuxNative.engineIdentity as NativeEngine
            val otherEngine = object : NativeEngine by originalEngine {}
            val policy = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy
            val foreign = try {
                LinuxNative.setEngine(otherEngine)
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, fixture.resources.implementationClass, emptySet())
            } finally {
                LinuxNative.setEngine(originalEngine)
            }
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, foreign, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val generation = runtimeState(fixture.runtime).javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            val before = generation.getLong(runtimeState(fixture.runtime))
            try {
                assertFailsWith<IllegalArgumentException> { fixture.runtime.prepare(policy, DomainLimits(1, 1), ready.value) }
                assertSame(original, association.state)
                assertEquals(before, generation.getLong(runtimeState(fixture.runtime)), "mismatch must not reserve a generation")
                assertEquals(0, recording.retireCalls.get())
                foreign.validate()
            } finally {
                ready.value.close()
            }
        }

    @Test
    fun `claim validation failure retains available owner and never creates a ruleset`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val creates = AtomicInteger()
            beforeNative = { nr ->
                if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) creates.incrementAndGet()
            }
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            Files.write(directory.resolve("operation.jar"), byteArrayOf(1, 2, 3))
            try {
                val failure = assertFailsWith<java.util.zip.ZipException> {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val retained = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state).retained
                assertSame(failure, retained.failure)
                assertEquals(io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned, retained.disposition)
                assertEquals(
                    "AVAILABLE",
                    fixture.resources.javaClass.getDeclaredField("phase").let {
                    it.isAccessible = true
                    it.get(fixture.resources).toString()
                },
                )
                assertEquals(0, creates.get())
                assertTrue(
                    ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    (it.get(fixture.runtime) as Map<*, *>).isEmpty()
                },
                )
                release.countDown()
                assertSame(failure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(0, creates.get())
            } finally {
                release.countDown()
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `second live claimant cannot consume active claim carrier`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val method = scopingMethod()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val managerCarrier = java.util.concurrent.atomic
                .AtomicReference<io.mazewall.enforcer.internal.execution.ScopingPreparation.ClaimProgress>()
            val result = CompletableFuture<Throwable>()
            beforeNative = { nr -> if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) calls.incrementAndGet() }
            val hook = {
                managerCarrier.set(assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state).acquisition)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Unit
            }
            val policy = (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(fixture.runtime, policy, DomainLimits(1, 1), ready.value, scopingEffects(afterTypedStore = hook))
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    result.complete(failure.targetException)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val first = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(original, first.acquisition.ready)
                assertSame(first.acquisition, managerCarrier.get())
                assertSame(original.frozen, first.acquisition.scoping)
                assertTrue(first.acquisition.generation > 0)
                assertFailsWith<IllegalArgumentException> { fixture.runtime.prepare(policy, DomainLimits(1, 1), ready.value) }
                val again = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(first.acquisition, again.acquisition)
                assertEquals(1, calls.get(), "the competing caller must not cause another ruleset acquisition")
                release.countDown()
                assertIs<UnsupportedOperationException>(result.get(5, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `close wins while live policy snapshot is still outside gate`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val worker = fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val originalPaths = worker.policy.definition.allowedFsReadPaths
            val blocked = object : AbstractSet<io.mazewall.platform.lifecycle.SandboxedPath>() {
                override val size: Int get() = originalPaths.size

                override fun iterator(): Iterator<io.mazewall.platform.lifecycle.SandboxedPath> {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return originalPaths.iterator()
                }
            }
            val policy = Policy<PolicyScope.ThreadLocalOnly, Uncompiled>(worker.policy.definition.copy(allowedFsReadPaths = blocked))
            val generation = runtimeState(fixture.runtime).javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            val before = generation.getLong(runtimeState(fixture.runtime))
            val result = CompletableFuture<Throwable>()
            val caller = Thread.ofPlatform().start {
                try {
                    fixture.runtime.prepare(policy, DomainLimits(1, 1), ready.value)
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: Throwable) {
                    result.complete(failure)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                ready.value.close()
                val nativeCalls = recording.retireCalls.get()
                release.countDown()
                assertIs<IllegalStateException>(result.get(5, TimeUnit.SECONDS))
                assertEquals(before, generation.getLong(runtimeState(fixture.runtime)))
                assertEquals(nativeCalls, recording.retireCalls.get())
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.isEmpty())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `actual origin gate rejects forged claim carrier provenance`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val original = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready>(association.state)
            val gate = ManagedRuntime::class.java.getDeclaredField("lock").let {
                it.isAccessible = true
                it.get(fixture.runtime)
            }
            val generation = runtimeState(fixture.runtime).javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            val before = generation.getLong(runtimeState(fixture.runtime))
            val worker = fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker
            val transition = Class
                .forName("io.mazewall.enforcer.api.execution.ManagedRuntimeKt")
                .getDeclaredMethod(
                "transitionScoping",
                io.mazewall.enforcer.internal.execution.ScopingPreparation::class.java,
                io.mazewall.enforcer.internal.execution.ScopingPreparation::class.java,
                io.mazewall.enforcer.internal.execution.ScopingPreparation.Event::class.java,
            ).apply { isAccessible = true }
            val shell = io.mazewall.landlock.PreparedLandlock
                .allocateRetained(fixture.resources.resources, association.environment)
            val otherShell = io.mazewall.landlock.PreparedLandlock
                .allocateRetained(fixture.resources.resources, association.environment)

            fun attempt(
                carrierReady: io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Ready,
                eventShell: io.mazewall.landlock.PreparedLandlock,
                eventGeneration: Long,
            ) {
                val carrier = io.mazewall.enforcer.internal.execution.ScopingPreparation
                    .ClaimProgress(carrierReady, shell, original.frozen)
                synchronized(gate) {
                    carrier.bindGeneration(before + 1)
                    val event = io.mazewall.enforcer.internal.execution.ScopingPreparation.Event.ClaimRequested(
                        fixture.runtime,
                        worker.definition,
                        DomainLimits(1, 1),
                        association.environment.native,
                        true,
                        eventGeneration,
                        eventShell,
                        original.frozen,
                        carrier,
                    )
                    val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> { transition.invoke(null, association, association, event) }
                    assertIs<IllegalStateException>(thrown.targetException)
                    assertSame(original, association.state)
                }
            }
            try {
                attempt(original.copy(), shell, before + 1)
                attempt(original, otherShell, before + 1)
                attempt(original, shell, before + 2)
                assertEquals(before, generation.getLong(runtimeState(fixture.runtime)))
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                ready.value.close()
            }
        }

    @Test
    fun `interrupted live prepare retains original cause and does not cancel manager`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val managerInterrupted = java.util.concurrent.atomic
                .AtomicBoolean()
            val result = CompletableFuture<Pair<Throwable, Boolean>>()
            beforeNative = { nr ->
                if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    managerInterrupted.set(Thread.currentThread().isInterrupted)
                }
            }
            val caller = Thread.ofPlatform().start {
                try {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                    result.complete(AssertionError("prepare unexpectedly returned") to Thread.currentThread().isInterrupted)
                } catch (failure: Throwable) {
                    result.complete(failure to Thread.currentThread().isInterrupted)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.interrupt()
                val (failure, interruptRestored) = result.get(4, TimeUnit.SECONDS)
                assertIs<InterruptedException>(failure)
                assertTrue(interruptRestored)
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(failure, assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Failed>(claiming.request).cause)
                assertFalse(association.claimFinished.isDone)
                fixture.resources.validate()
                release.countDown()
                association.claimFinished.get(5, TimeUnit.SECONDS)
                assertFalse(managerInterrupted.get())
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.isEmpty())
                assertSame(failure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `timed out queued live claim never consumes original resources`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val busy = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val cleanup = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val result = CompletableFuture<Throwable>()
            recording.beforeInvalidate = {
                cleanup.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            }
            fixture.manager.execute {
                busy.countDown()
                check(releaseManager.await(5, TimeUnit.SECONDS))
            }
            assertTrue(busy.await(5, TimeUnit.SECONDS))
            val caller = Thread.ofPlatform().start {
                try {
                    fixture.runtime.prepare((fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value)
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: Throwable) {
                    result.complete(failure)
                }
            }
            try {
                val failure = assertIs<java.util.concurrent.TimeoutException>(result.get(4, TimeUnit.SECONDS))
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(failure, assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Failed>(claiming.request).cause)
                assertEquals(io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned, claiming.disposition)
                assertFalse(association.claimFinished.isDone)
                releaseManager.countDown()
                assertTrue(cleanup.await(5, TimeUnit.SECONDS))
                val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(failure, retiring.retained.failure)
                assertEquals(
                    io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned,
                    retiring.retained.disposition,
                    "failed queued waiter must prevent the sole resource claim",
                )
                fixture.resources.validate()
            } finally {
                releaseManager.countDown()
                releaseCleanup.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `observational close timeout does not fail or cancel active live claim`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(15)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = CompletableFuture<Throwable>()
            val hook = {
                entered.countDown()
                check(release.await(12, TimeUnit.SECONDS))
                Unit
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(fixture.runtime, (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value, scopingEffects(afterTypedStore = hook))
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    result.complete(failure.targetException)
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { ready.value.close() }
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Awaiting>(claiming.request)
                assertFalse(association.claimFinished.isDone)
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
                release.countDown()
                assertIs<UnsupportedOperationException>(result.get(5, TimeUnit.SECONDS))
                assertTrue(
                    association.state is io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned ||
                        association.state is io.mazewall.enforcer.internal.execution.ScopingPreparation.State.TransferredSettled,
                )
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `drain reserved before native pending cannot retry that pending in same pass`() =
        withRuntime(preparationTimeout = Duration.ofSeconds(1)) { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingRetire.set(1)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val method = scopingMethod()
            val commitEntered = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val nativeEntered = CountDownLatch(1)
            val releaseNative = CountDownLatch(1)
            val callerResult = CompletableFuture<Throwable>()
            val drainResult = CompletableFuture<DrainReport>()
            recording.beforeInvalidate = {
                nativeEntered.countDown()
                check(releaseNative.await(8, TimeUnit.SECONDS))
            }
            val afterCommit = {
                commitEntered.countDown()
                check(releaseManager.await(8, TimeUnit.SECONDS))
                Unit
            }
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(afterDomainCommit = afterCommit),
                    )
                    callerResult.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    callerResult.complete(failure.targetException)
                }
            }
            var drainer: Thread? = null
            try {
                assertTrue(commitEntered.await(5, TimeUnit.SECONDS))
                val original = assertIs<java.util.concurrent.TimeoutException>(callerResult.get(4, TimeUnit.SECONDS))
                assertTrue(nativeEntered.await(5, TimeUnit.SECONDS))
                val domain = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.DomainOwned>(association.state).domain
                drainer = Thread.ofPlatform().start { drainResult.complete(fixture.runtime.drain(timeout)) }
                val queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (fixture.manager.queue.isEmpty() && System.nanoTime() < queueDeadline) Thread.yield()
                assertFalse(fixture.manager.queue.isEmpty(), "the first drain must reserve its sweep before Pending")
                releaseNative.countDown()
                val retirementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!domain.initialConfinedRetirementEnded() && System.nanoTime() < retirementDeadline) Thread.yield()
                assertTrue(domain.initialConfinedRetirementEnded())
                assertEquals(2, recording.retireCalls.get())
                releaseManager.countDown()
                assertSame(original, assertIs<DrainReport.Incomplete>(drainResult.get(6, TimeUnit.SECONDS)).cleanupFailure)
                assertFalse(domain.retired(), "a pass reserved before Pending cannot retry it")
                assertEquals(2, recording.retireCalls.get())
                assertSame(original, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertTrue(domain.retired())
                assertEquals(3, recording.retireCalls.get())
            } finally {
                releaseNative.countDown()
                releaseManager.countDown()
                caller.join(6000)
                drainer?.join(6000)
                assertFalse(caller.isAlive)
                assertFalse(drainer?.isAlive == true)
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `fatal preawait while queued retains available owner and skips claim`() = fatalPreawaitProbe(false)

    @Test
    fun `fatal preawait during actual claim retains claimed owner until quiescent`() = fatalPreawaitProbe(true)

    @Test
    fun `direct throwable preawait retains actual queued claim`() = fatalPreawaitProbe(false, true)

    private fun fatalPreawaitProbe(
        inFlight: Boolean,
        direct: Boolean = false,
    ) = withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.ScopingTestEffects")
            val effectsConstructor = effectsClass
                .getDeclaredConstructor(
                kotlin.jvm.functions.Function0::class.java,
                kotlin.jvm.functions.Function0::class.java,
                kotlin.jvm.functions.Function0::class.java,
                kotlin.jvm.functions.Function0::class.java,
            ).apply { isAccessible = true }
            val method = scopingMethod()
            val busy = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val nativeEntered = CountDownLatch(1)
            val releaseNative = CountDownLatch(1)
            val cleanupEntered = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val fatal: Throwable = if (direct) object : Throwable("direct pre-await failure") {} else LinkageError("controlled pre-await failure")
            val result = CompletableFuture<Throwable>()
            recording.beforeInvalidate = {
                cleanupEntered.countDown()
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            }
            if (inFlight) {
                beforeNative = { nr ->
                    if (Thread.currentThread() === fixture.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                        nativeEntered.countDown()
                        check(releaseNative.await(5, TimeUnit.SECONDS))
                    }
                }
            } else {
                fixture.manager.execute {
                    busy.countDown()
                    check(releaseManager.await(5, TimeUnit.SECONDS))
                }
                assertTrue(busy.await(5, TimeUnit.SECONDS))
            }
            val beforeWait = {
                if (inFlight) check(nativeEntered.await(5, TimeUnit.SECONDS))
                throw fatal
            }
            val effects = effectsConstructor.newInstance({}, {}, {}, beforeWait)
            val caller = Thread.ofPlatform().start {
                try {
                    method.invoke(fixture.runtime, (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy, DomainLimits(1, 1), ready.value, effects)
                    result.complete(AssertionError("prepare unexpectedly returned"))
                } catch (failure: java.lang.reflect.InvocationTargetException) {
                    result.complete(failure.targetException)
                }
            }
            try {
                assertSame(fatal, result.get(5, TimeUnit.SECONDS))
                val claiming = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Claiming>(association.state)
                assertSame(fatal, assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.PrepareRequest.Failed>(claiming.request).cause)
                assertEquals(
                    if (inFlight) {
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.ClaimedOwned
                    } else {
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned
                    },
                    claiming.disposition,
                )
                assertFalse(association.claimFinished.isDone)
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
                releaseNative.countDown()
                releaseManager.countDown()
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(fatal, retiring.retained.failure)
                assertEquals(
                    if (inFlight) {
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.ClaimedOwned
                    } else {
                        io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.AvailableOwned
                    },
                    retiring.retained.disposition,
                )
                val domains = ManagedRuntime::class.java.getDeclaredField("domains").let {
                    it.isAccessible = true
                    it.get(fixture.runtime) as Map<*, *>
                }
                assertTrue(domains.isEmpty())
                assertFalse(fixture.managerThread.isInterrupted)
            } finally {
                releaseManager.countDown()
                releaseNative.countDown()
                releaseCleanup.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
                beforeNative = {}
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `direct throwable after typed store retains exact original through retirement`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val original = object : Throwable("direct post-store failure") {}
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            try {
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(
                        1,
                        1,
                    ),
                        ready.value,
                            scopingEffects(afterTypedStore = {
                        throw original
                    }),
                    )
                }
                assertSame(original, thrown.targetException)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val retiring = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(original, retiring.retained.failure)
                assertNotNull(retiring.retained.landlock)
                assertEquals(io.mazewall.enforcer.internal.execution.ScopingPreparation.ResourceDisposition.ClaimedOwned, retiring.retained.disposition)
                fixture.resources.validate()
                release.countDown()
                assertSame(original, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
            } finally {
                release.countDown()
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `manager interrupted exception does not interrupt waiting caller`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val original = InterruptedException("manager post-store failure")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                assertFalse(Thread.currentThread().isInterrupted)
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(afterTypedStore = { throw original }),
                    )
                }
                assertSame(original, thrown.targetException)
                assertFalse(Thread.currentThread().isInterrupted)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                release.countDown()
                assertSame(original, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
            } finally {
                release.countDown()
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `preparedClosePendingRetainsOwnerOnSameManager`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingRetire.set(1)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            assertFailsWith<IllegalStateException> { ready.value.close() }
            assertFalse(fixture.manager.isShutdown)
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            fixture.resources.validate()
            ready.value.close()
            assertEquals(3, recording.retireCalls.get())
            assertFails { fixture.resources.validate() }
            ready.value.close()
            assertEquals(3, recording.retireCalls.get())
        }

    @Test
    fun `historySurvivesSettlementWithoutDoubleRelease`() =
        withRuntime { fixture ->
            val recording = RecordingCapture()
            val original = OutOfMemoryError("controlled construction failure")
            recording.pendingRetire.set(1)
            assertSame(
                original,
                assertFailsWith<OutOfMemoryError> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) { throw original }
            },
            )
            val first = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(original, first.cleanupFailure)
            assertEquals(2, recording.retireCalls.get())
            val second = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(original, second.cleanupFailure)
            assertEquals(3, recording.retireCalls.get())
            fixture.runtime.drain(timeout)
            assertEquals(3, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `captureFreezesIdentityRoutesCorridorsAndHandlersOnce`() =
        withRuntime { fixture ->
            assertActualCaptureFreezesInputs(fixture.reviewed, fixture.resources)
        }

    @Test
    fun `staticDenyRefusesBeforeNativeBootstrap`() =
        withRuntime { fixture ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.OPEN).build()
            val document = OperationPolicyDocument(fixture.reviewed.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
            val denied = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), fixture.reviewed.binding)
            assertFailsWith<IllegalArgumentException> {
                captureWithMissingAgent(fixture.runtime, denied, fixture.resources)
            }
            fixture.resources.validate()
        }

    @Test
    fun `emptyCorridorsRefuseBeforeNativeBootstrap`() =
        withRuntime { fixture ->
            assertFailsWith<IllegalArgumentException> {
                captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources, omitCorridor = true)
            }
            fixture.resources.validate()
        }

    @Test
    fun `capturedNonRealIgnoresAmbientReal`() =
        withRuntime { fixture ->
            val previous = LinuxNative.engineIdentity as NativeEngine
            try {
                LinuxNative.setEngine(RealNativeEngine)
                val failure = assertIs<LiveSetup.Refused>(captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources)).failure
                assertEquals(LiveFailureStatus.Unsupported, failure.status)
                assertEquals(LiveFailureReason.RuntimeMismatch, failure.reason)
            } finally {
                LinuxNative.setEngine(previous)
            }
        }

    @Test
    fun `activeConstructionRejectsSecondAdmission`() =
        withRuntime { fixture ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CompletableFuture<Any>()
            val recording = RecordingCapture()
            val caller = Thread.ofPlatform().start {
                try {
                    finished.complete(
                        captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    },
                    )
                } catch (failure: Throwable) {
                    finished.completeExceptionally(failure)
                }
            }
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                assertFailsWith<java.util.concurrent.RejectedExecutionException> { captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources) }
                assertEquals(0, recording.retireCalls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                assertFalse(caller.isAlive)
            }
            assertIs<LiveSetup.Refused>(finished.get(5, TimeUnit.SECONDS))
        }

    @Test
    fun `reviewedRuntimeMismatchRejectsBeforeNativeBootstrap`() =
        withRuntime { fixture ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
            val document = OperationPolicyDocument(fixture.reviewed.binding, CanonicalPolicyArtifact.worker(RuntimeProfile.NATIVE_IMAGE, policy), emptySet(), emptyMap())
            val other = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), fixture.reviewed.binding)
            assertFailsWith<IllegalArgumentException> {
                captureWithMissingAgent(fixture.runtime, other, fixture.resources)
            }
            fixture.resources.validate()
        }

    @Test
    fun `queueFullCloseRetainsOwnerWithoutCallerRuns`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val running = fixture.manager.submit {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = fixture.manager.submit { }
            try {
                assertFailsWith<IllegalStateException> { ready.value.close() }
                assertEquals(0, recording.retireCalls.get())
                assertFalse(fixture.manager.isShutdown)
                fixture.resources.validate()
            } finally {
                release.countDown()
                running.get(5, TimeUnit.SECONDS)
                queued.get(5, TimeUnit.SECONDS)
            }
            ready.value.close()
            assertEquals(2, recording.retireCalls.get())
        }

    @Test
    fun `interruptedCloseDoesNotCancelAcceptedManagerRelease`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val running = fixture.manager.submit {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val result = CompletableFuture<Boolean>()
            val caller = Thread.ofPlatform().start {
                Thread.currentThread().interrupt()
                try {
                    ready.value.close()
                    result.complete(false)
                } catch (failure: IllegalStateException) {
                    result.complete(Thread.currentThread().isInterrupted && failure.cause is InterruptedException)
                }
            }
            try {
                assertTrue(result.get(5, TimeUnit.SECONDS))
                assertEquals(0, recording.retireCalls.get())
            } finally {
                release.countDown()
                running.get(5, TimeUnit.SECONDS)
                caller.join(5000)
                assertFalse(caller.isAlive)
            }
            assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
            assertEquals(2, recording.retireCalls.get())
        }

    @Test
    fun `producerGetterFailureRetainsOriginalHistoryAndBorrowedResources`() =
        withRuntime { fixture ->
            val original = LinkageError("controlled handler getter failure")
            val caller = Thread.currentThread()
            val getterCalls = AtomicInteger()
            val policy = object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler>
                    get() {
                        assertSame(caller, Thread.currentThread())
                        getterCalls.incrementAndGet()
                        throw original
                    }
            }
            val baselineInstalls = installs.get()
            assertSame(
                original,
                assertFailsWith<LinkageError> {
                captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources, scopingPolicy = policy)
            },
            )
            assertEquals(1, getterCalls.get())
            assertEquals(baselineInstalls, installs.get())
            val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(original, report.cleanupFailure)
            assertTrue(fixture.manager.isTerminated)
            fixture.resources.validate()
        }

    @Test
    fun `reserveOwnerCapabilityMismatchIsRetainedAndRejected`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true, wrongOwner = true)
            assertFailsWith<IllegalArgumentException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {}
            }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `drainDuringEnrollKeepsOwnerUntilConstructionEnds`() = drainDuringCapture(false)

    @Test
    fun `drainDuringSealKeepsOwnerUntilConstructionEnds`() = drainDuringCapture(true)

    private fun drainDuringCapture(pauseSeal: Boolean) {
            withRuntime { fixture ->
                val recording = RecordingCapture(completeCapture = true)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val barrier = {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    Unit
                }
                if (pauseSeal) recording.beforeSeal = barrier else recording.beforeEnroll = barrier
                val result = CompletableFuture<Any>()
                val caller = Thread.ofPlatform().start {
                    try {
                        result.complete(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertIs<DrainReport.Incomplete>(fixture.runtime.drain(Duration.ofMillis(100)))
                    assertFalse(fixture.manager.isShutdown)
                    assertEquals(0, recording.retireCalls.get())
                } finally {
                    release.countDown()
                    caller.join(6000)
                    assertFalse(caller.isAlive)
                }
                assertEquals(LiveFailureReason.Shutdown, assertIs<LiveSetup.Refused>(result.get(5, TimeUnit.SECONDS)).failure.reason)
                assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
                assertSame(recording.reserved.get(), recording.retiredOwner.get())
                fixture.resources.validate()
            }
    }

    @Test
    fun `reserveCapacityMismatchRetainsExactOwner`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true, wrongCapacity = true)
            assertFailsWith<IllegalArgumentException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {}
            }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `foreignEnrollmentRetainsExactStagingOwner`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true, wrongEnrollment = true)
            assertFailsWith<IllegalArgumentException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {}
            }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            assertEquals(2, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `aggregateCloseFailureRemainsUnknownWithoutRepeat`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            countOwnedCloses.set(true)
            injectOwnedCloseError.set(true)
            try {
                val first = assertFailsWith<IllegalStateException> { ready.value.close() }
                assertFalse(injectOwnedCloseError.get(), "the real owned close was not exercised")
                skipFixtureResourceClose = true
                val attempts = ownedCloseCalls.get()
                assertTrue(attempts > 0)
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(first.cause, report.cleanupFailure)
                assertFalse(fixture.manager.isShutdown, "Unknown aggregate must remain retained")
                assertFailsWith<IllegalStateException> { ready.value.close() }
                assertEquals(attempts, ownedCloseCalls.get(), "Unknown aggregate must not be closed again")
            } finally {
                if (!injectOwnedCloseError.get()) skipFixtureResourceClose = true
                countOwnedCloses.set(false)
                injectOwnedCloseError.set(false)
                // Artificial test-only retirement after assertions; Unknown is not clean settlement.
                if (!fixture.manager.isShutdown) {
                    fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                    fixture.manager.shutdown()
                }
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `timedOutCloseDoesNotCancelAcceptedManagerRelease`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val running = fixture.manager.submit {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try {
                val failure = assertFailsWith<IllegalStateException> { ready.value.close() }
                assertIs<java.util.concurrent.TimeoutException>(failure.cause)
                assertEquals(0, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                release.countDown()
                running.get(5, TimeUnit.SECONDS)
            }
            ready.value.close()
            assertEquals(2, recording.retireCalls.get())
            assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))
        }

    @Test
    fun `drainWaitsForConstructionQuiescenceWithinDeadline`() =
        withRuntime { fixture ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recording = RecordingCapture()
            val capture = CompletableFuture<Any>()
            val caller = Thread.ofPlatform().start {
                try {
                    capture.complete(
                        captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    },
                    )
                } catch (failure: Throwable) {
                    capture.completeExceptionally(failure)
                }
            }
            val drain = CompletableFuture<DrainReport>()
            var drainer: Thread? = null
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                drainer = Thread.ofPlatform().start { drain.complete(fixture.runtime.drain(Duration.ofSeconds(4))) }
                assertFailsWith<java.util.concurrent.TimeoutException> { drain.get(100, TimeUnit.MILLISECONDS) }
                assertEquals(0, recording.retireCalls.get())
                release.countDown()
                assertIs<LiveSetup.Refused>(capture.get(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Complete>(drain.get(5, TimeUnit.SECONDS))
                assertSame(recording.reserved.get(), recording.retiredOwner.get())
            } finally {
                release.countDown()
                caller.join(6000)
                drainer?.join(6000)
                assertFalse(caller.isAlive)
                assertFalse(drainer?.isAlive == true)
            }
        }

    @Test
    fun `late fatal wait history survives in flight pending release snapshot`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingInvalidate.set(1)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val claimFailure = IllegalStateException("post-store claim failure before release")
            val waitFailure = LinkageError("late wait failure during native release")
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(
                            afterTypedStore = { throw claimFailure },
                            beforeWait = {
                                check(entered.await(5, TimeUnit.SECONDS))
                                throw waitFailure
                            },
                        ),
                    )
                }
                assertSame(waitFailure, thrown.targetException)
                val inFlight = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(waitFailure, inFlight.retained.failure)
                assertTrue(waitFailure.suppressed.any { it === claimFailure })
                assertEquals(0, recording.retireCalls.get())
                release.countDown()
                fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                val retained = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(waitFailure, retained.retained.failure)
                assertSame(recording.pendingInvalidateOutcome, retained.retained.nativeOutcome)
                assertEquals(1, recording.retireCalls.get())
                assertSame(waitFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertSame(waitFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(3, recording.retireCalls.get())
            } finally {
                release.countDown()
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `late fatal wait history survives in flight complete release snapshot`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
            )
            val association = runtimeState(fixture.runtime).javaClass.getDeclaredField("scopingPreparation").let {
                it.isAccessible = true
                it.get(runtimeState(fixture.runtime)) as io.mazewall.enforcer.internal.execution.ScopingPreparation
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val claimFailure = IllegalStateException("post-store claim failure before release")
            val waitFailure = LinkageError("late wait failure during native release")
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            try {
                val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    scopingMethod().invoke(
                        fixture.runtime,
                        (fixture.reviewed.document.policy as CanonicalPolicyArtifact.Worker).policy,
                        DomainLimits(1, 1),
                        ready.value,
                        scopingEffects(
                            afterTypedStore = { throw claimFailure },
                            beforeWait = {
                                check(entered.await(5, TimeUnit.SECONDS))
                                throw waitFailure
                            },
                        ),
                    )
                }
                assertSame(waitFailure, thrown.targetException)
                val inFlight = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.Retiring>(association.state)
                assertSame(waitFailure, inFlight.retained.failure)
                assertTrue(waitFailure.suppressed.any { it === claimFailure })
                assertEquals(0, recording.retireCalls.get())
                release.countDown()
                fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                val retained = assertIs<io.mazewall.enforcer.internal.execution.ScopingPreparation.State.PreparedSettled>(association.state)
                assertSame(waitFailure, retained.failure)
                assertEquals(2, recording.retireCalls.get())
                assertSame(waitFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertSame(waitFailure, assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout)).cleanupFailure)
                assertEquals(2, recording.retireCalls.get())
            } finally {
                release.countDown()
                runCatching { ready.value.close() }
            }
        }

    @Test
    fun `thrownNativeCleanupRemainsUnknownWithoutRetry`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val original = IllegalStateException("controlled unknown native result")
            recording.invalidateFailure.set(original)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            try {
                assertSame(original, assertFailsWith<IllegalStateException> { ready.value.close() }.cause)
                assertEquals(1, recording.retireCalls.get())
                assertSame(original, assertFailsWith<IllegalStateException> { ready.value.close() }.cause)
                assertEquals(1, recording.retireCalls.get(), "Unknown native effect must not be retried")
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(original, report.cleanupFailure)
                assertFalse(fixture.manager.isShutdown)
                assertEquals(1, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                // Test-only shutdown after the synthetic effect is quiescent; no clean settlement is claimed.
                if (!fixture.manager.isShutdown) {
                    fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                    fixture.manager.shutdown()
                }
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `callbacksAndNativeReleaseRunOutsideRuntimeGate`() =
        withRuntime { fixture ->
            val lock = ManagedRuntime::class.java.getDeclaredField("lock").let {
                it.isAccessible = true
                it.get(fixture.runtime)
            }
            val recording = RecordingCapture(completeCapture = true)
            recording.beforeInvalidate = { assertFalse(Thread.holdsLock(lock)) }
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                captureSynthetic(
                    fixture.runtime,
                    fixture.reviewed,
                    fixture.resources,
                    recording,
                    { assertFalse(Thread.holdsLock(lock)) },
                    { assertFalse(Thread.holdsLock(lock)) },
                ),
            )
            ready.value.close()
            assertEquals(2, recording.retireCalls.get())
        }

    @Test
    fun `concurrentDrainsShareConstructionCompletion`() =
        withRuntime { fixture ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recording = RecordingCapture()
            val capture = CompletableFuture<Any>()
            val caller = Thread.ofPlatform().start {
                try {
                    capture.complete(
                        captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {
                        arrived.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    },
                    )
                } catch (failure: Throwable) {
                    capture.completeExceptionally(failure)
                }
            }
            val first = CompletableFuture<DrainReport>()
            val second = CompletableFuture<DrainReport>()
            var one: Thread? = null
            var two: Thread? = null
            try {
                assertTrue(arrived.await(5, TimeUnit.SECONDS))

                fun startDrain(result: CompletableFuture<DrainReport>): Thread =
                    Thread.ofPlatform().start {
                    try {
                        result.complete(fixture.runtime.drain(Duration.ofSeconds(4)))
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }
                val control = ManagedRuntime::class.java.getDeclaredField("legacyControl").let {
                    it.isAccessible = true
                    it.get(fixture.runtime)
                }
                val lock = ManagedRuntime::class.java.getDeclaredField("lock").let {
                    it.isAccessible = true
                    it.get(fixture.runtime)
                }
                val participants = control.javaClass.getDeclaredField("participants").apply { isAccessible = true }

                one = startDrain(first)
                two = startDrain(second)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (synchronized(lock) { participants.getInt(control) } < 2 && System.nanoTime() < until && !first.isDone && !second.isDone) {
                    Thread.yield()
                }
                assertFalse(first.isDone || second.isDone, "A drain exited before both callers joined")
                assertEquals(
                    2,
                    synchronized(lock) { participants.getInt(control) },
                    "Both original callers must join before construction is released",
                )
                assertFailsWith<java.util.concurrent.TimeoutException> { first.get(100, TimeUnit.MILLISECONDS) }
                assertFailsWith<java.util.concurrent.TimeoutException> { second.get(100, TimeUnit.MILLISECONDS) }
                release.countDown()
                assertIs<LiveSetup.Refused>(capture.get(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Complete>(first.get(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Complete>(second.get(5, TimeUnit.SECONDS))
                assertEquals(2, recording.retireCalls.get())
            } finally {
                release.countDown()
                caller.join(6000)
                one?.join(6000)
                two?.join(6000)
                assertFalse(caller.isAlive || one?.isAlive == true || two?.isAlive == true)
            }
        }

    private fun withClosedDrainBoundary(body: (ManagedRuntime, Any, Any, Any, Any, RecordingCapture, RuntimeFixture) -> Unit) =
        withRuntime { fixture ->
        val recording = RecordingCapture(completeCapture = true)
        val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
            captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {},
        )
        val shared = runtimeState(fixture.runtime)
        val preparation = shared.javaClass.getDeclaredField("scopingPreparation").let {
            it.isAccessible = true
            it.get(shared)
        }
        ready.value.close()
        assertIs<DrainReport.Complete>(fixture.runtime.drain(timeout))

        fun field(name: String): Any =
            ManagedRuntime::class.java.getDeclaredField(name).let {
                it.isAccessible = true
                it.get(fixture.runtime)
            }
        body(
            fixture.runtime,
            field("operations"),
            field("operationsPermit"),
            field("legacyControl"),
            preparation,
            recording,
            fixture,
        )
    }

    private fun drainBoundaryMethod(
        operations: Any,
        name: String,
    ): java.lang.reflect.Method =
        operations.javaClass.declaredMethods
            .single { it.name == name }
            .apply { isAccessible = true }

    @Test
    fun `closed original scoping release boundary observes without effects`() =
        withClosedDrainBoundary { runtime, operations, permit, control, preparation, recording, fixture ->
            val before = recording.retireCalls.get()
            val reaps = fixture.relay.reaps.get()
            val tasks = fixture.manager.taskCount
            drainBoundaryMethod(operations, "awaitScopingDrainRelease").invoke(
                operations,
                permit,
                control,
                preparation,
                Deadline(timeout),
            )
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
            assertEquals(before, recording.retireCalls.get())
            assertEquals(reaps, fixture.relay.reaps.get())
            assertEquals(tasks, fixture.manager.taskCount)
        }

    @Test
    fun `closed original confined sweep boundary observes without effects`() =
        withClosedDrainBoundary { runtime, operations, permit, control, _, recording, fixture ->
            val before = recording.retireCalls.get()
            val reaps = fixture.relay.reaps.get()
            val tasks = fixture.manager.taskCount
            drainBoundaryMethod(operations, "awaitConfinedSweep").invoke(
                operations,
                permit,
                control,
                Deadline(timeout),
            )
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
            assertEquals(before, recording.retireCalls.get())
            assertEquals(reaps, fixture.relay.reaps.get())
            assertEquals(tasks, fixture.manager.taskCount)
        }

    @Test
    fun `closed original helper sweep boundary observes without effects`() =
        withClosedDrainBoundary { runtime, _, _, control, _, recording, fixture ->
            val before = recording.retireCalls.get()
            val reaps = fixture.relay.reaps.get()
            val tasks = fixture.manager.taskCount
            ManagedRuntime::class.java.declaredMethods
                .single { it.name == "awaitHelperSweep" }
                .apply { isAccessible = true }
                .invoke(runtime, control, Deadline(timeout))
            assertIs<DrainReport.Complete>(runtime.drain(timeout))
            assertEquals(before, recording.retireCalls.get())
            assertEquals(reaps, fixture.relay.reaps.get())
            assertEquals(tasks, fixture.manager.taskCount)
        }

    @Test
    fun `closed drain boundaries reject real foreign ownership`() =
        withClosedDrainBoundary { runtime, operations, permit, control, preparation, recording, fixture ->
            val originalDirectory = directory
            directory = Files.createTempDirectory(originalDirectory, "foreign-drain-fixture-")
            try {
                withRuntime { foreignFixture ->
                    val foreignRecording = RecordingCapture(completeCapture = true)
                    val foreignReady = assertIs<LiveSetup.Ready<PreparedStackScoping>>(
                        captureSynthetic(foreignFixture.runtime, foreignFixture.reviewed, foreignFixture.resources, foreignRecording) {},
                    )
                    try {
                        val foreignState = runtimeState(foreignFixture.runtime)
                        val foreignPreparation = foreignState.javaClass.getDeclaredField("scopingPreparation").let {
                            it.isAccessible = true
                            it.get(foreignState)
                        }
                        val foreignControl = ManagedRuntime::class.java.getDeclaredField("legacyControl").let {
                            it.isAccessible = true
                            it.get(foreignFixture.runtime)
                        }
                        val deadline = Deadline(timeout)
                        val release = drainBoundaryMethod(operations, "awaitScopingDrainRelease")
                        val sweep = drainBoundaryMethod(operations, "awaitConfinedSweep")
                        val before = recording.retireCalls.get()
                        val reaps = fixture.relay.reaps.get()
                        val tasks = fixture.manager.taskCount
                        for (call in listOf<() -> Unit>(
                            { sweep.invoke(operations, Any(), control, deadline) },
                            { sweep.invoke(operations, permit, foreignControl, deadline) },
                            { release.invoke(operations, permit, foreignControl, preparation, deadline) },
                            { release.invoke(operations, permit, control, foreignPreparation, deadline) },
                            { release.invoke(operations, Any(), control, preparation, deadline) },
                        )) {
                            assertIs<IllegalStateException>(assertFailsWith<java.lang.reflect.InvocationTargetException> { call() }.targetException)
                        }
                        val helper = ManagedRuntime::class.java.declaredMethods
                            .single { it.name == "awaitHelperSweep" }
                            .apply { isAccessible = true }
                        assertIs<IllegalStateException>(
                            assertFailsWith<java.lang.reflect.InvocationTargetException> { helper.invoke(runtime, foreignControl, deadline) }.targetException,
                        )
                        assertIs<DrainReport.Complete>(runtime.drain(timeout))
                        assertEquals(before, recording.retireCalls.get())
                        assertEquals(reaps, fixture.relay.reaps.get())
                        assertEquals(tasks, fixture.manager.taskCount)
                    } finally {
                        foreignReady.value.close()
                    }
                }
            } finally {
                directory = originalDirectory
            }
        }

    @Test
    fun `closed drain preserves retained unknown failure`() =
        withClosedDrainBoundary { runtime, _, _, _, _, recording, fixture ->
            val state = runtimeState(runtime)
            val lock = ManagedRuntime::class.java.getDeclaredField("lock").let {
                it.isAccessible = true
                it.get(runtime)
            }
            val unknown = state.javaClass.getDeclaredField("checkedUnknown").apply { isAccessible = true }
            val failureField = state.javaClass.getDeclaredField("checkedRawFailure").apply { isAccessible = true }
            val failure = IllegalStateException("retained unknown observation")
            val before = recording.retireCalls.get()
            val tasks = fixture.manager.taskCount
            try {
                synchronized(lock) {
                    unknown.setBoolean(state, true)
                    failureField.set(state, failure)
                }
                assertSame(failure, assertIs<DrainReport.Incomplete>(runtime.drain(timeout)).cleanupFailure)
                assertEquals(before, recording.retireCalls.get())
                assertEquals(tasks, fixture.manager.taskCount)
            } finally {
                synchronized(lock) {
                    unknown.setBoolean(state, false)
                    failureField.set(state, null)
                }
            }
        }

    @Test
    fun `singleRouteRejectsOppositeHandlerWithCompleteBindingMetadata`() =
        withRuntime { fixture ->
            val policy = object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler> = mapOf(Syscall.OPENAT to { _, _, _ -> true })
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources, scopingPolicy = policy)
            }
            assertEquals("Handlers must be limited to reviewed routes", failure.message)
            fixture.resources.validate()
        }

    @Test
    fun `foreignEnrollmentMembershipRetainsOriginalOwner`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true, wrongMembership = true)
            assertFailsWith<IllegalArgumentException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {}
            }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            fixture.resources.validate()
        }

    @Test
    fun `foreignSealedInstallationRetainsOriginalOwner`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true, wrongSealed = true)
            assertFailsWith<IllegalArgumentException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {}
            }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            fixture.resources.validate()
        }

    @Test
    fun `settledCloseRethrowsOriginalConstructionHistory`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            recording.pendingRetire.set(1)
            val original = OutOfMemoryError("controlled pre-publication error")
            val wrapper = java.util.concurrent.atomic
                .AtomicReference<PreparedStackScoping>()
            assertSame(
                original,
                assertFailsWith<OutOfMemoryError> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, {
                    wrapper.set(assertIs<LiveSetup.Ready<PreparedStackScoping>>(it).value)
                    throw original
                }, {})
            },
            )
            assertSame(original, assertFailsWith<IllegalStateException> { wrapper.get().close() }.cause)
            assertSame(original, assertFailsWith<OutOfMemoryError> { wrapper.get().close() })
            assertEquals(3, recording.retireCalls.get())
            fixture.resources.validate()
        }

    @Test
    fun `interruptedClosePreservesPriorHistoryAndInterruptObservation`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val original = IllegalStateException("controlled pre-publication history")
            val wrapper = java.util.concurrent.atomic
                .AtomicReference<PreparedStackScoping>()
            assertSame(
                original,
                assertFailsWith<IllegalStateException> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, {
                    wrapper.set(assertIs<LiveSetup.Ready<PreparedStackScoping>>(it).value)
                    throw original
                }, {})
            },
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            recording.beforeInvalidate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val first = CompletableFuture<Throwable>()
            val firstCaller = Thread.ofPlatform().start {
                try {
                    wrapper.get().close()
                    first.complete(AssertionError("close unexpectedly succeeded"))
                } catch (failure: Throwable) {
                    first.complete(failure)
                }
            }
            val interrupted = CompletableFuture<Pair<Throwable, Boolean>>()
            var secondCaller: Thread? = null
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                secondCaller = Thread.ofPlatform().start {
                    Thread.currentThread().interrupt()
                    try {
                        wrapper.get().close()
                        interrupted.complete(AssertionError("close unexpectedly succeeded") to false)
                    } catch (
                        failure: Throwable,
                    ) {
                        interrupted.complete(failure to Thread.currentThread().isInterrupted)
                    }
                }
                val (failure, flag) = interrupted.get(5, TimeUnit.SECONDS)
                assertTrue(flag)
                assertSame(original, failure.cause)
                assertTrue(original.suppressed.any { it is InterruptedException })
            } finally {
                release.countDown()
                firstCaller.join(6000)
                secondCaller?.join(6000)
                assertFalse(firstCaller.isAlive || secondCaller?.isAlive == true)
            }
            assertSame(original, first.get(5, TimeUnit.SECONDS))
            fixture.resources.validate()
        }

    @Test
    fun `fatalAggregateClosePropagatesAndRemainsUnknownWithoutRepeat`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val ready = assertIs<LiveSetup.Ready<PreparedStackScoping>>(captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {})
            val original = OutOfMemoryError("controlled real owned-close fatal")
            countOwnedCloses.set(true)
            fatalOwnedClose.set(original)
            try {
                assertSame(original, assertFailsWith<OutOfMemoryError> { ready.value.close() })
                assertTrue(fatalOwnedCloseObserved.get())
                skipFixtureResourceClose = true
                val attempts = ownedCloseCalls.get()
                val report = assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertSame(original, report.cleanupFailure)
                assertFalse(fixture.manager.isShutdown)
                assertSame(original, assertFailsWith<IllegalStateException> { ready.value.close() }.cause)
                assertEquals(attempts, ownedCloseCalls.get())
            } finally {
                if (fatalOwnedCloseObserved.get()) skipFixtureResourceClose = true
                fatalOwnedClose.set(null)
                countOwnedCloses.set(false)
                if (!fixture.manager.isShutdown) {
                    fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                    fixture.manager.shutdown()
                }
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `laterFatalNativeFailurePreservesEarlierFatalHistory`() =
        withRuntime { fixture ->
            val recording = RecordingCapture(completeCapture = true)
            val original = OutOfMemoryError("earlier construction fatal")
            val later = LinkageError("later native fatal")
            val wrapper = java.util.concurrent.atomic
                .AtomicReference<PreparedStackScoping>()
            assertSame(
                original,
                assertFailsWith<OutOfMemoryError> {
                captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording, {
                    wrapper.set(assertIs<LiveSetup.Ready<PreparedStackScoping>>(it).value)
                    throw original
                }, {})
            },
            )
            recording.invalidateFailure.set(later)
            try {
                assertSame(original, assertFailsWith<OutOfMemoryError> { wrapper.get().close() })
                assertTrue(original.suppressed.any { it === later })
                assertEquals(1, recording.retireCalls.get())
                assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
                assertEquals(1, recording.retireCalls.get())
                fixture.resources.validate()
            } finally {
                if (!fixture.manager.isShutdown) {
                    fixture.manager.submit { }.get(5, TimeUnit.SECONDS)
                    fixture.manager.shutdown()
                }
                assertTrue(fixture.manager.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    private fun assertRejectedProvenance(recording: RecordingCapture) =
        withRuntime { fixture ->
            assertFailsWith<IllegalArgumentException> { captureSynthetic(fixture.runtime, fixture.reviewed, fixture.resources, recording) {} }
            assertIs<DrainReport.Incomplete>(fixture.runtime.drain(timeout))
            assertSame(recording.reserved.get(), recording.retiredOwner.get())
            fixture.resources.validate()
        }

    @Test
    fun `extraEnrollmentClassIsRejected`() = assertRejectedProvenance(RecordingCapture(completeCapture = true, extraClass = true))

    @Test
    fun `missingEnrollmentMethodIsRejected`() = assertRejectedProvenance(RecordingCapture(completeCapture = true, missingMethod = true))

    @Test
    fun `foreignSealedProcessIsRejected`() = assertRejectedProvenance(RecordingCapture(completeCapture = true, wrongSealedProcess = true))

    @Test
    fun `foreignSealedCapacityIsRejected`() = assertRejectedProvenance(RecordingCapture(completeCapture = true, wrongSealedCapacity = true))

    @Test
    fun `secondAndSpentAdmissionRejectWithoutEffects`() =
        withRuntime { fixture ->
            assertIs<LiveSetup.Refused>(captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources))
            assertFailsWith<java.util.concurrent.RejectedExecutionException> {
                captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources)
            }
        }

    @Test
    fun `actualProducerNonRealRefusesBeforeJniPathAndReserve`() =
        withRuntime { fixture ->
            val result = captureWithMissingAgent(fixture.runtime, fixture.reviewed, fixture.resources)
            val failure = assertIs<LiveSetup.Refused>(result).failure
            assertEquals(LiveFailureStatus.Unsupported, failure.status)
            assertEquals(LiveFailureReason.RuntimeMismatch, failure.reason)
        }

    @Test
    fun `terminal accepted socket close failure permits later runtime retirement without native reclose`() =
        withRuntime { f ->
        val terminal = NativeCloseFailure("controlled terminal accepted close")
        val closes = AtomicInteger()
        f.endpoint.afterSocketClose = { fd ->
            if (fd === f.endpoint.firstAccepted.getNow(null)) {
                closes.incrementAndGet()
                throw terminal
            }
        }
        f.startPreparation()
        val cap = ConfinedDeadline.mint(f.relay.native) { 5_000_000_000 }
        val helper = f.relay.helper
        val setup = assertIs<Frame.Setup>(helper.receiveFrame(cap).frame)
        helper.sendFrame(Frame.Control(ConfinedHelperProtocol.ControlTag.Ready, setup.binding, setup.deadline))
        assertFailsWith<ExecutionException> { f.prepared.get(3, TimeUnit.SECONDS) }
        val disposed = assertIs<SeccompCleanupOutcome.Incomplete>(f.endpoint.firstCleanup.get(2, TimeUnit.SECONDS))
        assertEquals(emptyList(), disposed.retryableOwnership)
        assertSame(terminal, disposed.failures.single())
        val first = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        val failure = assertNotNull(first.cleanupFailure)
        assertTrue(f.managerThread.isAlive)
        f.resources.validate()
        val second = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        assertSame(failure, second.cleanupFailure)
        assertEquals(0, second.liveWorkers)
        assertFalse(f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        assertEquals(1, closes.get())
        assertEquals(second, f.runtime.drain(timeout))
        assertEquals(1, closes.get())
        assertEquals(1, installs.get())
    }

    @Test
    fun `actual drain retains failed helper retirement then retries on the same manager and retains original failure`() =
        withRuntime { f ->
        f.startPreparation()
        val domain = f.completeSetup()
        val worker = f.runtime
            .submit(domain, Callable { Thread.currentThread() })
            .result()
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS)
        val initial = Thread.getAllStackTraces().keys.single { it.name.endsWith("resource-retirement") && it.isAlive }
        f.relay.failReap.set(true)
        val first = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        val firstFailure = assertNotNull(first.cleanupFailure)
        assertFalse(worker.isAlive)
        assertFalse(initial.isAlive)
        assertTrue(f.managerThread.isAlive)
        f.resources.validate()
        val grants = f.resources.resources.grants
        assertTrue(grants.all { it.path.descriptor.isLiveForIo() })
        f.relay.failReap.set(false)
        val second = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        assertSame(firstFailure, second.cleanupFailure)
        assertEquals(0, second.liveWorkers)
        assertFalse(f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        val reaps = f.relay.reaps.get()
        assertEquals(second, f.runtime.drain(timeout))
        assertEquals(reaps, f.relay.reaps.get())
        assertFalse(worker.isAlive || initial.isAlive || f.managerThread.isAlive)
    }

    @Test
    fun `interrupted drain retains fatal retry sweep failure and original retirement failure`() =
        withRuntime { f ->
        f.startPreparation()
        f.completeSetup()
        f.relay.failReap.set(true)
        val first = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        val original = assertNotNull(first.cleanupFailure)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fatal = LinkageError("controlled retry clock failure")
        beforeNative = { nr ->
            if (Thread.currentThread() === f.managerThread && nr == Arch.current().clock_gettime.toLong()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw fatal
            }
        }
        val result = CompletableFuture<Pair<DrainReport, Boolean>>()
        val caller = Thread.ofPlatform().start {
            result.complete(f.runtime.drain(timeout) to Thread.currentThread().isInterrupted)
        }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(2000)
            assertFalse(caller.isAlive)
            val interrupted = result.get(1, TimeUnit.SECONDS)
            assertIs<DrainReport.Incomplete>(interrupted.first)
            assertTrue(interrupted.second)
        } finally {
            release.countDown()
            caller.join(6000)
            beforeNative = {}
        }
        f.manager.submit {}.get(2, TimeUnit.SECONDS)
        f.relay.failReap.set(false)
        val later = assertIs<DrainReport.Incomplete>(f.runtime.drain(timeout))
        assertEquals(0, later.liveWorkers)
        assertFalse(f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        assertSame(fatal, later.cleanupFailure)
        assertTrue(fatal.suppressed.any { it === original })
        assertEquals(later, f.runtime.drain(timeout))
    }

    @Test
    fun `cancelled queued helper preparation cannot strand the retained manager admission`() =
        withRuntime { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = f.manager.submit {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        lateinit var quiescence: CountDownLatch
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            f.startPreparation()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (f.manager.queue.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, f.manager.queue.size)
            assertEquals(0, f.manager.queue.remainingCapacity())
            val queued = f.manager.queue.single()
            quiescence = f.queuedPreparationQuiescence(queued)
            assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(30)))
            assertTrue(f.managerThread.isAlive)
            assertFalse(f.manager.isShutdown, "Queue-full drain must retain the original manager")
            requireNotNull(f.preparing).interrupt()
            assertIs<InterruptedException>(assertFailsWith<ExecutionException> { f.prepared.get(2, TimeUnit.SECONDS) }.cause)
        } finally {
            release.countDown()
        }
        blocked.get(2, TimeUnit.SECONDS)
        assertTrue(quiescence.await(2, TimeUnit.SECONDS))
        f.manager.submit {}.get(2, TimeUnit.SECONDS)
        assertEquals(DrainReport.Complete, f.runtime.drain(timeout))
        assertFalse(f.managerThread.isAlive)
        f.resources.validate()
        assertEquals(1, installs.get())
    }

    @Test
    fun `healthy helper first drain closes originals and joins owners before Complete`() =
        withRuntime { f ->
        f.startPreparation()
        val domain = f.completeSetup()
        val worker = f.runtime
            .submit(domain, Callable { Thread.currentThread() })
            .result()
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS)
        val submitted = f.manager.taskCount
        assertEquals(DrainReport.Complete, f.runtime.drain(timeout))
        assertFalse(worker.isAlive || f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        assertEquals(1, f.relay.reaps.get())
        assertEquals(submitted + 1, f.manager.taskCount)
        assertEquals(DrainReport.Complete, f.runtime.drain(timeout))
        assertEquals(submitted + 1, f.manager.taskCount)
    }

    @Test
    fun `drain before domain publication waits for claimed preparation rollback on the original manager`() =
        withRuntime { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeNative = { nr ->
            if (Thread.currentThread() === f.managerThread && nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            f.startPreparation()
            check(entered.await(2, TimeUnit.SECONDS))
            assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(30)))
            assertTrue(f.managerThread.isAlive)
            assertFalse(f.manager.isShutdown)
            f.resources.validate()
        } finally {
            release.countDown()
        }
        assertIs<java.util.concurrent.RejectedExecutionException>(assertFailsWith<ExecutionException> { f.prepared.get(2, TimeUnit.SECONDS) }.cause)
        assertEquals(DrainReport.Complete, f.runtime.drain(timeout))
        assertFalse(f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        assertEquals(1, installs.get(), "Unpublished rejected preparation must not install a worker")
    }

    @Test
    fun `timeout and interrupted callers reuse the same in flight drain without cancelling its owner`() =
        withRuntime { f ->
        f.startPreparation()
        f.completeSetup()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.relay.beforeReap = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val result = CompletableFuture<DrainReport>()
        val draining = Thread.ofPlatform().start { result.complete(f.runtime.drain(timeout)) }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            val submitted = f.manager.taskCount
            assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(30)))
            assertEquals(submitted, f.manager.taskCount)
            val interrupted = CompletableFuture<Pair<DrainReport, Boolean>>()
            val caller = Thread.ofPlatform().start {
                Thread.currentThread().interrupt()
                interrupted.complete(f.runtime.drain(timeout) to Thread.currentThread().isInterrupted)
            }
            caller.join(2000)
            assertFalse(caller.isAlive)
            val observed = interrupted.get(1, TimeUnit.SECONDS)
            assertIs<DrainReport.Incomplete>(observed.first)
            assertTrue(observed.second)
            assertEquals(submitted, f.manager.taskCount)
            assertFalse(f.manager.isShutdown)
            f.resources.validate()
        } finally {
            release.countDown()
            draining.join(6000)
            assertFalse(draining.isAlive)
        }
        assertEquals(DrainReport.Complete, result.get(1, TimeUnit.SECONDS))
        assertFalse(f.managerThread.isAlive)
        assertEquals(1, f.relay.reaps.get())
    }

    @Test
    fun `drain during shutdown waits for queued manager work and never resubmits or recloses`() =
        withRuntime { f ->
        f.startPreparation()
        f.completeSetup()
        val reapEntered = CountDownLatch(1)
        val allowReap = CountDownLatch(1)
        val queuedEntered = CountDownLatch(1)
        val allowTermination = CountDownLatch(1)
        f.relay.beforeReap = {
            reapEntered.countDown()
            check(allowReap.await(5, TimeUnit.SECONDS))
        }
        val result = CompletableFuture<DrainReport>()
        val draining = Thread.ofPlatform().start { result.complete(f.runtime.drain(timeout)) }
        try {
            check(reapEntered.await(2, TimeUnit.SECONDS))
            f.manager.submit {
                queuedEntered.countDown()
                check(allowTermination.await(5, TimeUnit.SECONDS))
            }
            allowReap.countDown()
            check(queuedEntered.await(2, TimeUnit.SECONDS))
            assertTrue(f.manager.isShutdown)
            assertFalse(f.manager.isTerminated)
            val submitted = f.manager.taskCount
            val report = assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(30)))
            assertEquals(1, report.liveWorkers)
            assertEquals(submitted, f.manager.taskCount)
            assertEquals(1, f.relay.reaps.get())
            assertFails { f.resources.validate() }
        } finally {
            allowReap.countDown()
            allowTermination.countDown()
            draining.join(6000)
            assertFalse(draining.isAlive)
        }
        assertEquals(DrainReport.Complete, result.get(1, TimeUnit.SECONDS))
        assertFalse(f.managerThread.isAlive)
        assertEquals(DrainReport.Complete, f.runtime.drain(timeout))
    }

    @Test
    fun `helper admission rejects concurrent and mixed preparation before acquiring another generation`() =
        withRuntime { f ->
        f.startPreparation()
        val cap = ConfinedDeadline.mint(f.relay.native) { 5_000_000_000 }
        val helper = f.relay.helper
        assertIs<Frame.Setup>(helper.receiveFrame(cap).frame)
        val tasks = f.manager.taskCount
        assertFailsWith<IllegalStateException> { f.prepare() }
        assertFailsWith<IllegalStateException> { f.runtime.prepare(Policy.NO_EXEC_HOTSPOT, DomainLimits(1, 1)) }
        assertEquals(tasks, f.manager.taskCount)
        assertEquals(1, installs.get())
        requireNotNull(f.preparing).interrupt()
        assertIs<InterruptedException>(assertFailsWith<ExecutionException> { f.prepared.get(2, TimeUnit.SECONDS) }.cause)
        f.runtime.drain(timeout)
        f.runtime.drain(timeout)
        assertFalse(f.managerThread.isAlive)
        assertFails { f.resources.validate() }
        assertEquals(1, installs.get())
    }
}
