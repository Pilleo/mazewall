package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
import io.mazewall.enforcer.internal.supervisor.SupervisorPrewarmBudget
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.PrctlCommand
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedRuntimeTest {
    @Test
    fun `canonical acquisition without helper support leaves helper owner absent`() {
        val owner = ManagedRuntime.newCanonicalAcquisition()
        try {
            owner.start(timeout, timeout)
            val field = owner.javaClass
                .getDeclaredField("helperManager")
                .apply { isAccessible = true }
            assertNull(field.get(owner))
        } finally {
            owner.close()
        }
    }

    @Test
    fun `failed owned helper startup remains unknown without replay`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        val cleanupCalls = AtomicInteger()
        val launcher = object : MockProcessLauncher() {
            override fun deleteIfExists(path: java.nio.file.Path): Boolean {
                cleanupCalls.incrementAndGet()
                return super.deleteIfExists(path)
            }
        }
        val daemon = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
        val owner = ManagedRuntime.newCanonicalAcquisition()
        val state = owner.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
        val starting = state.type.enumConstants.single { (it as Enum<*>).name == "STARTING" }
        state.set(owner, starting)
        owner.javaClass
            .getDeclaredField("startActor")
            .apply { isAccessible = true }
            .set(owner, Thread.currentThread())
        val start = owner.javaClass
            .getDeclaredMethod("startHelper", daemon.javaClass)
            .apply { isAccessible = true }
        try {
            System.setProperty(property, "15001")
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> { start.invoke(owner, daemon) }
            assertIs<IllegalArgumentException>(failure.cause)
            assertFalse(launcher.startProcessCalled)
            val rollbackCalls = cleanupCalls.get()
            assertTrue(rollbackCalls > 0)
            assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
            assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
            assertEquals(rollbackCalls, cleanupCalls.get())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @org.junit.jupiter.api.io.TempDir lateinit var directDirectory: java.nio.file.Path
    private val timeout = Duration.ofSeconds(5)
    private lateinit var engine: MockNativeEngine

    @BeforeEach
    fun prepareNative() {
        engine = MockNativeEngine().apply {
            process.onPrctl = { command -> LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L) }
        }
        LinuxNative.setEngine(engine)
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1L)

            override fun getLandlockCapability(): KernelCapability<Int> = KernelCapability.Available(8)

            override fun probeSeccompTsync(): Boolean = true

            override fun probeSeccompUserNotif(): Boolean = true

            override fun probeCetSupported(): Boolean = false
        })
    }

    @AfterEach
    fun resetNative() {
        LinuxNative.resetToDefault()
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        ContainmentStateRegistry.processState = ContainerState()
    }

    private fun open(): ManagedRuntime = ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, timeout, timeout)

    private fun policy(): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.CONNECT).build()

    @Test
    fun `direct Legacy claim retains real ruleset and pins until physical worker exits`() {
        try {
        LinuxNative.resetToDefault()
        val real = LinuxNative.snapshot()
        val capability = RealPlatformProvider.getLandlockCapability()
        org.junit.jupiter.api.Assumptions
            .assumeTrue(capability is KernelCapability.Available && capability.value >= 3)
        val closes = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val beforeClose = AtomicReference<(Any) -> Unit>()
        val afterClose = AtomicReference<(Any) -> Unit>()
        val foreignCalls = AtomicInteger()
        val activeRuntime = AtomicReference<ManagedRuntime>()
        val typedGap = java.util.concurrent.atomic
            .AtomicBoolean()
        val gapReached = java.util.concurrent.atomic
            .AtomicBoolean()
        val gapFailure = AtomicReference<Throwable?>()
        val nestedCreates = AtomicInteger()
        val swapOnCreate = java.util.concurrent.atomic
            .AtomicBoolean()
        val swapped = java.util.concurrent.atomic
            .AtomicBoolean()
        val foreign = object : io.mazewall.platform.native.NativeEngine by real {
            override val raw = object : io.mazewall.platform.native.RawSyscallOperations by real.raw {
                override fun syscall(
                    nr: Long,
                    a1: io.mazewall.platform.syscall.NativeArg,
                    a2: io.mazewall.platform.syscall.NativeArg,
                    a3: io.mazewall.platform.syscall.NativeArg,
                    a4: io.mazewall.platform.syscall.NativeArg,
                    a5: io.mazewall.platform.syscall.NativeArg,
                    a6: io.mazewall.platform.syscall.NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    if (nr == io.mazewall.platform.syscall.Arch
                        .current()
                        .seccompSyscallNumber
                        .toLong() ||
                        nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR
                    ) {
                            return LinuxNative.SyscallResult.Error(1, -1L)
                        }
                    return real.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                }

                override fun syscall4(
                    nr: Long,
                    a1: io.mazewall.platform.syscall.NativeArg,
                    a2: io.mazewall.platform.syscall.NativeArg,
                    a3: io.mazewall.platform.syscall.NativeArg,
                    a4: io.mazewall.platform.syscall.NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    if (nr == io.mazewall.platform.syscall.Arch
                        .current()
                        .seccompSyscallNumber
                        .toLong() ||
                        nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR
                    ) {
                            return LinuxNative.SyscallResult.Error(1, -1L)
                        }
                    return real.raw.syscall4(nr, a1, a2, a3, a4)
                }
            }
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by real.fileSystem {
                override fun open(
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    flags: io.mazewall.platform.lifecycle.OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.open(path, flags)
                }

                override fun open(
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    flags: io.mazewall.platform.lifecycle.OpenFlags,
                    mode: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.open(path, flags, mode)
                }

                override fun openat(
                    dirfd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    flags: io.mazewall.platform.lifecycle.OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.openat(dirfd, path, flags)
                }

                override fun openat(
                    dirfd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    flags: io.mazewall.platform.lifecycle.OpenFlags,
                    mode: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.openat(dirfd, path, flags, mode)
                }

                override fun openat2(
                    dirfd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    how: io.mazewall.ffi.memory.ManagedSegment,
                    size: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.openat2(dirfd, path, how, size)
                }

                override fun mmap(
                    addr: Long,
                    length: Long,
                    prot: io.mazewall.platform.lifecycle.MmapProt,
                    flags: io.mazewall.platform.lifecycle.MmapFlags,
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership>,
                    offset: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.mmap(addr, length, prot, flags, fd, offset)
                }

                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return real.fileSystem.close(fd)
                }
            }
            override val process = object : io.mazewall.platform.native.NativeProcess by real.process {
                override fun prctl(command: PrctlCommand): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    foreignCalls.incrementAndGet()
                    return LinuxNative.SyscallResult.Error(1, -1L)
                }
            }
        }
        val raw = object : io.mazewall.platform.native.RawSyscallOperations by real.raw {
            override fun syscall(
                nr: Long,
                a1: io.mazewall.platform.syscall.NativeArg,
                a2: io.mazewall.platform.syscall.NativeArg,
                a3: io.mazewall.platform.syscall.NativeArg,
                a4: io.mazewall.platform.syscall.NativeArg,
                a5: io.mazewall.platform.syscall.NativeArg,
                a6: io.mazewall.platform.syscall.NativeArg,
            ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR && typedGap.get()) {
                    nestedCreates.incrementAndGet()
                    return LinuxNative.SyscallResult.Error(1, -1L)
                }
                val result = if (nr == io.mazewall.platform.syscall.Arch
                    .current()
                    .seccompSyscallNumber
                    .toLong() ||
                    nr == io.mazewall.ffi.NativeConstants.LANDLOCK_RESTRICT_SELF_NR
                ) {
                        LinuxNative.SyscallResult.Success(0L)
                    } else {
                        real.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                    }
                if (nr == io.mazewall.ffi.NativeConstants.LANDLOCK_CREATE_RULESET_NR && swapOnCreate.compareAndSet(true, false)) {
                    typedGap.set(true)
                    try {
                        val ownerRuntime = checkNotNull(activeRuntime.get())
                        val control = ManagedRuntime::class.java
                            .getDeclaredField("legacyControl")
                            .apply { isAccessible = true }
                            .get(ownerRuntime)
                        val record = control.javaClass
                            .getDeclaredField("active")
                            .apply { isAccessible = true }
                            .get(control)
                        val shell = record.javaClass
                            .getDeclaredField("landlock")
                            .apply { isAccessible = true }
                            .get(record) as io.mazewall.landlock.PreparedLandlock
                        gapReached.set(true)
                        shell.prepareDirect()
                    } catch (failure: Throwable) {
                        gapFailure.set(failure)
                    } finally {
                        typedGap.set(false)
                    }
                    LinuxNative.setEngine(foreign)
                    swapped.set(true)
                }
                return result
            }

            override fun syscall4(
                nr: Long,
                a1: io.mazewall.platform.syscall.NativeArg,
                a2: io.mazewall.platform.syscall.NativeArg,
                a3: io.mazewall.platform.syscall.NativeArg,
                a4: io.mazewall.platform.syscall.NativeArg,
            ) = syscall(
                nr,
                a1,
                a2,
                a3,
                a4,
                io.mazewall.platform.syscall.NativeArg
                .LongArg(0),
                    io.mazewall.platform.syscall.NativeArg
                    .LongArg(0),
            )
        }
        LinuxNative.setEngine(object : io.mazewall.platform.native.NativeEngine by real {
            override val raw = raw
            override val process = object : io.mazewall.platform.native.NativeProcess by real.process {
                override fun prctl(command: PrctlCommand) = LinuxNative.SyscallResult.Success<Long, LinuxNative.SyscallHandledState.Unhandled>(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            override val fileSystem = object : io.mazewall.platform.native.NativeFileSystem by real.fileSystem {
                override fun close(
                    fd: io.mazewall.platform.fd.FileDescriptor<*, io.mazewall.platform.fd.FdState.Open, io.mazewall.platform.fd.FdOwnership.Owned>,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    beforeClose.get()?.invoke(fd)
                    closes.add(fd)
                    val result = real.fileSystem.close(fd)
                    afterClose.get()?.invoke(fd)
                    return result
                }
            }
        })
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName() = "Linux"

            override fun hasKernelSeccompSupport() = true

            override fun checkSeccompSanity() = LinuxNative.SyscallResult.Error<LinuxNative.SyscallHandledState.Unhandled>(22, -1L)

            override fun getLandlockCapability() = capability

            override fun probeSeccompTsync() = true

            override fun probeSeccompUserNotif() = true

            override fun probeCetSupported() = false
        })
            val source = java.nio.file.Files
                .writeString(directDirectory.resolve("DirectWorker.java"), "package fixture; public class DirectWorker { public int call() { return 7; } }")
            check(
                javax.tools.ToolProvider
                .getSystemJavaCompiler()
                .run(null, null, null, "-d", directDirectory.toString(), source.toString()) == 0,
            )
            val jar = directDirectory.resolve("worker.jar")
            java.util.jar
                .JarOutputStream(
                java.nio.file.Files
                .newOutputStream(jar),
            ).use {
                it.putNextEntry(java.util.jar.JarEntry("fixture/DirectWorker.class"))
                it.write(
                    java.nio.file.Files
                    .readAllBytes(directDirectory.resolve("fixture/DirectWorker.class")),
                )
                it.closeEntry()
            }
            java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val workerPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                val publicOwner = PreparedResolutionOwner.create()
                val owner = PreparedResolutionOwner::class.java
                    .getDeclaredField("capture")
                    .apply { isAccessible = true }
                    .get(publicOwner) as DirectResolutionCapture
                try {
                val operation = publicOwner.resolve(RuntimeProfile.HOTSPOT_JIT, workerPolicy, loader.loadClass("fixture.DirectWorker"), emptySet())
                val binding = OperationBinding("direct", OperationAdapter.CALLABLE, operation.implementationSha256, operation.resourceGeneration, mapOf("entryPoint" to "call()I"))
                val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, workerPolicy), emptySet(), emptyMap())
                val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), binding)
                val capture = owner.javaClass
                    .getDeclaredField("captured")
                    .apply { isAccessible = true }
                    .get(owner)
                val pin = (
                    capture.javaClass
                    .getDeclaredField("root")
                    .apply { isAccessible = true }
                    .get(capture) as io.mazewall.ffi.internal.PreparedPath
                ).descriptor
                val runtime = open()
                activeRuntime.set(runtime)
                try {
                    val capturedA = InstallationEnvironment.capture(Platform.FallbackBehavior.FAIL)
                    val capturedEngine = LinuxNative.snapshot()
                    val raceOwner = DirectResolutionCapture.create()
                    try {
                    val raceOperation = raceOwner.prepare(RuntimeProfile.HOTSPOT_JIT, workerPolicy, loader.loadClass("fixture.DirectWorker"), emptySet())
                    val raceCapture = raceOwner.javaClass
                        .getDeclaredField("captured")
                        .apply { isAccessible = true }
                        .get(raceOwner)
                    val racePin = (
                        raceCapture.javaClass
                        .getDeclaredField("root")
                        .apply { isAccessible = true }
                        .get(raceCapture) as io.mazewall.ffi.internal.PreparedPath
                    ).descriptor
                    val raceConstructor = ManagedRuntime::class.java
                        .getDeclaredConstructor(
                        RuntimeProfile::class.java,
                        InstallationEnvironment::class.java,
                        Duration::class.java,
                        Duration::class.java,
                    ).apply {
                        isAccessible =
                        true
                    }
                    val raceRuntime = raceConstructor.newInstance(RuntimeProfile.HOTSPOT_JIT, capturedA, timeout, timeout)
                    val raceEntered = CountDownLatch(1)
                    val releaseRace = CountDownLatch(1)
                    val raceDone = CountDownLatch(1)
                    val raceFailure = AtomicReference<Throwable?>()
                    val callerInterrupted = java.util.concurrent.atomic
                        .AtomicBoolean()
                    val raceHook = raceOwner.javaClass.getDeclaredField("afterValidationPermit").apply { isAccessible = true }
                    val originalRaceHook = raceHook.get(raceOwner)
                    raceHook.set(raceOwner, {
                        raceEntered.countDown()
                        var interrupted = false
                        while (true) {
                            try {
                                releaseRace.await()
                                break
                            } catch (_: InterruptedException) {
                                interrupted = true
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt()
                    })
                    val caller = Thread.ofPlatform().start {
                        try {
                            raceRuntime.prepare(reviewed, DomainLimits(1, 1), raceOperation)
                        } catch (failure: Throwable) {
                            raceFailure.set(failure)
                            callerInterrupted.set(Thread.currentThread().isInterrupted)
                        } finally {
                            raceDone.countDown()
                        }
                    }
                    try {
                        assertTrue(raceEntered.await(5, TimeUnit.SECONDS))
                        assertFailsWith<DirectPendingException> { raceOperation.close() }
                        caller.interrupt()
                        assertTrue(raceDone.await(5, TimeUnit.SECONDS))
                        assertIs<InterruptedException>(raceFailure.get())
                        assertTrue(callerInterrupted.get())
                        assertIs<DrainReport.Incomplete>(raceRuntime.drain(Duration.ofMillis(30)))
                        assertTrue(racePin.isLiveForIo())
                    } finally {
                        releaseRace.countDown()
                        caller.interrupt()
                        caller.join(5000)
                        raceRuntime.drain(timeout)
                        raceHook.set(raceOwner, originalRaceHook)
                        raceOwner.close()
                    }
                    assertFalse(caller.isAlive)
                    assertFalse(racePin.isLiveForIo())
                    } finally {
                        raceOwner.close()
                    }
                    val otherPolicy = WorkerPolicyBuilder
                        .denyList(RuntimeProfile.HOTSPOT_JIT)
                        .allowFsRead(jar)
                        .block(Syscall.CONNECT)
                        .build()
                    val wrongDocument = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, otherPolicy), emptySet(), emptyMap())
                    val wrongReview = ReviewedOperationArtifact.load(wrongDocument.toJson(), TrustedPolicyReview("operator", wrongDocument.sha256(), emptySet()), binding)
                    assertFailsWith<IllegalArgumentException> { runtime.prepare(wrongReview, DomainLimits(1, 1), operation) }
                    assertTrue(pin.isLiveForIo())
                    operation.validate()
                    swapOnCreate.set(true)
                    val domain = runtime.prepare(reviewed, DomainLimits(1, 1), operation)
                    assertTrue(swapped.get())
                    assertTrue(gapReached.get())
                    assertIs<IllegalStateException>(gapFailure.get())
                    assertEquals(0, nestedCreates.get())
                    assertEquals(1L, domain.generation)
                    val shell = domain.resource.javaClass
                        .getDeclaredField("preparedLandlock")
                        .apply { isAccessible = true }
                        .get(domain.resource) as io.mazewall.landlock.PreparedLandlock
                    val ruleset = shell.javaClass
                        .getDeclaredField("acquiredRuleset")
                        .apply { isAccessible = true }
                        .get(shell)
                    val reentrantRejections = AtomicInteger()
                    beforeClose.set { fd ->
                        if (fd === ruleset) {
                            assertFailsWith<IllegalStateException> { operation.retireDirect(DirectClaimAction.RETIRE_DOMAIN) }
                            assertTrue(pin.isLiveForIo())
                            reentrantRejections.incrementAndGet()
                        }
                    }
                    val permit = owner.javaClass
                        .getDeclaredField("claimedPermit")
                        .apply { isAccessible = true }
                        .get(owner)
                    assertFalse(runtime.matchesDirectPermit(Any(), operation, DirectClaimAction.APPLY_WORKER))
                    assertFalse(runtime.matchesDirectPermit(permit, operation, DirectClaimAction.APPLY_WORKER))
                    val permitClass = permit.javaClass
                    val originalRecord = permitClass.getDeclaredField("acquisition").apply { isAccessible = true }.get(permit)
                    val originalEnvironment = permitClass.getDeclaredField("environment").apply { isAccessible = true }.get(permit)
                    val originalAbi = permitClass.getDeclaredField("abi").apply { isAccessible = true }.getInt(permit)
                    val originalGate = permitClass.getDeclaredField("gate").apply { isAccessible = true }.get(permit)
                    val forged = permitClass.declaredConstructors
                        .single()
                        .apply { isAccessible = true }
                        .newInstance(runtime, originalRecord, operation, originalEnvironment, originalAbi, originalGate)
                    assertFalse(runtime.matchesDirectPermit(forged, operation, DirectClaimAction.CLAIM))
                    assertFailsWith<IllegalStateException> { shell.requireAcquisitionReady() }
                    var callbackCalls = 0
                    assertFailsWith<IllegalStateException> {
                        shell.mapRetainedAcquisition(LinuxNative.SyscallResult.Error(22, -1L)) { callbackCalls++ }
                    }
                    assertFailsWith<IllegalStateException> {
                        io.mazewall.landlock.PreparedLandlock
                            .prepareRetainedWithPostStore(workerPolicy.definition, capturedA, shell) { callbackCalls++ }
                    }
                    assertEquals(0, callbackCalls)
                    publicOwner.close()
                    operation.close()
                    assertEquals(PreparedResolutionRetirement.Transferred(null), publicOwner.retirement())
                    assertEquals(PreparedResolutionRetirement.Transferred(null), publicOwner.retryRetirement())
                    assertEquals(DirectResolutionCapture.Retirement.Transferred, owner.retire())
                    assertTrue(pin.isLiveForIo())
                    operation.validate()
                    val constructor = ManagedRuntime::class.java
                        .getDeclaredConstructor(
                        RuntimeProfile::class.java,
                        InstallationEnvironment::class.java,
                        Duration::class.java,
                        Duration::class.java,
                    ).apply {
                        isAccessible =
                        true
                    }
                    val other = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, capturedA, timeout, timeout)
                    try {
                        assertFalse(other.matchesDirectPermit(permit, operation, DirectClaimAction.CLAIM))
                        assertFailsWith<IllegalStateException> { other.prepare(reviewed, DomainLimits(1, 1), operation) }
                        assertTrue(pin.isLiveForIo())
                    } finally {
                        other.drain(timeout)
                    }
                    val worker = runtime
                        .submit(domain, Callable { Thread.currentThread() })
                        .result()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)
                    assertFalse(worker.isVirtual)
                    val spoofResult = AtomicReference<Boolean>()
                    val spoof = Thread.ofPlatform().name(worker.name).start { spoofResult.set(runtime.matchesDirectPermit(permit, operation, DirectClaimAction.APPLY_WORKER)) }
                    spoof.join(2000)
                    assertFalse(spoof.isAlive)
                    assertEquals(false, spoofResult.get())
                    val validationEntered = CountDownLatch(1)
                    val releaseValidation = CountDownLatch(1)
                    val validationFailure = AtomicReference<Throwable?>()
                    val validationHook = owner.javaClass.getDeclaredField("afterValidationPermit").apply { isAccessible = true }
                    val previousHook = validationHook.get(owner)
                    validationHook.set(owner, {
                        validationEntered.countDown()
                        check(releaseValidation.await(5, TimeUnit.SECONDS))
                    })
                    val validator = Thread.ofPlatform().start {
                        try {
                            operation.validate()
                        } catch (failure: Throwable) {
                            validationFailure.set(failure)
                        }
                    }
                    try {
                        assertTrue(validationEntered.await(5, TimeUnit.SECONDS))
                        domain.resource.fail(IllegalStateException("controlled retirement while runtime remains open"))
                        worker.join(2000)
                        assertFalse(worker.isAlive)
                        val ownerGate = owner.javaClass
                            .getDeclaredField("gate")
                            .apply { isAccessible = true }
                            .get(owner)
                        val reserved = owner.javaClass.getDeclaredField("runtimeRetirementReserved").apply { isAccessible = true }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!synchronized(ownerGate) { reserved.getBoolean(owner) } && System.nanoTime() < deadline) Thread.yield()
                        assertTrue(synchronized(ownerGate) { reserved.getBoolean(owner) })
                        val duplicateFailure = AtomicReference<Throwable?>()
                        val duplicateDone = CountDownLatch(1)
                        val duplicate = Thread.ofPlatform().start {
                            try {
                                runtime.prepare(reviewed, DomainLimits(1, 1), operation)
                            } catch (failure: Throwable) {
                                duplicateFailure.set(failure)
                            } finally {
                                duplicateDone.countDown()
                            }
                        }
                        try {
                            assertTrue(duplicateDone.await(3, TimeUnit.SECONDS), "Same-runtime duplicate claim must not deadlock retirement")
                            assertIs<IllegalStateException>(duplicateFailure.get())
                            assertTrue(pin.isLiveForIo())
                        } finally {
                            duplicate.interrupt()
                            if (duplicate.isAlive) releaseValidation.countDown()
                            duplicate.join(5000)
                            check(!duplicate.isAlive)
                        }
                        assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(30)))
                        worker.join(2000)
                        assertFalse(worker.isAlive)
                        assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(30)))
                        assertTrue(pin.isLiveForIo())
                        assertTrue(synchronized(closes) { closes.none { it === ruleset } })
                    } finally {
                        releaseValidation.countDown()
                        validator.join(5000)
                        validationHook.set(owner, previousHook)
                    }
                    assertFalse(validator.isAlive)
                    assertNull(validationFailure.get())
                    assertIs<DrainReport.Complete>(runtime.drain(timeout))
                    assertFalse(worker.isAlive)
                    assertFalse(pin.isLiveForIo())
                    assertEquals(DirectResolutionCapture.Retirement.Transferred, owner.retire())
                    assertFalse(runtime.matchesDirectPermit(permit, operation, DirectClaimAction.RETIRE_DOMAIN))
                    val observed = synchronized(closes) { closes.toList() }
                    assertTrue(observed.indexOfFirst { it === ruleset } >= 0)
                    assertTrue(observed.indexOfFirst { it === pin } > observed.indexOfFirst { it === ruleset })
                    assertEquals(1, observed.count { it === ruleset })
                    assertEquals(1, observed.count { it === pin })
                    assertEquals(1, reentrantRejections.get())
                    assertEquals(0, foreignCalls.get(), "Captured engine must own all subsequent rule, worker and descriptor effects")
                    LinuxNative.setEngine(capturedEngine)
                    val runtimeOwner = ManagedRuntimeAcquisitionOwner.create()
                    val secondRuntime = runtimeOwner.start(timeout, timeout)
                    val secondOwner = PreparedResolutionOwner.create()
                    val secondOperation = secondOwner.resolve(RuntimeProfile.HOTSPOT_JIT, workerPolicy, loader.loadClass("fixture.DirectWorker"), emptySet())
                    val secondBinding =
                        OperationBinding("direct", OperationAdapter.CALLABLE, secondOperation.implementationSha256, secondOperation.resourceGeneration, mapOf("entryPoint" to "call()I"))
                    val secondDocument = OperationPolicyDocument(secondBinding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, workerPolicy), emptySet(), emptyMap())
                    val secondReviewed = ReviewedOperationArtifact.load(secondDocument.toJson(), TrustedPolicyReview("operator", secondDocument.sha256(), emptySet()), secondBinding)
                    val secondDomain = secondRuntime.prepare(secondReviewed, DomainLimits(1, 1), secondOperation)
                    val secondShell = secondDomain.resource.javaClass
                        .getDeclaredField("preparedLandlock")
                        .apply { isAccessible = true }
                        .get(secondDomain.resource)
                    val secondRuleset = secondShell.javaClass
                        .getDeclaredField("acquiredRuleset")
                        .apply { isAccessible = true }
                        .get(secondShell)
                    val closeFailure = AssertionError("real close completed before failure")
                    afterClose.set { if (it === secondRuleset) throw closeFailure }
                    try {
                        val outcome = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(runtimeOwner.retire(timeout))
                        assertSame(closeFailure, outcome.history)
                        assertSame(closeFailure, assertFailsWith<AssertionError> { runtimeOwner.close() })
                        repeat(2) { assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(runtimeOwner.retire(timeout)) }
                        assertEquals(1, synchronized(closes) { closes.count { it === secondRuleset } })
                    } finally {
                        afterClose.set(null)
                        secondOwner.close()
                    }
                } finally {
                    runtime.drain(timeout)
                }
                } finally {
                    owner.close()
                }
            }
        } finally {
            LinuxNative.setEngine(engine)
        }
    }

    @Test
    fun `diagnostics retain physical lifetime after cancellation and never advance retirement`() {
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val initial = domain.diagnostics()
            assertEquals(ExecutionDiagnosticState.READY, initial.state)
            assertEquals(domain.generation, assertIs<ExecutionGeneration.InProcess>(initial.generation).generation)
            assertEquals(0L, initial.invocations.admitted)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    worker.set(Thread.currentThread())
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        cleanupEntered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                },
                )
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("capacity rejection entered") }) }
                assertEquals(CancellationDisposition.REQUESTED, handle.requestCancellation())
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ofMillis(20)))
                val held = domain.diagnostics()
                assertEquals(ExecutionDiagnosticState.DRAINING, held.state)
                assertEquals(1, held.unfinishedInvocations)
                assertTrue(held.liveWorkers > 0)
                assertFalse(held.retirementComplete)
                assertEquals(1L, held.invocations.admitted)
                assertEquals(1L, held.invocations.rejected)
                assertEquals(1L, held.invocations.cancellationRequests)
                assertEquals(1L, held.invocations.delivered)
                assertEquals(0L, held.invocations.ended)
                assertEquals(held, domain.diagnostics(), "polling must not drain or advance the owner")
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("draining owner admitted") }) }
                release.countDown()
                worker.get().join(5_000)
                assertFalse(worker.get().isAlive)
                assertFalse(domain.diagnostics().retirementComplete, "only the existing drain boundary may publish complete retirement")
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                val ended = domain.diagnostics()
                assertEquals(ExecutionDiagnosticState.CLOSED, ended.state)
                assertTrue(ended.retirementComplete)
                assertEquals(0, ended.liveWorkers)
                assertEquals(0, ended.unfinishedInvocations)
                assertEquals(2L, ended.invocations.rejected)
                assertEquals(1L, ended.invocations.ended)
                assertEquals(0L, initial.invocations.admitted, "captured snapshots must remain immutable")
                assertFalse(held.retirementComplete, "later retirement cannot upgrade a captured pending snapshot")
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `diagnostics retain failed generation identity after fatal delivery`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("diagnostic failed-generation control")
            val handle = runtime.submit(domain, Callable<Int> { throw fatal })
            assertSame(
                fatal,
                assertFailsWith<java.util.concurrent.ExecutionException> {
                handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }.cause,
            )
            assertEquals(ExecutionDiagnosticState.FAILED, domain.diagnostics().state)
            assertEquals(domain.generation, assertIs<ExecutionGeneration.InProcess>(domain.diagnostics().generation).generation)
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            val snapshot = domain.diagnostics()
            assertEquals(ExecutionDiagnosticState.FAILED, snapshot.state)
            assertTrue(snapshot.retirementComplete)
            assertEquals(1L, snapshot.invocations.delivered)
            assertEquals(1L, snapshot.invocations.ended)
        }
    }

    private val declared = ThreadLocal<Map<String, String>>()

    @Test
    fun `custom scoping captures once on caller before readiness and freezes handler membership`() {
        open().use { runtime ->
            val caller = Thread.currentThread()
            val reads = AtomicInteger()
            val handlers = mutableMapOf<Syscall, ScopingHandler>()
            val scoping = object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler>
                    get() {
                        assertTrue(Thread.currentThread() === caller)
                        reads.incrementAndGet()
                        return handlers
                    }
            }
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) handlers[Syscall.GETPID] = { _, _, _ -> true }
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            val domain = runtime.prepare(policy(), DomainLimits(1, 1), scoping)
            val resource = domain.resource
            val frozen = resource.javaClass.getDeclaredField("scoping").let {
                it.isAccessible = true
                it.get(resource) as io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy
            }
            assertEquals(1, reads.get())
            assertTrue(handlers.isNotEmpty())
            val observed = AtomicReference<io.mazewall.enforcer.internal.execution.FrozenStackScoping>()
            val handle = runtime.submit(
                domain,
                Callable {
                observed.set(io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.scoping)
                Thread.currentThread()
            },
            )
            val worker = handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertSame(frozen, observed.get())
            assertTrue(worker !== caller)
            assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun `custom capture failure preserves original ordinary and fatal identity with zero entry`() {
        open().use { runtime ->
            val entries = AtomicInteger()
            for (failure in listOf(IllegalArgumentException("capture failure"), LinkageError("capture fatal"))) {
                val scoping = object : StacktraceScopingPolicy {
                    override val handlers: Map<Syscall, ScopingHandler>
                        get() = throw failure
                }
                val observed = assertFailsWith<Throwable> {
                    val domain = runtime.prepare(policy(), DomainLimits(1, 1), scoping)
                    runtime.submit(domain, Callable { entries.incrementAndGet() })
                }
                assertTrue(observed === failure)
            }
            assertEquals(0, entries.get())
            assertEquals(42, Mazewall.callContained(runtime, runtime.prepare(policy(), DomainLimits(1, 1)), Callable { 42 }))
        }
    }

    @Test
    fun `required custom supervision without bootstrap prevents body entry and retires generation`() {
        open().use { runtime ->
            val entries = AtomicInteger()
            val scoping = object : StacktraceScopingPolicy {
                override val handlers = mapOf<Syscall, ScopingHandler>(Syscall.CONNECT to { _, _, _ -> true })
            }
            val failure = assertFailsWith<IllegalStateException> {
                val allowedConnect = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allow(Syscall.CONNECT).build()
                val domain = runtime.prepare(allowedConnect, DomainLimits(1, 1), scoping)
                runtime.submit(domain, Callable { entries.incrementAndGet() })
            }
            assertTrue(failure.message.orEmpty().contains("Cannot spawn SupervisorDaemon"))
            assertEquals(0, entries.get())
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
        }
    }

    private fun bridge(
        applied: AtomicInteger = AtomicInteger(),
        restore: () -> Unit = {},
    ): InvocationContextBridge =
        InvocationContextBridge {
        val caller = Thread.currentThread()
        check(!caller.name.startsWith("mazewall-manager-"))
        InvocationContextSnapshot(declared.get() ?: emptyMap()) { metadata ->
            applied.incrementAndGet()
            val previous = declared.get()
            declared.set(metadata)
            AutoCloseable {
                restore()
                if (previous == null) declared.remove() else declared.set(previous)
            }
        }
    }

    @Test
    fun `captured immutable metadata alternates on exact worker and restores nested one permit`() {
        val unrelated = ThreadLocal<String>()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val caller = Thread.currentThread()
            val worker = java.util.concurrent.atomic
                .AtomicReference<Thread>()
            unrelated.set("never-transfer")
            try {
                for (name in listOf("A", "B", "A")) {
                    val mutable = mutableMapOf("name" to name)
                    declared.set(mutable)
                    val captured = runtime.captureInvocation(
                        domain,
                        bridge(),
                        Callable {
                        val current = Thread.currentThread()
                        assertTrue(current !== caller && !current.isVirtual)
                        assertTrue(worker.compareAndSet(null, current) || worker.get() === current)
                        assertNull(unrelated.get())
                        assertEquals(name, declared.get()["name"])
                        assertFailsWith<UnsupportedOperationException> { (declared.get() as MutableMap)["name"] = "mutation" }
                        declared.set(mapOf("name" to "inner"))
                        val inner = runtime.captureInvocation(
                            domain,
                            bridge(),
                            Callable {
                            assertTrue(Thread.currentThread() === current)
                            assertEquals("inner", declared.get()["name"])
                            42
                        },
                        )
                        declared.set(mapOf("name" to name))
                        assertEquals(42, Mazewall.callContained(runtime, domain, inner))
                        assertEquals(name, declared.get()["name"])
                        name
                    },
                    )
                    mutable["name"] = "caller-change"
                    val invocation = runtime.submit(domain, captured)
                    assertEquals(name, invocation.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    assertNull(assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                    assertEquals("caller-change", declared.get()["name"])
                    val clean = runtime.submit(domain, Callable { declared.get() })
                    assertNull(clean.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    assertNull(assertIs<ExecutionEnd.Finished>(clean.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                }
            } finally {
                unrelated.remove()
                declared.remove()
            }
        }
    }

    @Test
    fun `queued capture precedes caller changes and queued cancellation never applies context`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val applied = AtomicInteger()
            val calls = AtomicInteger()
            try {
                val held = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                declared.set(mapOf("name" to "queued"))
                val queued = runtime.submit(domain, runtime.captureInvocation(domain, bridge(applied), Callable { calls.incrementAndGet() }))
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = runtime.submit(domain, runtime.captureInvocation(domain, bridge(applied), Callable { declared.get()["name"] }))
                declared.set(mapOf("name" to "changed"))
                assertEquals(0, applied.get())
                release.countDown()
                held.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals("queued", next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                next.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals(1, applied.get())
                assertEquals(0, calls.get())
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
            } finally {
                release.countDown()
                declared.remove()
            }
        }
    }

    @Test
    fun `held restoration keeps physical end and admission after cooperative cancellation`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val restoring = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                declared.set(mapOf("name" to "cancelled"))
                val captured = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    restoring.countDown()
                    while (release.count > 0) {
                        try {
                            check(release.await(5, TimeUnit.SECONDS))
                        } catch (_: InterruptedException) {
                            // restoration still owns context
                        }
                    }
                }),
                    Callable {
                    assertEquals("cancelled", declared.get()["name"])
                    entered.countDown()
                    CountDownLatch(1).await()
                },
                )
                val handle = runtime.submit(domain, captured)
                check(entered.await(5, TimeUnit.SECONDS))
                handle.requestCancellation()
                check(restoring.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
            } finally {
                release.countDown()
                declared.remove()
            }
        }
    }

    @Test
    fun `restoration failure quarantines generation and preserves fatal primary identity`() {
        for (fatal in listOf(false, true)) {
            open().use { runtime ->
                val domain = runtime.prepare(policy(), DomainLimits(1, 1))
                val primary: Throwable = if (fatal) LinkageError("body fatal") else IllegalArgumentException("body failure")
                val cleanup = IllegalStateException("restore unproved")
                val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable<Int> { throw primary }))
                val delivered = assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause
                assertTrue(delivered === primary)
                assertTrue(primary.suppressed.any { it === cleanup })
                assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === primary)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("unsafe reuse") }) }
            }
        }
    }

    @Test
    fun `restoration quarantine preserves canonical containment delivery and original denied cause`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val denied = AccessDeniedException("declared body denial")
            val cleanup = IllegalStateException("unproved restore")
            val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable<Int> { throw denied }))
            val delivered = assertIs<ContainmentViolationException>(
                assertFailsWith<java.util.concurrent.ExecutionException> {
                handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }.cause,
            )
            assertTrue(delivered.cause === denied)
            assertTrue(denied.suppressed.any { it === cleanup })
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === delivered)
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `caught nested restoration failure cannot become successful outer evidence`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val cleanup = IllegalStateException("nested restore")
            val handle = runtime.submit(
                domain,
                Callable {
                val nested = runtime.captureInvocation(domain, bridge(restore = { throw cleanup }), Callable { 1 })
                assertTrue(assertFailsWith<IllegalStateException> { Mazewall.callContained(runtime, domain, nested) } === cleanup)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === cleanup)
                42
            },
            )
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === cleanup)
            handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `caught nested fatal with clean restoration still retires generation and retains original evidence`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("nested fatal")
            val restored = AtomicInteger()
            val handle = runtime.submit(
                domain,
                Callable {
                val nested = runtime.captureInvocation(domain, bridge(restore = { restored.incrementAndGet() }), Callable<Int> { throw fatal })
                assertTrue(assertFailsWith<LinkageError> { Mazewall.callContained(runtime, domain, nested) } === fatal)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === fatal)
                42
            },
            )
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
            assertEquals(1, restored.get())
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `recorded nested fatal remains terminal when plain or captured outer throws a later failure`() {
        for (capturedOuter in listOf(false, true)) {
            for (outerFailure in listOf(IllegalArgumentException("later business"), LinkageError("later fatal"))) {
                assertNestedFatalTerminal(capturedOuter, outerFailure)
            }
        }
    }

    @Test
    fun `recorded nested fatal remains terminal through captured outer business failure and cleanup fatal`() {
        assertNestedFatalTerminal(true, IllegalArgumentException("later business"), LinkageError("later cleanup fatal"))
    }

    private fun assertNestedFatalTerminal(
        capturedOuter: Boolean,
        outerFailure: Throwable,
        cleanupFatal: Error? = null,
    ) {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val fatal = LinkageError("original nested fatal")
            val innerRestored = AtomicInteger()
            val outerRestored = AtomicInteger()
            val caller = Thread.currentThread()
            val outer = Callable<Int> {
                val worker = Thread.currentThread()
                assertTrue(worker !== caller && !worker.isVirtual)
                if (!capturedOuter) declared.set(mapOf("name" to "outer"))
                val previous = declared.get()
                assertEquals("outer", previous["name"])
                declared.set(mapOf("name" to "inner"))
                val inner = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    assertEquals("inner", declared.get()["name"])
                    innerRestored.incrementAndGet()
                }),
                    Callable<Int> {
                    assertTrue(Thread.currentThread() === worker)
                    assertEquals("inner", declared.get()["name"])
                    throw fatal
                },
                )
                declared.set(previous)
                assertTrue(assertFailsWith<LinkageError> { Mazewall.callContained(runtime, domain, inner) } === fatal)
                assertTrue(declared.get() === previous)
                assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure === fatal)
                throw outerFailure
            }
            val contexts = InvocationContextBridge {
                InvocationContextSnapshot(mapOf("name" to "outer")) { metadata ->
                    val previous = declared.get()
                    declared.set(metadata)
                    AutoCloseable {
                        assertEquals("outer", declared.get()["name"])
                        if (previous == null) declared.remove() else declared.set(previous)
                        assertTrue(declared.get() === previous)
                        outerRestored.incrementAndGet()
                        cleanupFatal?.let { throw it }
                    }
                }
            }
            val handle = runtime.submit(domain, if (capturedOuter) runtime.captureInvocation(domain, contexts, outer) else outer)
            val delivered = assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause
            assertTrue(delivered === fatal)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
            assertEquals(1, innerRestored.get())
            assertEquals(if (capturedOuter) 1 else 0, outerRestored.get())
            if (cleanupFatal == null) {
                assertEquals(listOf(outerFailure), fatal.suppressed.toList())
            } else {
                assertEquals(listOf(cleanupFatal), fatal.suppressed.toList())
                assertEquals(listOf(outerFailure), cleanupFatal.suppressed.toList())
            }
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `ordinary body failure restores and capture failure consumes no admission`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val caller = Thread.currentThread()
            val captureFailure = IllegalArgumentException("caller capture")
            val failing = InvocationContextBridge {
                assertTrue(Thread.currentThread() === caller)
                throw captureFailure
            }
            assertTrue(assertFailsWith<IllegalArgumentException> { runtime.captureInvocation(domain, failing, Callable { 0 }) } === captureFailure)
            declared.set(mapOf("name" to "business"))
            try {
                val business = IllegalArgumentException("business")
                val handle = runtime.submit(domain, runtime.captureInvocation(domain, bridge(), Callable<Int> { throw business }))
                assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === business)
                handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertNull(Mazewall.callContained(runtime, domain, Callable { declared.get() }))
                val captured = runtime.captureInvocation(domain, bridge(), Callable { 1 })
                assertFailsWith<IllegalStateException> { captured.call() }
                assertEquals("business", declared.get()["name"])
            } finally {
                declared.remove()
            }
        }
    }

    @Test
    fun `application failure has zero body entry and quarantines unproved worker context`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val calls = AtomicInteger()
            val applicationFailure = IllegalStateException("partially applied")
            val context = InvocationContextBridge { InvocationContextSnapshot(emptyMap()) { throw applicationFailure } }
            val handle = runtime.submit(domain, runtime.captureInvocation(domain, context, Callable { calls.incrementAndGet() }))
            assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === applicationFailure)
            assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === applicationFailure)
            assertEquals(0, calls.get())
            assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
        }
    }

    @Test
    fun `fatal restoration promotes over business failure and clean restoration preserves body fatal`() {
        for (restoreFatal in listOf(false, true)) {
            open().use { runtime ->
                val domain = runtime.prepare(policy(), DomainLimits(1, 1))
                val fatal = LinkageError("original fatal")
                val business = IllegalArgumentException("original business")
                val restored = CountDownLatch(1)
                val captured = runtime.captureInvocation(
                    domain,
                    bridge(restore = {
                    restored.countDown()
                    if (restoreFatal) throw fatal
                }),
                    Callable<Int> { if (restoreFatal) throw business else throw fatal },
                )
                val handle = runtime.submit(domain, captured)
                assertTrue(assertFailsWith<java.util.concurrent.ExecutionException> { handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
                assertTrue(assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
                assertEquals(0L, restored.count)
                if (restoreFatal) assertTrue(fatal.suppressed.any { it === business })
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
            }
        }
    }

    @Test
    fun `missing required read resource rejects preparation before any domain can admit`() {
        val root = java.nio.file.Files
            .createTempDirectory("missing-prepared-resource-")
        try {
            open().use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(root.resolve("missing")).build()
                assertFailsWith<java.nio.file.NoSuchFileException> { runtime.prepare(policy, DomainLimits(1, 1)) }
            }
        } finally {
            java.nio.file.Files
                .delete(root)
        }
    }

    @Test
    fun `missing writable leaf never implicitly grants its parent`() {
        val root = java.nio.file.Files
            .createTempDirectory("missing-prepared-output-")
        try {
            open().use { runtime ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsReadWrite(root.resolve("future-output")).build()
                assertFailsWith<java.nio.file.NoSuchFileException> { runtime.prepare(policy, DomainLimits(1, 1)) }
            }
        } finally {
            java.nio.file.Files
                .delete(root)
        }
    }

    @Test
    fun `reviewed operation prepares its exact policy and retains configured binding`() {
        open().use { runtime ->
            val binding = OperationBinding("owned-operation", OperationAdapter.CALLABLE, "a".repeat(64), "resources-1", mapOf("entryPoint" to "call()I"))
            val document = OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy()), emptySet(), emptyMap())
            val reviewed = ReviewedOperationArtifact.load(document.toJson(), TrustedPolicyReview("operator", document.sha256(), emptySet()), binding)
            val domain = runtime.prepare(reviewed, DomainLimits(1, 1))
            assertEquals("owned-operation", domain.operationBinding?.operationId)
            assertEquals(42, Mazewall.callContained(runtime, domain, Callable { 42 }))
        }
    }

    @Test
    fun `owned domain has no synthetic raw phase setter`() {
        val names = io.mazewall.enforcer.internal.execution.OwnedDomain::class.java.declaredMethods.map { it.name }
        assertFalse(names.any { it == "access\$setPhase\$p" })
        assertFalse(names.any { it == "access\$getPending\$p" })
        assertFalse(names.any { it.contains("liveCancellationPermit", ignoreCase = true) })
        val invocationNames = io.mazewall.enforcer.internal.execution.OwnedInvocation::class.java.declaredMethods.map { it.name }
        assertFalse(invocationNames.any { it.contains("cancellationPermit", ignoreCase = true) })
    }

    @Test
    fun `canonical synchronous entry nests inline on the exact active worker`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            assertEquals(
                42,
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                val worker = Thread.currentThread()
                checkNotNull(Mazewall.currentInvocationEvidence())
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertTrue(Thread.currentThread() === worker)
                    42
                },
                )
            },
                ),
            )
            assertNull(Mazewall.currentInvocationEvidence())
        }
    }

    @Test
    fun `caught nested containment failure remains unsuccessful and evidence is not reused`() {
        open().use { runtime ->
            // Result delivery precedes admission release; queue the follow-up on the same worker.
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            assertFailsWith<ContainmentViolationException> {
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                    assertFailsWith<ContainmentViolationException> {
                        Mazewall.callContained(runtime, domain, Callable<Int> { throw AccessDeniedException("private-resource") })
                    }
                    assertTrue(Mazewall.currentInvocationEvidence()?.observedFailure is ContainmentViolationException)
                    42
                },
                )
            }
            assertEquals(
                43,
                Mazewall.callContained(
                    runtime,
                    domain,
                    Callable {
                assertNull(Mazewall.currentInvocationEvidence()?.observedFailure)
                43
            },
                ),
            )
        }
    }

    @Test
    fun `different prepared domain cannot run inline or widen a caught transition`() {
        open().use { runtime ->
            val first = runtime.prepare(policy(), DomainLimits(1, 1))
            val second = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = AtomicInteger()
            assertFailsWith<ExecutionTransitionException> {
                Mazewall.callContained(
                    runtime,
                    first,
                    Callable {
                    assertFailsWith<ExecutionTransitionException> {
                        Mazewall.callContained(runtime, second, Callable { entered.incrementAndGet() })
                    }
                    42
                },
                )
            }
            assertEquals(0, entered.get())
        }
    }

    @Test
    fun `declared runtime cannot misreport incompatible process mapping floor`() {
        var opened: ManagedRuntime? = null
        try {
            assertFailsWith<IllegalArgumentException> {
                ManagedRuntime.open(RuntimeProfile.NATIVE_IMAGE, Policy.NO_EXEC_HOTSPOT, timeout, timeout).also { opened = it }
            }
        } finally {
            opened?.close()
        }
    }

    @Test
    fun `worker startup preserves original fatal identity and retires all owned threads`() {
        open().use { runtime ->
            val fatal = AssertionError("native startup fatal")
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs) throw fatal
                LinuxNative.SyscallResult.Success(2L)
            }
            val failure = assertFailsWith<AssertionError> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertTrue(failure === fatal)
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }

    @Test
    fun `foreign domains and unsupported nested dispatch cannot enter application work`() {
        open().use { first ->
            open().use { second ->
                val domain = first.prepare(policy(), DomainLimits(1, 1))
                val calls = AtomicInteger()
                assertFailsWith<IllegalArgumentException> { second.submit(domain, Callable { calls.incrementAndGet() }) }
                assertEquals(
                    42,
                    first
                        .submit(
                        domain,
                        Callable {
                    assertFailsWith<RejectedExecutionException> { first.submit(domain, Callable { calls.incrementAndGet() }) }
                    assertFailsWith<IllegalStateException> { first.prepare(policy(), DomainLimits(1, 1)) }
                    42
                },
                    ).result()
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS),
                )
                assertEquals(0, calls.get())
            }
        }
    }

    @Test
    fun `ordinary business failure preserves a healthy installed worker`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val business = IllegalArgumentException("business failure")
            val failed = runtime.submit(domain, Callable<Int> { throw business })
            val ended = assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
            assertTrue(ended.failure === business)
            assertEquals(
                42,
                runtime
                .submit(domain, Callable { 42 })
                .result()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun `preparation timeout cancels bootstrap without hiding its live workers`() {
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, Duration.ofSeconds(1), timeout).use { runtime ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            engine.process.onPrctl = { command ->
                if (command is PrctlCommand.SetNoNewPrivs && Thread.currentThread().name.startsWith("mazewall-domain-")) {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            // native startup deliberately remains live
                        }
                    }
                }
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            try {
                assertFailsWith<TimeoutException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                check(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                release.countDown()
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed live generation cannot be hidden by preparing replacement capacity`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(2, 2))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val survivor = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val failed = runtime.submit(domain, Callable<Int> { throw AssertionError("generation failure") })
                assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertFalse(survivor.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<IllegalStateException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
                release.countDown()
                survivor.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `caller future mutation cannot retire physical work or cancel internal delivery`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                handle.result().toCompletableFuture().cancel(true)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 0 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `owned workers retain prepared native authority and refuse caller execution on overload`() {
        open().use { runtime ->
            val foreignCalls = AtomicInteger()
            LinuxNative.setEngine(
                MockNativeEngine().apply {
                process.onPrctl = {
                    foreignCalls.incrementAndGet()
                    LinuxNative.SyscallResult.Error(1, -1L)
                }
            },
            )
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = Thread.currentThread()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    assertFalse(Thread.currentThread().isVirtual)
                    assertTrue(Thread.currentThread() !== caller)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val second = runtime.submit(domain, Callable { 43 })
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { error("must not run on caller") }) }
                release.countDown()
                assertEquals(42, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(43, second.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, foreignCalls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `successful result delivery can retain admission until execution end`() {
        val releaseBody = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val secondEntries = AtomicInteger()
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                        worker.set(Thread.currentThread())
                        check(releaseBody.await(5, TimeUnit.SECONDS))
                        42
                    },
                )
                val callback = first.result().whenComplete { value, failure ->
                    assertSame(worker.get(), Thread.currentThread())
                    assertNull(failure)
                    assertEquals(42, value)
                    assertNull(io.mazewall.enforcer.internal.execution.RuntimeWorkerContext.invocation)
                    assertFailsWith<ExecutionTransitionException> {
                        Mazewall.callContained(runtime, domain, Callable { secondEntries.incrementAndGet() })
                    }
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                }
                releaseBody.countDown()
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                assertEquals(42, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertFalse(first.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> {
                    runtime.submit(domain, Callable { secondEntries.incrementAndGet() })
                }
                assertEquals(0, secondEntries.get())

                releaseCallback.countDown()
                assertEquals(42, callback.toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                val second = runtime.submit(
                    domain,
                    Callable {
                        secondEntries.incrementAndGet()
                        43
                    },
                )
                assertEquals(43, second.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertNull(assertIs<ExecutionEnd.Finished>(second.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                assertEquals(1, secondEntries.get())
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertFalse(checkNotNull(worker.get()).isAlive)
            } finally {
                releaseBody.countDown()
                releaseCallback.countDown()
            }
        }
    }

    @Test
    fun `running cancellation retains admission until physical completion`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                    }
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.REQUESTED, handle.requestCancellation())
                check(interrupted.await(5, TimeUnit.SECONDS))
                assertTrue(handle.result().toCompletableFuture().isCompletedExceptionally)
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertIs<ExecutionEnd.Finished>(handle.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(CancellationDisposition.ALREADY_ENDED, handle.requestCancellation())
                assertFalse(
                    runtime
                    .submit(domain, Callable { Thread.currentThread().isInterrupted })
                    .result()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS),
                )
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `queued cancellation never enters the body and frees bounded admission`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 2))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                val first = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    1
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                val queued = runtime.submit(domain, Callable { calls.incrementAndGet() })
                assertEquals(CancellationDisposition.REQUESTED, queued.requestCancellation())
                assertIs<ExecutionEnd.NotStarted>(queued.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                val next = runtime.submit(domain, Callable { 3 })
                release.countDown()
                assertEquals(1, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(3, next.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(0, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `drain reports live execution and stops admission until actual retirement`() {
        open().use { runtime ->
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val handle = runtime.submit(
                    domain,
                    Callable {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    42
                },
                )
                check(entered.await(5, TimeUnit.SECONDS))
                assertIs<DrainReport.Incomplete>(runtime.drain(Duration.ZERO))
                assertFalse(handle.executionEnd().toCompletableFuture().isDone)
                assertFailsWith<RejectedExecutionException> { runtime.submit(domain, Callable { 1 }) }
                release.countDown()
                assertEquals(42, handle.result().toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `failed worker preparation retires its generation before any application entry`() {
        open().use { runtime ->
            engine.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
            assertFailsWith<PartialInstallationException> { runtime.prepare(policy(), DomainLimits(2, 2)) }
            assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith("mazewall-domain-") })
        }
    }

    @Test
    fun `failed generation does not await workers that were never started`() {
        val domain = io.mazewall.enforcer.internal.execution.OwnedDomain(
            1,
            DomainLimits(2, 2),
            policy().definition,
            InstallationEnvironment.capture(),
            null,
            null,
            "unstarted-preparation-",
            io.mazewall.enforcer.internal.execution.FrozenStackScoping
                .Legacy(DefaultStacktraceScopingPolicy),
        )
        val failure = IllegalStateException("startup already failed")
        domain.fail(failure)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val actual = assertFailsWith<IllegalStateException> {
                domain.prepare(
                    io.mazewall.enforcer.internal.execution
                    .Deadline(Duration.ofSeconds(30)),
                )
            }
            assertSame(failure, actual)
        }
        assertTrue(domain.drainSnapshot().retired)
    }

    @Test
    fun `public synthetic runtime constructor cannot admit a domain before baseline`() {
        val constructor = ManagedRuntime::class.java.getConstructor(
            RuntimeProfile::class.java,
            Duration::class.java,
            Duration::class.java,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )
        val runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, timeout, timeout, null)
        try {
            assertFailsWith<java.util.concurrent.RejectedExecutionException> { runtime.prepare(policy(), DomainLimits(1, 1)) }
            val managerThread = ManagedRuntime::class.java.getDeclaredField("managerThread").apply { isAccessible = true }
            assertNull((managerThread.get(runtime) as AtomicReference<*>).get())
        } finally {
            runtime.drain(timeout)
        }
    }

    @Test
    fun `static scoping boundary rejects another original runtime state`() {
        val constructor = ManagedRuntime::class.java.getConstructor(
            RuntimeProfile::class.java,
            Duration::class.java,
            Duration::class.java,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )
        val first = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, timeout, timeout, null)
        val second = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, timeout, timeout, null)
        try {
            val shared = ManagedRuntime::class.java
                .getDeclaredField("shared")
                .apply { isAccessible = true }
                .get(second)
            val boundary = Class
                .forName("io.mazewall.enforcer.api.execution.ManagedRuntime\$ScopingFailureBoundary")
                .getDeclaredConstructor(ManagedRuntime::class.java, shared.javaClass, Any::class.java)
                .apply { isAccessible = true }
            val originalPermit = ManagedRuntime::class.java
                .getDeclaredField("operationsPermit")
                .apply { isAccessible = true }
                .get(first)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> { boundary.newInstance(first, shared, originalPermit) }
            assertIs<IllegalStateException>(failure.cause)
            assertEquals(DrainReport.Complete, first.drain(timeout))
            assertEquals(DrainReport.Complete, second.drain(timeout))
        } finally {
            first.drain(timeout)
            second.drain(timeout)
        }
    }

    @Test
    fun `runtime acquisition rejects invalid duration without reserving`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        assertFailsWith<IllegalArgumentException> { owner.start(Duration.ZERO, timeout) }
        assertIs<ManagedRuntimeAcquisitionStatus.NotStarted>(owner.status())
        owner.start(timeout, timeout)
        assertFailsWith<IllegalStateException> { owner.start(timeout, timeout) }
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.retire(timeout))
    }

    @Test
    fun `runtime acquisition keeps unknown installation after original manager exits`() {
        val failure = AssertionError("native dispatch failed")
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val physicalManager = AtomicReference<Thread>()
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetNoNewPrivs) {
                calls.incrementAndGet()
                physicalManager.set(Thread.currentThread())
                throw failure
            }
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        assertSame(failure, assertFailsWith<AssertionError> { owner.start(timeout, timeout) })
        val status = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
        assertSame(failure, status.history)
        assertFalse(physicalManager.get().isAlive)
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
        assertEquals(1, calls.get())
        assertSame(failure, assertFailsWith<RuntimeAcquisitionUnknownException> { owner.close() }.cause)
    }

    @Test
    fun `runtime acquisition use preserves original fatal without self suppression`() {
        val failure = AssertionError("installation failed")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetNoNewPrivs) throw failure
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        var originalSuppressed = 0
        val actual = assertFailsWith<AssertionError> {
            owner.use {
                try {
                    it.start(timeout, timeout)
                } catch (problem: AssertionError) {
                    originalSuppressed = problem.suppressed.size
                    throw problem
                }
            }
        }
        assertSame(failure, actual)
        assertEquals(originalSuppressed + 1, actual.suppressed.size)
        assertFalse(actual.suppressed.any { it === failure })
        val unknown = actual.suppressed.filterIsInstance<RuntimeAcquisitionUnknownException>().single()
        assertSame(failure, unknown.cause)
        repeat(2) {
            val repeated = assertFailsWith<RuntimeAcquisitionUnknownException> { owner.close() }
            assertSame(failure, repeated.cause)
            assertEquals(originalSuppressed + 1, failure.suppressed.size)
        }
    }

    @Test
    fun `owner remains pending while its worker lives and registry is private`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val runtime = owner.start(timeout, timeout)
        val domain = runtime.prepare(policy(), DomainLimits(1, 1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            runtime.submit(
                domain,
                Callable {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        }
                }
            },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(ManagedRuntime::class.java.methods.singleOrNull { it.name == "access\$getDomains\$p" })
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofMillis(100)))
        } finally {
            release.countDown()
            domain.resource.beginDrain()
            domain.resource.awaitRetirement(
                io.mazewall.enforcer.internal.execution
                .Deadline(timeout),
            )
        }
    }

    @Test
    fun `late startup fatal preserves earlier timeout and is delivered only once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AssertionError("late installation failure")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetNoNewPrivs) {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        }
                }
                throw failure
            }
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        try {
            val timeoutFailure = assertFailsWith<TimeoutException> { owner.start(Duration.ofMillis(100), timeout) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val unknown = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
            assertSame(failure, unknown.history)
            assertTrue(failure.suppressed.any { it === timeoutFailure })
            assertSame(failure, assertFailsWith<AssertionError> { owner.close() })
            assertSame(failure, assertFailsWith<RuntimeAcquisitionUnknownException> { owner.close() }.cause)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `runtime acquisition owner closes inertly before start`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        assertIs<ManagedRuntimeAcquisitionStatus.NotStarted>(owner.status())
        owner.close()
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.status())
        assertFailsWith<IllegalStateException> { owner.start(timeout, timeout) }
    }

    @Test
    fun `runtime acquisition owner retains actual started runtime until physical retirement`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val runtime = owner.start(timeout, timeout)
        assertIs<ManagedRuntimeAcquisitionStatus.Active>(owner.status())
        assertEquals(DrainReport.Complete, runtime.drain(timeout))
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.retire(timeout))
        owner.close()
    }

    @Test
    fun `runtime acquisition timeout retains physically running original manager`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetNoNewPrivs && Thread.currentThread().name.startsWith("mazewall-manager-")) {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // The delegated native operation is still physically in progress.
                    }
                }
            }
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        try {
            assertFailsWith<TimeoutException> { owner.start(Duration.ofMillis(100), Duration.ofMillis(100)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofMillis(100)))
        } finally {
            release.countDown()
        }
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.retire(timeout))
    }

    @Test
    fun `runtime acquisition owner reconciles concurrent same runtime drain`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val runtime = owner.start(timeout, timeout)
        val domain = runtime.prepare(policy(), DomainLimits(1, 1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = AtomicReference<DrainReport>()
        val started = CountDownLatch(1)
        try {
            runtime.submit(
                domain,
                Callable {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        }
                }
                42
            },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val thread = Thread.ofPlatform().start {
                started.countDown()
                drained.set(runtime.drain(timeout))
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofMillis(100)))
            release.countDown()
            thread.join(timeout.toMillis())
            assertFalse(thread.isAlive)
            assertEquals(DrainReport.Complete, drained.get())
            assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.retire(timeout))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `Java try with resources retains startup Error without self suppression`() {
        val source = java.nio.file.Files.writeString(
            directDirectory.resolve("OwnerTry.java"),
            "public class OwnerTry { public static void run(AutoCloseable c, Runnable r) throws Exception { try (c) { r.run(); } } }",
        )
        assertEquals(
            0,
            javax.tools.ToolProvider
            .getSystemJavaCompiler()
            .run(null, null, null, "-d", directDirectory.toString(), source.toString()),
        )
        val failure = AssertionError("Java startup fatal")
        engine.process.onPrctl = { command ->
            if (command is PrctlCommand.SetNoNewPrivs) throw failure
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        java.net.URLClassLoader(arrayOf(directDirectory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val method = loader.loadClass("OwnerTry").getMethod("run", AutoCloseable::class.java, Runnable::class.java)
            val thrown = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                method.invoke(null, owner, Runnable { owner.start(timeout, timeout) })
            }
            assertSame(failure, thrown.cause)
            assertFalse(failure.suppressed.any { it === failure })
            val suppressed = failure.suppressed.filterIsInstance<RuntimeAcquisitionUnknownException>().single()
            assertSame(failure, suppressed.cause)
            assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(timeout))
            assertSame(failure, assertFailsWith<RuntimeAcquisitionUnknownException> { owner.close() }.cause)
            val protocol = owner.javaClass
                .getDeclaredField("protocol")
                .apply { isAccessible = true }
                .get(owner)
            assertTrue(
                protocol.javaClass
                .getDeclaredField("physicalComplete")
                .apply { isAccessible = true }
                .getBoolean(protocol),
            )
        }
    }

    @Test
    fun `checked runtime request validation is inert until the original owner starts`() {
        val calls = AtomicInteger()
        engine.process.onPrctl = { command ->
            calls.incrementAndGet()
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        io.mazewall.ffi.internal.OwnedStackScopingPackagedBridge
            .run(arrayOf("canonical-request-validation"))
        assertEquals(0, calls.get())
    }

    @Test
    fun `original runtime state is not exposed through ordinary same package reflection`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val runtime = owner.start(timeout, timeout)
        try {
            val stateType = Class.forName("io.mazewall.enforcer.api.execution.ManagedRuntime\$RuntimeState")
            val shared = ManagedRuntime::class.java.getDeclaredField("shared")
            val operationsField = ManagedRuntime::class.java.getDeclaredField("operations")
            assertFailsWith<IllegalAccessException> { shared.get(runtime) }
            assertFailsWith<IllegalAccessException> { operationsField.get(runtime) }
            val accessor = ManagedRuntime::class.java.getMethod("access\$getOperations\$p", ManagedRuntime::class.java)
            val operations = accessor.invoke(null, runtime)
            val operationsShared = operations.javaClass.getDeclaredField("shared")
            assertFailsWith<IllegalAccessException> { operationsShared.get(operations) }
            val visibleMethods = ManagedRuntime::class.java.declaredMethods + operations.javaClass.declaredMethods
            assertTrue(
                visibleMethods.none {
                !java.lang.reflect.Modifier
                .isPrivate(it.modifiers) &&
                    it.returnType == stateType
            },
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `runtime operations reject foreign permit through ordinary same package lookup`() {
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val runtime = owner.start(timeout, timeout)
        try {
            val holder = Class.forName("io.mazewall.enforcer.api.execution.ManagedRuntime\$RuntimeOperations")
            val accessor = ManagedRuntime::class.java.getMethod("access\$getOperations\$p", ManagedRuntime::class.java)
            val actual = accessor.invoke(null, runtime)
            val admission = java.lang.invoke.MethodHandles
                .lookup()
                .unreflect(holder.getDeclaredMethod("requireAdmission", Any::class.java))
            assertFailsWith<IllegalStateException> { admission.invokeWithArguments(actual, Any()) }
            assertFailsWith<NullPointerException> { admission.invokeWithArguments(actual, null) }
            val terminated = java.lang.invoke.MethodHandles
                .lookup()
                .unreflect(holder.getDeclaredMethod("managerTerminated", Any::class.java))
            assertEquals(false, terminated.invokeWithArguments(actual, Any()))
            val nativeCalls = AtomicInteger()
            engine.process.onPrctl = { command ->
                nativeCalls.incrementAndGet()
                LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
            }
            val installer = java.lang.invoke.MethodHandles.lookup().unreflect(
                holder.getDeclaredMethod("installInitialBaseline", Any::class.java, Policy::class.java),
            )
            assertFailsWith<IllegalStateException> { installer.invokeWithArguments(actual, Any(), Policy.NO_EXEC_HOTSPOT) }
            assertEquals(0, nativeCalls.get())
            assertFailsWith<IllegalAccessException> {
                java.lang.invoke.MethodHandles
                .lookup()
                .findGetter(holder, "permit", Any::class.java)
            }
            assertIs<ManagedRuntimeAcquisitionStatus.Active>(owner.status())
            val domain = runtime.prepare(policy(), DomainLimits(1, 1))
            assertEquals(
                42,
                runtime
                .submit(domain, Callable { 42 })
                .result()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS),
            )
        } finally {
            owner.close()
        }
    }

    @Test
    fun `synthetic canonical constructor refuses a foreign permit before bootstrap`() {
        val canonical = ManagedRuntime.newCanonicalAcquisition()
        val ctor = ManagedRuntime::class.java.getConstructor(
            canonical.javaClass,
            Any::class.java,
            Duration::class.java,
            Duration::class.java,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )
        val calls = AtomicInteger()
        engine.process.onPrctl = {
            calls.incrementAndGet()
            LinuxNative.SyscallResult.Success(0L)
        }
        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            ctor.newInstance(canonical, Any(), timeout, timeout, null)
        }
        assertIs<IllegalStateException>(failure.cause)
        assertEquals(0, calls.get())
    }

    @Test
    fun `owner retirement winning its gate prevents queued manager baseline dispatch`() {
        val calls = AtomicInteger()
        engine.process.onPrctl = { command ->
            calls.incrementAndGet()
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val owner = ManagedRuntimeAcquisitionOwner.create()
        val protocol = owner.javaClass
            .getDeclaredField("protocol")
            .apply { isAccessible = true }
            .get(owner)
        val gate = protocol.javaClass
            .getDeclaredField("gate")
            .apply { isAccessible = true }
            .get(protocol)
        synchronized(gate) {
            assertFailsWith<TimeoutException> { owner.start(Duration.ofMillis(100), Duration.ofMillis(100)) }
            val original = protocol.javaClass
                .getDeclaredField("original")
                .apply { isAccessible = true }
                .get(protocol)
            val managerRef = ManagedRuntime::class.java
                .getDeclaredField("managerThread")
                .apply { isAccessible = true }
                .get(original) as AtomicReference<*>
            val manager = managerRef.get() as Thread
            val threadBean = java.lang.management.ManagementFactory
                .getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var info = threadBean.getThreadInfo(manager.threadId())
            while (info?.threadState != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
                info = threadBean.getThreadInfo(manager.threadId())
            }
            assertTrue(manager.isAlive)
            assertEquals(Thread.State.BLOCKED, info?.threadState)
            assertEquals(System.identityHashCode(gate), info?.lockInfo?.identityHashCode)
            assertEquals(Thread.currentThread().threadId(), info?.lockOwnerId)
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofMillis(100)))
            assertEquals(0, calls.get())
        }
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.retire(timeout))
        assertEquals(0, calls.get())
    }

    @Test
    fun `runtime owner identity observes only the retained original`() {
        val calls = AtomicInteger()
        engine.process.onPrctl = { command ->
            calls.incrementAndGet()
            LinuxNative.SyscallResult.Success(if (command is PrctlCommand.GetSeccomp) 2L else 0L)
        }
        val first = ManagedRuntimeAcquisitionOwner.create()
        val second = ManagedRuntimeAcquisitionOwner.create()
        val fresh = ManagedRuntimeAcquisitionOwner.create()
        var marker: ManagedRuntime? = null
        try {
            val firstRuntime = first.start(timeout, timeout)
            val secondRuntime = second.start(timeout, timeout)
            marker = ManagedRuntime::class.java
                .getConstructor(
                RuntimeProfile::class.java,
                Duration::class.java,
                Duration::class.java,
                kotlin.jvm.internal.DefaultConstructorMarker::class.java,
            ).newInstance(RuntimeProfile.HOTSPOT_JIT, timeout, timeout, null)
            val firstStatus = first.status()
            val secondStatus = second.status()
            val freshStatus = fresh.status()
            val before = calls.get()
            repeat(3) {
                assertTrue(first.ownsRuntime(firstRuntime))
                assertFalse(first.ownsRuntime(secondRuntime))
                assertFalse(first.ownsRuntime(marker))
                assertFalse(first.ownsRuntime(null))
                assertTrue(second.ownsRuntime(secondRuntime))
                assertFalse(second.ownsRuntime(firstRuntime))
                assertFalse(fresh.ownsRuntime(firstRuntime))
                assertFalse(fresh.ownsRuntime(null))
            }
            val method = ManagedRuntimeAcquisitionOwner::class.java.getMethod("ownsRuntime", ManagedRuntime::class.java)
            assertEquals(false, method.invoke(first, null))
            assertEquals(firstStatus, first.status())
            assertEquals(secondStatus, second.status())
            assertEquals(freshStatus, fresh.status())
            assertEquals(before, calls.get())
            assertIs<ManagedRuntimeAcquisitionStatus.Complete>(first.retire(timeout))
            assertTrue(first.ownsRuntime(firstRuntime))
            assertFalse(first.ownsRuntime(secondRuntime))
            assertFalse(first.ownsRuntime(null))
            first.close()
            assertTrue(first.ownsRuntime(firstRuntime))
        } finally {
            try {
                first.retire(timeout)
            } finally {
                try {
                    second.retire(timeout)
                } finally {
                    try {
                        fresh.close()
                    } finally {
                        marker?.drain(timeout)
                    }
                }
            }
        }
    }
}
