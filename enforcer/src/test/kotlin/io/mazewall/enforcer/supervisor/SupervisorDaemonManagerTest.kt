package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.enforcer.api.MockProcess
import io.mazewall.enforcer.api.MockProcessLauncher
import io.mazewall.enforcer.api.MockSocketManager
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.readByte
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.socket.*
import io.mazewall.platform.socket.SocketManager
import io.mazewall.platform.syscall.*
import io.mazewall.seccomp.SeccompInstallationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class SupervisorDaemonManagerTest {
    @Test
    fun `canonical acquisition counts blocked helper startup then retains failure`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanup = AtomicInteger()
        val failure = IllegalArgumentException("controlled launch failure")
        val launcher = object : MockProcessLauncher() {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw failure
            }

            override fun deleteIfExists(path: Path): Boolean {
                cleanup.incrementAndGet()
                return super.deleteIfExists(path)
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf()),
        )
        val owner = ManagedRuntime.newCanonicalAcquisition()
        val state = owner.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
        state.set(owner, state.type.enumConstants.single { (it as Enum<*>).name == "STARTING" })
        val inFlight = owner.javaClass
            .getDeclaredField("setupInFlight")
            .apply { isAccessible = true }
        inFlight.setBoolean(owner, true)
        val actor = owner.javaClass
            .getDeclaredField("startActor")
            .apply { isAccessible = true }
        val start = owner.javaClass
            .getDeclaredMethod("startHelper", SupervisorDaemonManager::class.java)
            .apply { isAccessible = true }
        val observed = AtomicReference<Throwable>()
        val starter = Thread {
            actor.set(owner, Thread.currentThread())
            try {
                start.invoke(owner, manager)
            } catch (failure: InvocationTargetException) {
                observed.set(failure.cause)
            } finally {
                inFlight.setBoolean(owner, false)
            }
        }
        try {
            starter.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofSeconds(1)))
        } finally {
            release.countDown()
            starter.join(5000)
        }
        assertFalse(starter.isAlive)
        assertSame(failure, observed.get())
        val rollbackCalls = cleanup.get()
        assertTrue(rollbackCalls > 0)
        val unknown = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(1)))
        assertSame(failure, unknown.history)
        assertEquals(rollbackCalls, cleanup.get())
    }

    private fun startedCanonicalHelper(manager: SupervisorDaemonManager): ManagedRuntime.CanonicalAcquisition {
        val owner = ManagedRuntime.newCanonicalAcquisition()
        val state = owner.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
        state.set(owner, state.type.enumConstants.single { (it as Enum<*>).name == "STARTING" })
        owner.javaClass
            .getDeclaredField("startActor")
            .apply { isAccessible = true }
            .set(owner, Thread.currentThread())
        owner.javaClass
            .getDeclaredMethod("startHelper", SupervisorDaemonManager::class.java)
            .apply { isAccessible = true }
            .invoke(owner, manager)
        return owner
    }

    internal data class SyntheticHistoryHelper(
        val manager: SupervisorDaemonManager,
        val process: MockProcess,
        val context: SupervisorContext,
        val entered: CountDownLatch,
        val release: CountDownLatch,
        val stops: AtomicInteger,
    )

    // Test-only composition; the simulated process is not an OS-owned helper.
    internal fun attachSyntheticHistoryHelper(
        owner: ManagedRuntime.CanonicalAcquisition,
        relayContext: SupervisorContext,
    ): SyntheticHistoryHelper {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stops = AtomicInteger()
        val process = MockProcess(ProcessHandle.current().pid(), SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(process) {
            override fun removeShutdownHook(hook: Thread) {
                stops.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                super.removeShutdownHook(hook)
            }
        }
        val gone = AtomicBoolean(false)
        val helperPid = checkNotNull(relayContext.confinedHelper).identity.pid
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { this.process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(
                mutableListOf(),
                children = listOf(helperPid),
                helperGone = gone,
                helperPid = helperPid,
                daemonPid = Pid(process.pid().toInt()),
            ),
        )
        manager.onUnexpectedExit = {}
        val state = owner.javaClass
            .getDeclaredField("state")
            .apply { isAccessible = true }
        val prior = state.get(owner)
        state.set(owner, state.type.enumConstants.single { (it as Enum<*>).name == "STARTING" })
        owner.javaClass
            .getDeclaredField("startActor")
            .apply { isAccessible = true }
            .set(owner, Thread.currentThread())
        val previousProcessState = ContainmentStateRegistry.processState
        try {
            ContainmentStateRegistry.processState = ContainerState()
            owner.javaClass
                .getDeclaredMethod("startHelper", SupervisorDaemonManager::class.java)
                .apply { isAccessible = true }
                .invoke(owner, manager)
        } finally {
            ContainmentStateRegistry.processState = previousProcessState
            state.set(owner, prior)
        }
        val context = relayContext.copy(daemonProcess = process)
        val handleField = manager.javaClass
            .getDeclaredField("handleRef")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val handle = handleField.get(manager) as AtomicReference<Any>
        val running = handle
            .get()
            .javaClass
            .getDeclaredConstructor(SupervisorContext::class.java)
            .apply { isAccessible = true }
            .newInstance(context)
        handle.set(running)
        manager.javaClass
            .getDeclaredField("confinedOriginalContext")
            .apply { isAccessible = true }
            .set(manager, context)
        owner.javaClass
            .getDeclaredField("startedHelperContext")
            .apply { isAccessible = true }
            .set(owner, context)
        gone.set(true)
        return SyntheticHistoryHelper(manager, process, context, entered, release, stops)
    }

    @Test
    fun `canonical acquisition remains pending while started helper stop is in flight`() {
        val helperGone = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                super.removeShutdownHook(hook)
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf(), helperGone = helperGone),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        helperGone.set(true)
        val result = AtomicReference<ManagedRuntimeAcquisitionStatus>()
        val retire = Thread { result.set(owner.retire(Duration.ofSeconds(5))) }
        try {
            retire.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.status())
            assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.retire(Duration.ofSeconds(1)))
        } finally {
            release.countDown()
            retire.join(5000)
        }
        assertFalse(retire.isAlive)
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(result.get())
    }

    @Test
    fun `canonical publication waits for manager termination and helper stop`() {
        val helperGone = AtomicBoolean(false)
        val stops = AtomicInteger()
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                stops.incrementAndGet()
                super.removeShutdownHook(hook)
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf(), helperGone = helperGone),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        for (name in listOf("returned", "finished")) {
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .setBoolean(owner, true)
        }
        val publish = owner.javaClass
            .getDeclaredMethod("publishRetirement", DrainReport::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        publish.invoke(owner, DrainReport.Complete, false)
        assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.status())
        assertEquals(0, stops.get())
        publish.invoke(owner, DrainReport.Complete, true)
        assertIs<ManagedRuntimeAcquisitionStatus.Pending>(owner.status())
        assertEquals(0, stops.get())
        helperGone.set(true)
        owner.javaClass
            .getDeclaredMethod("retireStartedHelper")
            .apply { isAccessible = true }
            .invoke(owner)
        assertEquals(1, stops.get())
        publish.invoke(owner, DrainReport.Complete, true)
        assertIs<ManagedRuntimeAcquisitionStatus.Complete>(owner.status())
    }

    @Test
    fun `canonical helper remains unknown when original helper is still observable`() {
        val stops = AtomicInteger()
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                stops.incrementAndGet()
                super.removeShutdownHook(hook)
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf()),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertEquals(1, stops.get())
    }

    @Test
    fun `canonical helper observation failure is unknown without stop replay`() {
        val deny = AtomicBoolean(false)
        val stops = AtomicInteger()
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                stops.incrementAndGet()
                super.removeShutdownHook(hook)
            }
        }
        val observations = bootstrapObservations(mutableListOf()) { path ->
            if (deny.get() && path == Path.of("/proc/556/stat")) throw java.nio.file.AccessDeniedException(path.toString())
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            observations,
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        deny.set(true)
        val first = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertIs<java.nio.file.AccessDeniedException>(first.history)
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertEquals(1, stops.get())
    }

    @Test
    fun `canonical helper malformed replacement identity remains unknown`() {
        val malformed = AtomicBoolean(false)
        val stops = AtomicInteger()
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                stops.incrementAndGet()
                super.removeShutdownHook(hook)
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf(), helperMalformed = malformed),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        malformed.set(true)
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertEquals(1, stops.get())
    }

    @Test
    fun `canonical helper retirement checks incarnation and proc view`() {
        for (scenario in listOf("zombie", "replacement", "view", "owner", "proc")) {
            val mode = AtomicReference("normal")
            val changed = AtomicBoolean(false)
            val stops = AtomicInteger()
            val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
            val launcher = object : MockProcessLauncher(child) {
                override fun removeShutdownHook(hook: Thread) {
                    stops.incrementAndGet()
                    super.removeShutdownHook(hook)
                }
            }
            val observations = bootstrapObservations(mutableListOf(), helperMode = mode) { path ->
                if (changed.get() && (path == Path.of("/proc/self/ns/pid") || path == Path.of("/proc"))) error("changed proc view")
            }
            val manager = SupervisorDaemonManager(
                MockNativeEngine().apply { process.tid = Tid(333) },
                MockSocketManager(),
                launcher,
                observations,
            )
            manager.onUnexpectedExit = {}
            val owner = startedCanonicalHelper(manager)
            child.setAlive(false)
            mode.set(scenario)
            changed.set(scenario == "view" || scenario == "proc")
            val result = owner.retire(Duration.ofSeconds(5))
            if (scenario == "replacement") {
                assertIs<ManagedRuntimeAcquisitionStatus.Complete>(result)
            } else {
                assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(result)
            }
            owner.retire(Duration.ofSeconds(5))
            assertEquals(1, stops.get())
        }
    }

    @Test
    fun `canonical acquisition retains failed helper stop without replay`() {
        val attempts = AtomicInteger()
        val failure = IllegalArgumentException("controlled hook removal failure")
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                attempts.incrementAndGet()
                throw failure
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf()),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        val first = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertSame(failure, first.history)
        assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertEquals(1, attempts.get())
    }

    @Test
    fun `canonical helper stop fatal remains unknown and releases retirement actor`() {
        val attempts = AtomicInteger()
        val fatal = AssertionError("controlled fatal hook removal")
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val launcher = object : MockProcessLauncher(child) {
            override fun removeShutdownHook(hook: Thread) {
                attempts.incrementAndGet()
                throw fatal
            }
        }
        val manager = SupervisorDaemonManager(
            MockNativeEngine().apply { process.tid = Tid(333) },
            MockSocketManager(),
            launcher,
            bootstrapObservations(mutableListOf()),
        )
        manager.onUnexpectedExit = {}
        val owner = startedCanonicalHelper(manager)
        child.setAlive(false)
        assertSame(fatal, assertFailsWith<AssertionError> { owner.retire(Duration.ofSeconds(5)) })
        val inFlight = owner.javaClass
            .getDeclaredField("retirementInFlight")
            .apply { isAccessible = true }
        assertFalse(inFlight.getBoolean(owner))
        val unknown = assertIs<ManagedRuntimeAcquisitionStatus.Unknown>(owner.retire(Duration.ofSeconds(5)))
        assertSame(fatal, unknown.history)
        assertEquals(1, attempts.get())
    }

    private fun bootstrapObservations(
        seen: MutableList<Path>,
        children: List<Pid> = listOf(Pid(556)),
        helperGone: AtomicBoolean = AtomicBoolean(false),
        helperMalformed: AtomicBoolean = AtomicBoolean(false),
        helperMode: AtomicReference<String> = AtomicReference("normal"),
        helperPid: Pid = Pid(556),
        daemonPid: Pid = Pid(555),
        change: (Path) -> Unit = {},
    ) = HelperSecurityObservation(
            namespaceKey = { path ->
                seen.add(path)
                change(path)
                "(dev=4,ino=5)"
            },
            readBounded = { path, _ ->
                seen.add(path)
                change(path)
                val pid = path.getName(1).toString().let { if (it == "self") ProcessHandle.current().pid().toInt() else it.toInt() }
                if (pid == helperPid.value && path.fileName.toString() == "stat" && helperGone.get()) {
                    throw java.nio.file.NoSuchFileException(path.toString())
                }
                when (path.fileName.toString()) {
                    "stat" ->
                        if (pid == 556 && helperMalformed.get()) {
                            "$pid (bootstrap) ? " + "0 ".repeat(18) + "18 0\n"
                        } else {
                            val state = if (pid == 556 && helperMode.get() == "zombie") "Z" else "S"
                            val start = if ((pid == 556 && helperMode.get() == "replacement") ||
                                (path == Path.of("/proc/self/stat") && helperMode.get() == "owner")
                            ) {
                                18
                            } else {
                                17
                            }
                            "$pid (bootstrap) $state " + "0 ".repeat(18) + "$start 0\n"
                        }
                    "status" ->
                        "Tgid:\t$pid\nPid:\t${path.parent.fileName}\nUid:\t1 1 1 1\nGid:\t2 2 2 2\nGroups:\t2\n" +
                        "CapInh:\t0000000000000000\nCapPrm:\t0000000000000000\nCapEff:\t0000000000000000\n" +
                        "CapBnd:\t0000000000000000\nCapAmb:\t0000000000000000\nNoNewPrivs:\t1\nSeccomp:\t2\nSeccomp_filters:\t1\n"
                    "timens_offsets" -> "monotonic 0 0\nboottime 0 0\n"
                    "uid_map", "gid_map" -> "0 0 4294967295\n"
                    "mountinfo" -> "23 1 0:1 / / rw - tmpfs tmpfs rw\n24 23 0:2 / /proc rw - proc proc rw\n"
                    else -> error("Unexpected observation $path")
                }.toByteArray()
            },
            childrenOf = { parent ->
                assertEquals(daemonPid, parent)
                children
            },
        )

    @Test
    fun `confined bootstrap selects private mode once and refuses legacy switch or replacement`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val seen = mutableListOf<Path>()
        var starts = 0
        val launcher = object : MockProcessLauncher(child) {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                starts++
                assertTrue(seen.any { it.toString().endsWith("/task/333/ns/time_for_children") })
                return super.startProcess(args, redirectErrorStream)
            }
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(seen))
        try {
            val first = manager.prepareConfinedReadBootstrap()
            assertEquals("--confined-read-v1", launcher.lastArgs!!.last())
            assertTrue(seen.any { it.toString() == "/proc/555/task/555/ns/time_for_children" })
            assertEquals(ProcessIdentity(Pid(556), 17), first.confinedHelper?.identity)
            assertTrue(seen.any { it.toString() == "/proc/556/task/556/ns/time_for_children" })
            kotlin.test.assertSame(first, manager.prepareConfinedReadBootstrap())
            assertThrows<IllegalStateException> { manager.getOrSpawnSharedDaemon() }
            ContainmentStateRegistry.processState = ContainerState(filterDepth = 1, engineState = SeccompInstallationState.Verified)
            assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() }
            ContainmentStateRegistry.processState = ContainerState()
            manager.stop()
            assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() }
            assertThrows<IllegalStateException> { manager.getOrSpawnSharedDaemon() }
            assertEquals(1, starts)
            kotlin.test.assertFalse(child.isAlive)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `confined bootstrap forwards canonical prewarm policy to its supervisor`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "015000")
            val native = MockNativeEngine().apply { process.tid = Tid(333) }
            val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
            val launcher = MockProcessLauncher(child)
            val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
            try {
                manager.prepareConfinedReadBootstrap()
                val args = requireNotNull(launcher.lastArgs)
                assertEquals(1, args.count { it.startsWith("-D$property=") })
                assertTrue(args.contains("-D$property=15000"))
                assertEquals("--confined-read-v1", args.last())
            } finally {
                manager.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `invalid confined prewarm policy prevents supervisor launch`() {
        val property = SupervisorPrewarmBudget.PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "15001")
            val native = MockNativeEngine().apply { process.tid = Tid(333) }
            val launcher = MockProcessLauncher(MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
            val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
            try {
                assertThrows<IllegalArgumentException> { manager.prepareConfinedReadBootstrap() }
                assertEquals(null, launcher.lastArgs)
            } finally {
                manager.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `confined hook that closes during commit prevents startup publication`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        val registered = AtomicBoolean()
        val checks = AtomicInteger()
        val commitCheck = CountDownLatch(1)
        val resumeStarter = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val hookRef = AtomicReference<Thread>()
        val outcome = AtomicReference<SupervisorContext>()
        val failure = AtomicReference<Throwable>()
        val hookFailure = AtomicReference<Throwable>()
        val source = ByteArrayInputStream((SupervisorDaemon.DAEMON_READY_SENTINEL + "\n").toByteArray())
        val stream = object : InputStream() {
            override fun read(): Int {
                val next = source.read()
                if (next >= 0) return next
                streamClosed.await(5, TimeUnit.SECONDS)
                return -1
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val count = source.read(buffer, offset, length)
                if (count >= 0) return count
                streamClosed.await(5, TimeUnit.SECONDS)
                return -1
            }

            override fun close() {
                streamClosed.countDown()
            }
        }
        lateinit var starter: Thread
        val child = object : MockProcess(555) {
            override fun getInputStream(): InputStream = stream

            override fun isAlive(): Boolean {
                val observed = super.isAlive()
                if (Thread.currentThread() === starter && registered.get() && checks.incrementAndGet() == 2) {
                    commitCheck.countDown()
                    check(resumeStarter.await(5, TimeUnit.SECONDS)) { "starter was not released" }
                }
                return observed
            }
        }
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                super.addShutdownHook(hook)
                hookRef.set(hook)
                registered.set(true)
            }
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        manager.onUnexpectedExit = {}
        starter = Thread {
            try {
                outcome.set(manager.prepareConfinedReadBootstrap())
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        try {
            starter.start()
            assertTrue(commitCheck.await(5, TimeUnit.SECONDS), "starter did not reach the commit check")
            val hook = checkNotNull(hookRef.get())
            hook.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, caught -> hookFailure.set(caught) }
            hook.start()
            hook.join(5_000)
            kotlin.test.assertFalse(hook.isAlive, "the original hook did not retire")
            kotlin.test.assertNull(hookFailure.get())
            kotlin.test.assertFalse(child.isAlive)
            resumeStarter.countDown()
            starter.join(5_000)
            kotlin.test.assertFalse(starter.isAlive, "starter did not finish")
            assertNotNull(failure.get(), "closed owner must not be published as a successful startup")
            kotlin.test.assertNull(outcome.get())
        } finally {
            resumeStarter.countDown()
            streamClosed.countDown()
            starter.join(5_000)
            hookRef.get()?.let { hook ->
                hook.join(5_000)
                launcher.removeShutdownHook(hook)
            }
            if (!starter.isAlive) manager.stop()
        }
    }

    @Test
    fun `confined registration failure retains and removes the exact dispatched hook once`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        var ptracerCalls = 0
        native.process.onPrctl = { command ->
            if (command is PrctlCommand.SetPtracer) ptracerCalls++
            LinuxNative.SyscallResult.Success(0L)
        }
        val registrationFailure = java.io.IOException("registration outcome is ambiguous")
        val removed = mutableListOf<Thread>()
        val deleted = mutableListOf<Path>()
        lateinit var dispatched: Thread
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                dispatched = hook
                super.addShutdownHook(hook)
                throw registrationFailure
            }

            override fun removeShutdownHook(hook: Thread) {
                removed.add(hook)
                super.removeShutdownHook(hook)
            }

            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        try {
            val observed = assertThrows<java.io.IOException> { manager.prepareConfinedReadBootstrap() }
            kotlin.test.assertSame(registrationFailure, observed)
            kotlin.test.assertFalse(child.isAlive)
            assertEquals(2, deleted.size)
            assertEquals(1, removed.size, "The originally dispatched hook must be removed once")
            kotlin.test.assertSame(dispatched, removed.single())
            assertTrue(launcher.shutdownHooks.isEmpty())
            manager.stop()
            assertEquals(1, removed.size)
            assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() }
            assertThrows<IllegalStateException> { manager.getOrSpawnSharedDaemon() }
            assertEquals(1, ptracerCalls)
            // Reactivate only the test double to probe the cached original object identity.
            child.setAlive(true)
            DaemonPtracer.authorize(native, child)
            assertEquals(2, ptracerCalls, "Failed startup must forget its original ptracer selection")
        } finally {
            try {
                manager.stop()
            } finally {
                DaemonPtracer.retire(native, child)
            }
        }
    }

    @Test
    fun `failed confined startup preserves a newer ptracer selection`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val newer = MockProcess(777, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val registrationFailure = java.io.IOException("registration outcome is ambiguous")
        var ptracerCalls = 0
        native.process.onPrctl = { command ->
            if (command is PrctlCommand.SetPtracer) ptracerCalls++
            LinuxNative.SyscallResult.Success(0L)
        }
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                DaemonPtracer.authorize(native, newer)
                super.addShutdownHook(hook)
                throw registrationFailure
            }
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        try {
            kotlin.test.assertSame(registrationFailure, assertThrows<java.io.IOException> { manager.prepareConfinedReadBootstrap() })
            kotlin.test.assertFalse(child.isAlive)
            assertTrue(launcher.shutdownHooks.isEmpty())
            assertEquals(2, ptracerCalls)
            DaemonPtracer.authorize(native, newer)
            assertEquals(2, ptracerCalls, "Retiring the original must preserve the newer selection")
        } finally {
            try {
                manager.stop()
            } finally {
                DaemonPtracer.retire(native, child)
                DaemonPtracer.retire(native, newer)
            }
        }
    }

    @Test
    fun `failed confined ptracer bookkeeping preserves fatal after child rollback`() {
        val process = MockNativeProcess().apply { tid = Tid(333) }
        val fatal = LinkageError("ptracer bookkeeping failed")
        var failBookkeeping = false
        val native = object : MockNativeEngine(process = process) {
            override val process: MockNativeProcess
                get() = if (failBookkeeping) throw fatal else super.process
        }
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val startupFailure = java.io.IOException("registration outcome is ambiguous")
        val deleted = mutableListOf<Path>()
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                super.addShutdownHook(hook)
                failBookkeeping = true
                throw startupFailure
            }

            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        try {
            val observed = assertThrows<LinkageError> { manager.prepareConfinedReadBootstrap() }
            kotlin.test.assertSame(fatal, observed)
            assertTrue(observed.suppressed.any { it === startupFailure })
            kotlin.test.assertFalse(child.isAlive)
            assertTrue(launcher.shutdownHooks.isEmpty())
            assertEquals(2, deleted.size)
        } finally {
            failBookkeeping = false
            try {
                manager.stop()
            } finally {
                DaemonPtracer.retire(native, child)
            }
        }
    }

    @Test
    fun `postcommit startup failure preserves the confined ptracer selection`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        var ptracerCalls = 0
        native.process.onPrctl = { command ->
            if (command is PrctlCommand.SetPtracer) ptracerCalls++
            LinuxNative.SyscallResult.Success(0L)
        }
        val postcommitFailure = IllegalStateException("postcommit liveness failed")
        val registered = AtomicBoolean()
        val checks = AtomicInteger()
        val closed = CountDownLatch(1)
        val source = ByteArrayInputStream((SupervisorDaemon.DAEMON_READY_SENTINEL + "\n").toByteArray())
        val stream = object : InputStream() {
            override fun read(): Int {
                val next = source.read()
                if (next >= 0) return next
                closed.await(5, TimeUnit.SECONDS)
                return -1
            }

            override fun close() {
                closed.countDown()
            }
        }
        val child = object : MockProcess(555) {
            override fun getInputStream(): InputStream = stream

            override fun isAlive(): Boolean {
                if (registered.get() && checks.incrementAndGet() == 3) throw postcommitFailure
                return super.isAlive()
            }
        }
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                super.addShutdownHook(hook)
                registered.set(true)
            }
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        try {
            kotlin.test.assertSame(postcommitFailure, assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() })
            assertEquals(1, ptracerCalls)
            DaemonPtracer.authorize(native, child)
            assertEquals(1, ptracerCalls, "A committed owner's selection must not be retired by startup failure handling")
        } finally {
            closed.countDown()
            try {
                manager.stop()
            } finally {
                DaemonPtracer.retire(native, child)
            }
        }
    }

    @Test
    fun `confined hook removal failure is retained without replay after independent cleanup`() {
        val native = MockNativeEngine().apply { process.tid = Tid(333) }
        val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val registrationFailure = java.io.IOException("registration outcome is ambiguous")
        val fatal = LinkageError("hook removal failed")
        val removed = mutableListOf<Thread>()
        val deleted = mutableListOf<Path>()
        val launcher = object : MockProcessLauncher(child) {
            override fun addShutdownHook(hook: Thread) {
                super.addShutdownHook(hook)
                throw registrationFailure
            }

            override fun removeShutdownHook(hook: Thread) {
                removed.add(hook)
                throw fatal
            }

            override fun deleteIfExists(path: Path): Boolean = deleted.add(path)
        }
        val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf()))
        val observed = assertThrows<LinkageError> { manager.prepareConfinedReadBootstrap() }
        kotlin.test.assertSame(fatal, observed)
        kotlin.test.assertFalse(child.isAlive)
        assertEquals(2, deleted.size)
        assertEquals(1, removed.size)
        kotlin.test.assertSame(fatal, assertThrows<LinkageError> { manager.stop() })
        assertEquals(1, removed.size)
        assertEquals(2, deleted.size)
    }

    @Test
    fun `bootstrap observation failure retires acquired daemon and never accepts its ready sentinel`() {
        for (task in listOf(333, 555, 556)) {
            val native = MockNativeEngine().apply { process.tid = Tid(333) }
            val child = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
            val launcher = MockProcessLauncher(child)
            val denied = java.nio.file.AccessDeniedException("controlled time domain")
            val observer = bootstrapObservations(mutableListOf()) { path ->
                if (path.toString().endsWith("/task/$task/ns/time_for_children")) throw denied
            }
            val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, observer)
            try {
                val failure = assertThrows<ConfinedHelperRejection> { manager.prepareConfinedReadBootstrap() }
                assertEquals(ConfinedRejectReason.Permission, failure.reason)
                assertEquals(13, failure.errno)
                kotlin.test.assertSame(denied, failure.cause)
                assertEquals(task != 333, launcher.startProcessCalled)
                if (launcher.startProcessCalled) kotlin.test.assertFalse(child.isAlive)
                assertTrue(launcher.shutdownHooks.isEmpty())
            } finally {
                manager.stop()
            }
        }
    }

    @Test
    fun `confined bootstrap refuses missing or ambiguous acquired helper identities`() {
        for (children in listOf(emptyList(), listOf(Pid(556), Pid(557)))) {
            val native = MockNativeEngine().apply { process.tid = Tid(333) }
            val daemon = MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
            val launcher = MockProcessLauncher(daemon)
            val manager = SupervisorDaemonManager(native, MockSocketManager(), launcher, bootstrapObservations(mutableListOf(), children))
            try {
                val failure = assertThrows<ConfinedHelperRejection> { manager.prepareConfinedReadBootstrap() }
                assertEquals(ConfinedRejectReason.Ancestry, failure.reason)
                kotlin.test.assertFalse(daemon.isAlive)
                assertTrue(launcher.shutdownHooks.isEmpty())
                assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() }
            } finally {
                manager.stop()
            }
        }
    }

    @Test
    fun `confined bootstrap cannot upgrade a running legacy daemon`() {
        val launcher = MockProcessLauncher(MockProcess(555, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(MockNativeEngine(), MockSocketManager(), launcher)
        try {
            val legacy = manager.getOrSpawnSharedDaemon()
            assertThrows<IllegalStateException> { manager.prepareConfinedReadBootstrap() }
            kotlin.test.assertSame(legacy, manager.getOrSpawnSharedDaemon())
            kotlin.test.assertFalse("--confined-read-v1" in launcher.lastArgs!!)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `default shutdown transport remains bound to lifecycle owner`() {
        val owner = MockNativeEngine()
        val ownedOpens = AtomicInteger()
        val ownedCloses = AtomicInteger()
        owner.networking.onSocket = { _, _, _ ->
            ownedOpens.incrementAndGet()
            LinuxNative.SyscallResult.Success(1000L)
        }
        owner.fileSystem.onClose = {
            ownedCloses.incrementAndGet()
            LinuxNative.SyscallResult.Success(0L)
        }
        val manager = SupervisorDaemonManager(
            engine = owner,
            processLauncher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")),
        )
        val foreignCalls = AtomicInteger()
        val foreign = MockNativeEngine().apply {
            networking.onSocket = { _, _, _ ->
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(13, -1L)
            }
        }
        try {
            manager.getOrSpawnSharedDaemon()
            LinuxNative.setEngine(foreign)
            manager.stop()
            assertEquals(0, foreignCalls.get())
            assertEquals(1, ownedOpens.get())
            assertEquals(1, ownedCloses.get())
        } finally {
            manager.stop()
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `default lifecycle owner survives global replacement before startup`() {
        val owner = MockNativeEngine()
        LinuxNative.setEngine(owner)
        val manager = SupervisorDaemonManager(
            socketManager = MockSocketManager(),
            processLauncher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")),
        )
        val foreignCalls = AtomicInteger()
        LinuxNative.setEngine(
            MockNativeEngine().apply {
            process.onPrctl = {
                foreignCalls.incrementAndGet()
                LinuxNative.SyscallResult.Error(1, -1L)
            }
        },
        )
        try {
            assertEquals(9999L, manager.getOrSpawnSharedDaemon().daemonProcess.pid())
            manager.stop()
            assertEquals(0, foreignCalls.get())
        } finally {
            manager.stop()
            LinuxNative.resetToDefault()
        }
    }

    @Test
    fun `cached daemon reacquires ptrace authority after another daemon took it`() {
        val engine = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
        var ptracer = 0L
        engine.process.onPrctl = { command ->
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer) ptracer = command.tracerPid
            LinuxNative.SyscallResult.Success(0L)
        }
        try {
            val original = manager.getOrSpawnSharedDaemon()
            io.mazewall.platform.process.DaemonPtracer
                .authorize(engine, MockProcess(12345L))
            val reused = manager.getOrSpawnSharedDaemon()
            assertEquals(original, reused)
            assertEquals(9999L, ptracer, "cached supervisor must regain authority before use")
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `cached daemon is not returned when restoring ptrace authority fails`() {
        val engine = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(engine, MockSocketManager(), launcher)
        manager.getOrSpawnSharedDaemon()
        io.mazewall.platform.process.DaemonPtracer
            .authorize(engine, MockProcess(12345L))
        engine.process.onPrctl = { LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
        try {
            val failure = assertThrows<IllegalStateException> { manager.getOrSpawnSharedDaemon() }
            assertTrue(failure.message!!.contains("errno=1"))
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `cached daemon authorizes supplied caller authority without using lifecycle backend`() {
        val owner = MockNativeEngine()
        val launcher = MockProcessLauncher(MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n"))
        val manager = SupervisorDaemonManager(owner, MockSocketManager(), launcher)
        val caller = MockNativeEngine()
        var authorized = 0L
        caller.process.onPrctl = { command ->
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer) authorized = command.tracerPid
            LinuxNative.SyscallResult.Success(0L)
        }
        try {
            val original = manager.getOrSpawnSharedDaemon()
            owner.process.onPrctl = { LinuxNative.SyscallResult.Error(NativeConstants.EPERM, -1L) }
            val reused = manager.getOrSpawnSharedDaemon(caller)
            assertEquals(original, reused)
            assertEquals(9999L, authorized)
        } finally {
            owner.process.onPrctl = { LinuxNative.SyscallResult.Success(0L) }
            manager.stop()
        }
    }

    @AfterEach
    fun resetContainmentState() {
        ContainmentStateRegistry.processState = ContainerState()
        ContainmentStateRegistry.threadState = ContainerState()
    }

    @Test
    fun `spawn is allowed when only an outer OCI seccomp profile is present`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), mockLauncher)

        mockEngine.process.onPrctl = {
            LinuxNative.SyscallResult.Success(2L)
        }

        val context = manager.getOrSpawnSharedDaemon()
        assertTrue(mockLauncher.startProcessCalled)
        assertEquals(9999L, context.daemonProcess.pid())
    }

    @Test
    fun `spawn is refused after this JVM applied a mazewall filter`() {
        ContainmentStateRegistry.processState =
            ContainerState(filterDepth = 1, engineState = SeccompInstallationState.Verified)
        val mockEngine = MockNativeEngine()
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), MockProcessLauncher())

        val ex =
            assertThrows<IllegalStateException> {
                manager.getOrSpawnSharedDaemon()
            }
        assertTrue(ex.message!!.contains("mazewall seccomp"))
    }

    @Test
    fun `getOrSpawnSharedDaemon spawns daemon and sets ptracer`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)

        var prctlCalled = false
        mockEngine.process.onPrctl = { command ->
            if (command is io.mazewall.platform.syscall.PrctlCommand.SetPtracer && command.tracerPid == 9999L) {
                prctlCalled = true
            }
            LinuxNative.SyscallResult.Success(0L)
        }

        val context = manager.getOrSpawnSharedDaemon()

        assertNotNull(context)
        assertEquals(9999L, context.daemonProcess.pid())
        assertTrue(mockLauncher.startProcessCalled)
        assertTrue(prctlCalled, "prctl(PR_SET_PTRACER) should be called with daemon PID")
    }

    @Test
    fun `stop cleans up daemon and triggers shutdown`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)
        manager.getOrSpawnSharedDaemon()

        var writeCalledWithShutdown = false
        mockEngine.memory.onWrite = { _, buf, count ->
            if (count == 1L) {
                val byte = buf.readByte(0L)
                if (byte == 0x53.toByte()) { // 'S'
                    writeCalledWithShutdown = true
                }
            }
            LinuxNative.SyscallResult.Success(count)
        }

        manager.stop()

        assertTrue(mockSocket.connectCalled)
        assertTrue(writeCalledWithShutdown, "Should write shutdown command to daemon socket")
        assertEquals(1, mockSocket.closeCalledCount.get())
    }

    @Test
    fun `detects unexpected daemon exit and invokes handler`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = MockProcessLauncher()
        val output = java.io.PipedInputStream()
        val writer = java.io.PipedOutputStream(output)
        writer.write((SupervisorDaemon.DAEMON_READY_SENTINEL + "\n").toByteArray())
        val exitWaitEntered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val mockProcess = object : MockProcess(9999L, exitVal = 42) {
            override fun getInputStream(): java.io.InputStream = output

            override fun waitFor(): Int {
                exitWaitEntered.countDown()
                exited.await()
                return 42
            }
        }
        mockLauncher.mockProcess = mockProcess
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)

        val exitLatch = CountDownLatch(1)
        val exitCodeReceived = java.util.concurrent.atomic
            .AtomicInteger(-1)
        manager.onUnexpectedExit = { exitCode ->
            exitCodeReceived.set(exitCode)
            exitLatch.countDown()
        }

        val context = manager.getOrSpawnSharedDaemon()
        assertNotNull(context)
        writer.close()
        try {
            assertTrue(exitWaitEntered.await(2, TimeUnit.SECONDS), "Output EOF must still observe later process death")
        } finally {
            mockProcess.setAlive(false)
            exited.countDown()
        }

        val completed = exitLatch.await(5, TimeUnit.SECONDS)
        assertTrue(completed, "Expected unexpected exit handler to be invoked")
        assertEquals(42, exitCodeReceived.get())
        manager.stop()
    }

    @Test
    fun `dead daemon transitions once and is replaced on the next lookup`() {
        val mockEngine = MockNativeEngine()
        val first = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val second = MockProcess(10000L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockLauncher = object : MockProcessLauncher(first) {
            var starts = 0

            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
            ): Process {
                starts++
                return super.startProcess(args, redirectErrorStream)
            }
        }
        val manager = SupervisorDaemonManager(mockEngine, MockSocketManager(), mockLauncher)
        val exits = AtomicInteger(0)
        manager.onUnexpectedExit = { exits.incrementAndGet() }

        val initial = manager.getOrSpawnSharedDaemon()
        first.setAlive(false)
        mockLauncher.mockProcess = second

        val replacement = manager.getOrSpawnSharedDaemon()

        assertEquals(9999L, initial.daemonProcess.pid())
        assertEquals(10000L, replacement.daemonProcess.pid())
        assertEquals(2, mockLauncher.starts)
        assertEquals(1, exits.get(), "a dead daemon must transition to Defunct exactly once")
    }

    @Test
    fun `spawnDaemon falls back to short temp directory when default socket path is too long`() {
        val mockEngine = MockNativeEngine()
        val mockLauncher = object : MockProcessLauncher() {
            override fun createTempDirectory(
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                // Return an excessively long path that exceeds 107 bytes when suffix and /supervisor.sock is appended
                return java.nio.file.Paths
                    .get("/" + "a".repeat(120))
            }

            override fun createTempDirectory(
                dir: Path,
                prefix: String,
                vararg attrs: FileAttribute<*>,
            ): Path {
                return java.nio.file.Paths
                    .get("/tmp/fallback-mock-dir")
            }
        }
        mockLauncher.mockProcess = MockProcess(9999L, SupervisorDaemon.DAEMON_READY_SENTINEL + "\n")
        val mockSocket = MockSocketManager()

        val manager = SupervisorDaemonManager(mockEngine, mockSocket, mockLauncher)
        val context = manager.getOrSpawnSharedDaemon()

        try {
            assertNotNull(context)
            val pathBytes = context.socketPath.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            assertTrue(pathBytes.size < 108, "Fallback path should be under 108 bytes: ${context.socketPath}")
            assertEquals("/tmp/fallback-mock-dir/supervisor.sock", context.socketPath)
        } finally {
            manager.stop()
        }
    }
}
