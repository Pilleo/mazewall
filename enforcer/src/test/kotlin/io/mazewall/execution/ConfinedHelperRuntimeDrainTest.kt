package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.ffi.internal.RecordingCapture
import io.mazewall.ffi.internal.assertActualCaptureFreezesInputs
import io.mazewall.ffi.internal.captureSynthetic
import io.mazewall.ffi.internal.captureWithMissingAgent
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperControlTest
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.LiveFailureReason
import io.mazewall.platform.native.LiveFailureStatus
import io.mazewall.platform.native.LiveSetup
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeProcess
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.platform.socket.NativeCloseFailure
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by real.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (countOwnedCloses.get()) ownedCloseCalls.incrementAndGet()
                    val target = targetRuleset.get() === fd
                    if (target) rulesetCloseCalls.incrementAndGet()
                    val actual = real.fileSystem.close(fd)
                    if (target) {
                        check(actual is LinuxNative.SyscallResult.Success) { "Target ruleset close did not succeed before injection" }
                        rulesetCloseFailure.getAndSet(null)?.let { throw it }
                    }
                    fatalOwnedClose.getAndSet(null)?.let { fatal ->
                        check(actual is LinuxNative.SyscallResult.Success)
                        fatalOwnedCloseObserved.set(true)
                        throw fatal
                    }
                    if (injectOwnedCloseError.compareAndSet(true, false)) {
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
        body: (RuntimeFixture) -> Unit,
    ) {
        val jar = implementationJar()
        ConfinedHelperControlTest.RelayFixture(directory, observations(parent = false)).use { f ->
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
                        val runtime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, preparationTimeout, timeout)
                        val fixture = RuntimeFixture(f, endpoint, resources, reviewed, support, runtime)
                        try {
                            body(fixture)
                        } finally {
                            f.failReap.set(false)
                            fixture.preparing?.let { thread ->
                                thread.interrupt()
                                thread.join(6000)
                                check(!thread.isAlive)
                            }
                            runtime.drain(timeout)
                            runtime.drain(timeout)
                        }
                        } finally {
                            if (!skipFixtureResourceClose) resources.close()
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
    ) {
        val prepared = CompletableFuture<InProcessDomain>()
        var preparing: Thread? = null

        // Scheduling barriers use the real bounded executor without adding production test hooks.
        val manager = ManagedRuntime::class.java.getDeclaredField("manager").let { field ->
            field.isAccessible = true
            field.get(runtime) as ThreadPoolExecutor
        }
        val managerThread = manager.submit(Callable { Thread.currentThread() }).get(2, TimeUnit.SECONDS)

        fun prepare() = runtime.prepareConfinedRead(reviewed, DomainLimits(1, 1), resources, endpoint.context, support, HelperSupportLifetime.NoWriters, observations(parent = true))

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
                one = Thread.ofPlatform().start { first.complete(fixture.runtime.drain(Duration.ofSeconds(4))) }
                two = Thread.ofPlatform().start { second.complete(fixture.runtime.drain(Duration.ofSeconds(4))) }
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
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            f.startPreparation()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (f.manager.queue.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, f.manager.queue.size)
            assertIs<DrainReport.Incomplete>(f.runtime.drain(Duration.ofMillis(30)))
            assertTrue(f.managerThread.isAlive)
            assertFalse(f.manager.isShutdown, "Queue-full drain must retain the original manager")
            requireNotNull(f.preparing).interrupt()
            assertIs<InterruptedException>(assertFailsWith<ExecutionException> { f.prepared.get(2, TimeUnit.SECONDS) }.cause)
        } finally {
            release.countDown()
        }
        blocked.get(2, TimeUnit.SECONDS)
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
