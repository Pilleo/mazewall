package io.mazewall.landlock

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult
import io.mazewall.enforcer.internal.supervisor.SupervisorOwnedFd
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.OwnedProcFdReopen
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readLong
import io.mazewall.ffi.memory.readString
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Public only for JUnit discovery; restrictions are installed exclusively in disposable children. */
class OwnedProcFdReopenKernelTest {
    private enum class KernelCase { BASELINE, ALLOWED, FORBIDDEN, RENAME_REPLACE, ROOT_RENAME_REPLACE, UNLINK, MOVE_OUTSIDE, NONREGULAR, ESCAPES }

    private enum class Barrier { AFTER_ACQUIRE, AFTER_METADATA }

    private enum class MetadataStage { PRE, POST_PIN, POST_READABLE }

    @Test
    fun inventoryFailsClosedForLiveDescriptorErrors() {
        val fd = Path.of("/proc/self/fd/0")
        val target = Files.readSymbolicLink(fd)
        listOf(AccessDeniedException("live fdinfo"), IOException("unexpected fdinfo"), AssertionError("fatal fdinfo"), NoSuchFileException("fdinfo missing but FD still live")).forEach { failure ->
            val receipts = mutableListOf<String>()
            val observed = runCatching {
                descriptorInventory(target, receipts, descriptors = { listOf(fd) }, readInfo = { throw failure })
            }.exceptionOrNull()
            assertSame(failure, observed)
            assertTrue(receipts.isEmpty())
        }
        listOf("flags:\tbroken\n", "missing flags\n").forEach { info ->
            val receipts = mutableListOf<String>()
            assertThrows(RuntimeException::class.java) {
                descriptorInventory(target, receipts, descriptors = { listOf(fd) }, readInfo = { info })
            }
            assertTrue(receipts.isEmpty())
        }
        assertThrows(NumberFormatException::class.java) {
            descriptorInventory(Path.of("not-the-live-target"), mutableListOf(), descriptors = { listOf(fd) }, readInfo = { "flags:\tbroken\n" })
        }
    }

    @Test
    fun inventoryRecordsOnlyVanishedDescriptors() {
        val fd = Path.of("/proc/self/fd/vanished")
        val missing = NoSuchFileException(fd.toString())
        val receipts = mutableListOf<String>()
        descriptorInventory(Path.of("forbidden"), receipts, descriptors = { listOf(fd) }, readLink = { throw missing })
        assertEquals(listOf("INVENTORY_TRANSIENT fd=vanished cause=$missing"), receipts)
        val live = Path.of("/proc/self/fd/0")
        val target = Files.readSymbolicLink(live)
        assertThrows(IllegalStateException::class.java) {
            descriptorInventory(target, mutableListOf(), descriptors = { listOf(live) }, readInfo = { "flags:\t0\n" })
        }
        val allowed = mutableListOf<String>()
        descriptorInventory(target, allowed, descriptors = { listOf(live) }, readInfo = { "flags:\t${NativeConstants.O_PATH.toString(8)}\n" })
        assertTrue(allowed.single().startsWith("INHERITED_OR_SETUP fd=0 "))
    }

    @Test
    fun workerRetirementPreservesOperationAndCleanup() {
        listOf(
            IOException("operation") to IOException("retirement"),
            IOException("operation only") to null,
            null to IOException("cleanup only"),
            AssertionError("fatal operation") to IOException("retirement"),
            IOException("operation") to AssertionError("fatal retirement"),
        ).forEach { (operationFailure, cleanupFailure) ->
            val worker = Thread.ofPlatform().start {}
            var released = false
            var retired = false
            try {
                val observed = runCatching {
                    awaitWorker(worker, release = { released = true }, retired = {
                        assertFalse(worker.isAlive)
                        retired = true
                    }, join = {
                        worker.join(10_000)
                        cleanupFailure?.let { throw it }
                    }) { operationFailure?.let { throw it } }
                }.exceptionOrNull()
                val expected = if (cleanupFailure is Error && operationFailure !is Error) cleanupFailure else operationFailure ?: cleanupFailure
                assertSame(expected, observed)
                if (operationFailure != null && cleanupFailure != null) {
                    val secondary = if (expected === operationFailure) cleanupFailure else operationFailure
                    assertTrue(checkNotNull(observed).suppressed.any { it === secondary })
                }
                assertTrue(released && retired)
            } finally {
                worker.join(10_000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test
    fun interruptedWorkerRetirementRetainsLiveOwnersAndCoordinatorEvidence() {
        assertFalse(Thread.currentThread().isInterrupted)
        val gate = CountDownLatch(1)
        val worker = Thread.ofPlatform().start { check(gate.await(10, TimeUnit.SECONDS)) }
        val operationFailure = TimeoutException("operation timeout")
        val interrupted = InterruptedException("retirement interrupted")
        var closed = 0
        var retired = false
        val resources = listOf(AutoCloseable { closed++ })
        val receipts = mutableListOf("COORDINATOR measured-stage-pending")
        try {
            val observed = runCatching {
                awaitWorker(worker, release = {}, retired = { retired = true }, join = { throw interrupted }) { throw operationFailure }
            }.exceptionOrNull()
            assertSame(operationFailure, observed)
            assertTrue(checkNotNull(observed).suppressed.any { it === interrupted })
            assertTrue(Thread.currentThread().isInterrupted)
            assertSame(operationFailure, retireSetupOwners(worker, resources, receipts, observed))
            assertEquals(0, closed)
            assertFalse(retired)
            assertEquals(listOf("COORDINATOR measured-stage-pending", "OWNERS_RETAINED worker-alive=true child-process-retirement-required"), receipts)
        } finally {
            Thread.interrupted()
            gate.countDown()
            worker.join(10_000)
            assertFalse(worker.isAlive)
        }
        assertSame(operationFailure, retireSetupOwners(worker, resources, receipts, operationFailure))
        assertEquals(1, closed)
    }

    private enum class FifoStream { INPUT, ERROR, OUTPUT }

    private class ControlledFifo(
        private val operationFailure: Throwable? = null,
        private val retirementFailure: Throwable? = null,
        private val closeFailures: Map<FifoStream, Throwable> = emptyMap(),
    ) : Process() {
        val closed = mutableListOf<FifoStream>()
        private var waits = 0
        private val input = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() = closeStream(FifoStream.INPUT)
        }
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() = closeStream(FifoStream.ERROR)
        }
        private val output = object : ByteArrayOutputStream() {
            override fun close() = closeStream(FifoStream.OUTPUT)
        }

        private fun closeStream(stream: FifoStream) {
            closed += stream
            closeFailures[stream]?.let { throw it }
        }

        override fun getInputStream() = input

        override fun getErrorStream() = error

        override fun getOutputStream() = output

        override fun isAlive() = false

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            check(timeout > 0)
            waits++
            val failure = if (waits == 1) operationFailure else retirementFailure
            failure?.let { throw it }
            return true
        }

        override fun waitFor(): Int = error("Unbounded FIFO wait")

        override fun exitValue() = 0

        override fun destroy() = Unit
    }

    @Test
    fun fifoRetirementPreservesFailuresAndInterruption() {
        listOf(
            IOException("operation") to IOException("retirement"),
            IOException("operation only") to null,
            null to IOException("cleanup only"),
            null to null,
            AssertionError("fatal operation") to IOException("retirement"),
            IOException("operation") to AssertionError("fatal retirement"),
            IOException("operation") to InterruptedException("retirement interrupted"),
            InterruptedException("operation interrupted") to null,
        ).forEach { (operationFailure, cleanupFailure) ->
            val fifo = ControlledFifo(operationFailure, cleanupFailure)
            try {
                val observed = runCatching { awaitFifo(fifo) }.exceptionOrNull()
                val expected = if (cleanupFailure is Error && operationFailure !is Error) cleanupFailure else operationFailure ?: cleanupFailure
                assertSame(expected, observed)
                if (operationFailure != null && cleanupFailure != null) {
                    val secondary = if (expected === operationFailure) cleanupFailure else operationFailure
                    assertTrue(checkNotNull(observed).suppressed.any { it === secondary })
                }
                assertEquals(FifoStream.entries, fifo.closed)
                assertEquals(operationFailure is InterruptedException || cleanupFailure is InterruptedException, Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun fifoStreamFailuresNeverPreventLaterCloses() {
        val original = IOException("FIFO operation")
        val first = IOException("input close")
        val fatal = AssertionError("error close")
        val last = IOException("output close")
        val fifo = ControlledFifo(original, closeFailures = mapOf(FifoStream.INPUT to first, FifoStream.ERROR to fatal, FifoStream.OUTPUT to last))
        val observed = runCatching { awaitFifo(fifo) }.exceptionOrNull()
        assertSame(fatal, observed)
        assertEquals(FifoStream.entries, fifo.closed)
        assertTrue(fatal.suppressed.any { it === original })
        assertTrue(original.suppressed.any { it === first })
        assertTrue(fatal.suppressed.any { it === last })
    }

    @Test
    fun setupOwnerClosesAreIndependentAndFatalFirst() {
        val original = IOException("operation")
        val ordinary = IOException("last owner close")
        val fatal = AssertionError("middle owner close")
        val closed = mutableListOf<Int>()
        val resources = listOf(
            AutoCloseable {
                closed += 1
                throw ordinary
            },
            AutoCloseable {
                closed += 2
                throw fatal
            },
            AutoCloseable { closed += 3 },
        )
        val observed = retireSetupOwners(null, resources, mutableListOf(), original)
        assertSame(fatal, observed)
        assertEquals(listOf(3, 2, 1), closed)
        assertTrue(fatal.suppressed.any { it === original })
        assertTrue(fatal.suppressed.any { it === ordinary })
    }

    @Test
    fun `owned regular objects reopen under real worker Landlock`() {
        val base = Path.of("build/owned-proc-fd-reopen-fixtures").toAbsolutePath()
        Files.createDirectories(base)
        KernelCase.entries.forEach { case ->
            val root = Files.createTempDirectory(base, "${case.name.lowercase()}-")
            println("RETAINED fixture=$root case=$case")
            IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyKernelCase", case.name, root.toString())
            println("CHILD_RETIRED case=$case fixture=$root")
        }
    }

    private fun verifyKernelCase(
        caseName: String,
        fixtureRoot: String,
    ) {
        val case = KernelCase.valueOf(caseName)
        val root = Path.of(fixtureRoot)
        val native = LinuxNative.snapshot()
        check(native.isRealEngine)
        val abi = Landlock.getAbiVersion()
        check(abi >= 1) { "Unqualified: fresh Landlock ABI=$abi" }
        environment(root, abi)
        val resources = mutableListOf<AutoCloseable>()
        val receipts = mutableListOf<String>()
        var activeWorker: Thread? = null
        val execution = runCatching {
            val apps = (0..1).map { index -> Files.createDirectory(root.resolve("application-$index")) }
            val outside = Files.createDirectory(root.resolve("outside"))
            apps.forEach { write(it.resolve("target"), "original-owned-object") }
            write(outside.resolve("target"), "forbidden-distinct-object")
            val keys = (apps.map { it.resolve("target") } + listOf(outside.resolve("target"))).map { path ->
                val attrs = attributes(path)
                check(attrs.isRegularFile && Files.isReadable(path))
                check(Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rw-------"))
                receipts += "DAC path=$path uid=${Files.getAttribute(path, "unix:uid")} mode=${Files.getPosixFilePermissions(path)} key=${attrs.fileKey()}"
                attrs.fileKey()
            }
            check(keys.distinct().size == keys.size)
            check(Files.readString(outside.resolve("target")) == "forbidden-distinct-object")
            check(apps.all { Files.readString(it.resolve("target")) == "original-owned-object" })
            val roots = apps.map { PreparedPath.open(it, native).also(resources::add) }
            val forbidden = PreparedPath.open(outside, native).also(resources::add)
            val proc = PreparedPath.open(Path.of("/proc/self/fd"), native).also(resources::add)
            val procOwner = SupervisorOwnedFd(proc.descriptor, native.fileSystem)
            val dev = PreparedPath.open(Path.of("/dev"), native).also(resources::add)
            val supportPaths = buildList {
                val modules = Path.of(System.getProperty("java.home"), "lib/modules")
                if (Files.exists(modules)) {
                    check(Files.isRegularFile(modules))
                    add(modules.toRealPath())
                }
                System.getProperty("java.class.path").split(File.pathSeparator).forEach { entry ->
                    val path = Path.of(entry).toRealPath()
                    if (Files.isDirectory(path)) {
                        receipts += "PRELOAD_ONLY class-directory=$path grant=NONE"
                    } else {
                        check(path.toString().endsWith(".jar") && Files.isRegularFile(path))
                        add(path)
                    }
                }
            }.distinct()
            val support = supportPaths.map { PreparedPath.open(it, native).also(resources::add) }
            support.forEach { receipts += "SUPPORT fd=${it.descriptor.value} identity=${it.identity} grant=READ_FILE" }
            (roots + listOf(forbidden, proc, dev)).forEach {
                receipts += "OPATH fd=${it.descriptor.value} identity=${it.identity}"
            }
            receipts += "proc-grant=NONE forbidden-grant=NONE dev-grant=NONE injection=not-exercised seccomp=not-installed"
            val nonregular = prepareNonregular(apps[0], roots[0].descriptor.value, resources, receipts)
            Files.createSymbolicLink(apps[0].resolve("final-link"), outside.resolve("target"))
            Files.createSymbolicLink(apps[0].resolve("middle-link"), outside)
            Files.createSymbolicLink(apps[0].resolve("dangling-link"), outside.resolve("absent"))
            Files.createSymbolicLink(apps[0].resolve("user-proc"), Path.of("/proc/self/fd/${forbidden.descriptor.value}"))
            val ruleset = NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val fd = when (val created = Landlock.tryCreateRuleset(Landlock.getFullAccessMask(abi), 0L, abi, native)) {
                        is LandlockFdOutcome.Ok -> created.fd
                        is LandlockFdOutcome.Err -> error("create ruleset errno=${created.errno}")
                    }
                    resources += SupervisorOwnedFd(fd, native.fileSystem)
                    val rules = LandlockRuleset<RulesetState.Building>(fd, native)
                    roots.forEach { addRule(rules, it, Landlock.LANDLOCK_ACCESS_FS_READ_FILE or Landlock.LANDLOCK_ACCESS_FS_READ_DIR) }
                    support.forEach { addRule(rules, it, Landlock.LANDLOCK_ACCESS_FS_READ_FILE) }
                    rules
                }
            }
            receipts += "RULESET fd=${ruleset.fd.value} abi=$abi handled=${Landlock.getFullAccessMask(abi)} net=0 application-grant=12 support-grant=4"
            // Exercise the exact local code paths before restricting directory-based class reads.
            val warm = Trace(native)
            transaction(warm, roots[0], procOwner, "target", "original-owned-object", false) {}
            rejectNonregular(warm, roots[0], procOwner, "directory")
            val warmProbe = OwnedProcFdReopen(warm.engine, warm::metadata)
            check(warmProbe.acquire(roots[0].descriptor, "final-link") is SupervisorDescriptorResult.Denied)
            listOf(
                "io.mazewall.landlock.LandlockRestrictOutcome\$Ok",
                "io.mazewall.landlock.LandlockRestrictOutcome\$Err",
                "io.mazewall.landlock.LandlockRuleset",
                "io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult\$Denied",
            ).forEach { Class.forName(it) }
            val barriers = ArrayBlockingQueue<Int>(1)
            val releases = ArrayBlockingQueue<Boolean>(1)
            val inspection = ArrayBlockingQueue<Int>(1)
            val inspectionRelease = ArrayBlockingQueue<Boolean>(1)
            val variants = if (isMutation(case)) Barrier.entries else listOf(Barrier.AFTER_ACQUIRE)
            val trace = Trace(native)
            // Construct this exact callable-reference class before denying output-directory reads.
            val escapeProbe = OwnedProcFdReopen(trace.engine, trace::metadata)
            descriptorInventory(outside.resolve("target"), receipts)
            val task = FutureTask {
                check(!Thread.currentThread().isVirtual)
                if (case != KernelCase.BASELINE) {
                    when (val installed = Landlock.tryEnforceRuleset(ruleset, processWide = false)) {
                        is LandlockRestrictOutcome.Ok -> trace.events += "INSTALLED thread=${Thread.currentThread().name} processWide=false"
                        is LandlockRestrictOutcome.Err -> error("restrict_self errno=${installed.errno}")
                    }
                }
                check(inspection.offer(native.process.gettid().value, 10, TimeUnit.SECONDS))
                check(inspectionRelease.poll(10, TimeUnit.SECONDS) == true) { "worker envelope inspection timeout" }
                when (case) {
                    KernelCase.BASELINE -> {
                        transaction(trace, roots[0], procOwner, "target", "original-owned-object", false) {}
                        transaction(trace, forbidden, procOwner, "target", "forbidden-distinct-object", false) {}
                    }
                    KernelCase.ALLOWED -> transaction(trace, roots[0], procOwner, "target", "original-owned-object", false) {}
                    KernelCase.FORBIDDEN -> transaction(trace, forbidden, procOwner, "target", "forbidden-distinct-object", true) {}
                    KernelCase.RENAME_REPLACE, KernelCase.ROOT_RENAME_REPLACE, KernelCase.UNLINK, KernelCase.MOVE_OUTSIDE -> {
                        variants.forEachIndexed { index, barrier ->
                            trace.events += "VARIANT $case $barrier"
                            var signalled = false
                            val mutate = {
                                check(!signalled)
                                signalled = true
                                check(barriers.offer(index, 10, TimeUnit.SECONDS))
                                check(releases.poll(10, TimeUnit.SECONDS) == true) { "mutation release timeout" }
                            }
                            trace.afterFirstMetadata = if (barrier == Barrier.AFTER_METADATA) mutate else null
                            transaction(trace, roots[index], procOwner, "target", "original-owned-object", false, case == KernelCase.UNLINK, true) {
                                if (barrier == Barrier.AFTER_ACQUIRE) mutate()
                            }
                            check(signalled)
                            trace.afterFirstMetadata = null
                        }
                    }
                    KernelCase.NONREGULAR -> {
                        nonregular.forEach { rejectNonregular(trace, roots[0], procOwner, it) }
                        rejectNonregular(trace, dev, procOwner, "null")
                        trace.events += "UNQUALIFIED block-device: no safe provisioned fixture"
                    }
                    KernelCase.ESCAPES -> {
                        listOf(
                            "../outside/target",
                            outside.resolve("target").toString(),
                            "../application-0-sibling/target",
                            "final-link",
                            "middle-link/target",
                            "dangling-link",
                            "user-proc/target",
                        ).forEach { suffix ->
                            val before = trace.reopens
                            try {
                                when (val result = escapeProbe.acquire(roots[0].descriptor, suffix)) {
                                    is SupervisorDescriptorResult.Denied -> check(result.errno == 40 || result.errno == 18) { "escape errno=${result.errno}" }
                                    is SupervisorDescriptorResult.Opened -> result.descriptor.use { error("Escape acquired: $suffix") }
                                }
                            } catch (rejected: IllegalArgumentException) {
                                check(suffix.startsWith('/') || ".." in suffix.split('/'))
                                trace.events += "LEXICAL_REJECT suffix=$suffix cause=$rejected"
                            }
                            check(trace.reopens == before)
                        }
                        when (val magic = escapeProbe.acquire(proc.descriptor, "${forbidden.descriptor.value}/target")) {
                            is SupervisorDescriptorResult.Denied -> check(magic.errno == 40 || magic.errno == 18) { "proc magic-link errno=${magic.errno}" }
                            is SupervisorDescriptorResult.Opened -> magic.descriptor.use { error("User proc magic link acquired") }
                        }
                        check(trace.reopens == 0 && trace.readable == 0 && trace.reads == 0)
                        trace.events += "UNQUALIFIED bind-mount/mount-race: no separately provisioned fixture; resolve=0x0f asserted by host tests"
                    }
                }
                check(trace.live.isEmpty()) { "Measured descriptors remain live: ${trace.live}" }
            }
            val worker = Thread.ofPlatform().name("owned-proc-fd-landlock").unstarted(task)
            activeWorker = worker
            worker.start()
            awaitWorker(worker, release = {
                inspectionRelease.offer(true)
                releases.offer(true)
            }, retired = {
                receipts += trace.events.toList()
                receipts += "WORKER_RETIRED case=$case acquires=${trace.acquires} reopens=${trace.reopens} readable=${trace.readable} reads=${trace.reads} closes=${trace.closes} live=${trace.live}"
            }) {
                val tid = inspection.poll(10, TimeUnit.SECONDS)
                if (tid == null && task.isDone) task.get(10, TimeUnit.SECONDS)
                check(tid != null) { "worker installation/inspection barrier timeout" }
                val status = Files.readString(Path.of("/proc/self/task/$tid/status"))
                if (case != KernelCase.BASELINE) {
                    check(status.lineSequence().any { it.startsWith("NoNewPrivs:") && it.substringAfter(':').trim() == "1" })
                }
                receipts += "WORKER_ENVELOPE tid=$tid status=$status"
                val lsm = runCatching { Files.readString(Path.of("/proc/self/task/$tid/attr/current")) }
                receipts += "WORKER_LSM tid=$tid receipt=${lsm.fold({ it }, { "UNREADABLE $it" })}"
                check(inspectionRelease.offer(true, 10, TimeUnit.SECONDS))
                if (isMutation(case)) {
                    variants.forEachIndexed { index, barrier ->
                        val ready = barriers.poll(10, TimeUnit.SECONDS)
                        if (ready == null && task.isDone) task.get(10, TimeUnit.SECONDS)
                        check(ready == index) { "mutation acquisition/metadata barrier timeout $case $barrier" }
                        mutate(case, apps[index], outside, index)
                        receipts += "MUTATED case=$case variant=$barrier"
                        check(releases.offer(true, 10, TimeUnit.SECONDS))
                    }
                }
                task.get(10, TimeUnit.SECONDS)
            }
        }
        val failure = retireSetupOwners(activeWorker, resources, receipts, execution.exceptionOrNull())
        receipts.forEach(::println)
        println("RETIREMENT case=$case worker-alive=${activeWorker?.isAlive} resources=${resources.size} fixture=$root failure=$failure")
        failure?.let { throw it }
        println("PASS case=$case worker-and-owners-retired=true")
    }

    private fun awaitWorker(
        worker: Thread,
        release: () -> Unit,
        retired: () -> Unit,
        join: () -> Unit = { worker.join(10_000) },
        operation: () -> Unit,
    ) {
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null

        fun attempt(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { caught ->
                if (caught is InterruptedException) interrupted = true
                failure = CleanupFailures.merge(failure, caught)
            }
            interrupted = Thread.interrupted() || interrupted
        }
        try {
            attempt {
                if (interrupted) throw InterruptedException("Worker await was already interrupted")
                operation()
            }
            attempt(release)
            attempt(join)
            if (worker.isAlive) {
                attempt { error("Worker retirement timeout") }
            } else {
                // Termination detection publishes the trace even if join itself failed.
                attempt(retired)
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    private fun retireSetupOwners(
        worker: Thread?,
        resources: List<AutoCloseable>,
        receipts: MutableList<String>,
        original: Throwable?,
    ): Throwable? {
        var failure = original
        if (worker?.isAlive != true) {
            resources.asReversed().forEach { owner ->
                runCatching { owner.close() }.exceptionOrNull()?.let { cleanup ->
                    failure = CleanupFailures.merge(failure, cleanup)
                }
            }
        } else {
            receipts += "OWNERS_RETAINED worker-alive=true child-process-retirement-required"
        }
        return failure
    }

    context(arena: NativeArena) private fun addRule(
        rules: LandlockRuleset<RulesetState.Building>,
        path: PreparedPath,
        mask: Long,
    ) {
        when (val added = Landlock.addRuleToRuleset(rules, path.descriptor, mask)) {
            Landlock.AddRuleResult.Success -> Unit
            is Landlock.AddRuleResult.Error -> error("add rule ${path.requested} errno=${added.errno}")
        }
    }

    private class Trace(
        native: NativeEngine,
    ) {
        val events = mutableListOf<String>()
        val live = mutableSetOf<Int>()
        var acquires = 0
        var reopens = 0
        var readable = 0
        var reads = 0
        var closes = 0
        var afterFirstMetadata: (() -> Unit)? = null
        var metadataCalls = 0
            private set
        var pinnedKey: Any? = null
            private set

        fun beginMetadata() {
            metadataCalls = 0
            pinnedKey = null
        }

        val engine = object : NativeEngine by native {
            override val fileSystem = object : NativeFileSystem by native.fileSystem {
                override fun openat2(
                    dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    path: ManagedSegment,
                    how: ManagedSegment,
                    size: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    acquires++
                    return native.fileSystem.openat2(dirfd, path, how, size).also {
                        record(
                            "ACQUIRE root=${dirfd.value} suffix=${path.readString(
                                0,
                            )} size=$size flags=${how.readLong(
                                Layouts.OPEN_HOW_FLAGS_OFFSET,
                            )} mode=${how.readLong(Layouts.OPEN_HOW_MODE_OFFSET)} resolve=${how.readLong(Layouts.OPEN_HOW_RESOLVE_OFFSET)}",
                            it,
                        )
                    }
                }

                override fun openat(
                    dirfd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    path: ManagedSegment,
                    flags: OpenFlags,
                    mode: Int,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    reopens++
                    return native.fileSystem.openat(dirfd, path, flags, mode).also {
                        if (it is LinuxNative.SyscallResult.Success) readable++
                        record("READABLE_OPEN proc=${dirfd.value} decimal=${path.readString(0)} flags=${flags.value} mode=$mode", it)
                    }
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closes++
                    return native.fileSystem.close(fd).also {
                        it.getOrThrow("retire measured FD")
                        check(live.remove(fd.value))
                        events += "CLOSE fd=${fd.value}"
                    }
                }
            }
        }

        private fun record(
            stage: String,
            result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
        ) {
            when (result) {
                is LinuxNative.SyscallResult.Success -> {
                    check(live.add(result.value.toInt()))
                    events += "$stage success-fd=${result.value}"
                }
                is LinuxNative.SyscallResult.Error -> events += "$stage errno=${result.errno} raw=${result.rawValue}"
            }
        }

        fun metadata(path: Path): BasicFileAttributes {
            val stage = MetadataStage.entries[metadataCalls]
            val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }
                .onFailure { events += "METADATA stage=$stage fd=${path.fileName} failure=$it" }
                .getOrThrow()
            events += "METADATA stage=$stage fd=${path.fileName} regular=${attrs.isRegularFile} directory=${attrs.isDirectory} other=${attrs.isOther} key=${attrs.fileKey()}"
            check(attrs.fileKey()?.javaClass?.name == "sun.nio.fs.UnixFileKey") { "Unqualified file identity provider" }
            metadataCalls++
            if (stage == MetadataStage.PRE) {
                pinnedKey = attrs.fileKey()
                afterFirstMetadata?.invoke()
            }
            return attrs
        }
    }

    private fun transaction(
        trace: Trace,
        root: PreparedPath,
        proc: SupervisorOwnedFd,
        suffix: String,
        bytes: String,
        forbidden: Boolean,
        unlinked: Boolean = false,
        mutation: Boolean = false,
        afterAcquire: () -> Unit,
    ) {
        val beforeOpen = trace.reopens
        val beforeReadable = trace.readable
        val beforeReads = trace.reads
        trace.beginMetadata()
        val probe = OwnedProcFdReopen(trace.engine, trace::metadata)
        val pin = when (val acquired = probe.acquire(root.descriptor, suffix)) {
            is SupervisorDescriptorResult.Opened -> acquired.descriptor
            is SupervisorDescriptorResult.Denied -> error("O_PATH acquisition errno=${acquired.errno}")
        }
        pin.use {
            val pinFlags = trace.engine.raw
                .fcntl(pin.handle, NativeConstants.F_GETFL, 0L)
                .getOrThrow("pin F_GETFL")
            check(pinFlags and NativeConstants.O_PATH.toLong() != 0L)
            trace.events += "PIN_FLAGS fd=${pin.handle.value} status=$pinFlags"
            afterAcquire()
            when (val reopened = probe.reopenRegular(pin, proc)) {
                is SupervisorDescriptorResult.Denied -> {
                    check(forbidden || mutation) { "Positive reopen refused errno=${reopened.errno}" }
                    check(reopened.errno in listOf(1, 13) || (unlinked && reopened.errno == 2)) { "Unqualified native refusal errno=${reopened.errno}" }
                    check(trace.reopens == beforeOpen + 1 && trace.readable == beforeReadable && trace.reads == beforeReads)
                    check(trace.metadataCalls == 1 && trace.pinnedKey != null)
                    trace.events += "NATIVE_REFUSAL errno=${reopened.errno} readable-delta=0 reads-delta=0"
                }
                is SupervisorDescriptorResult.Opened -> reopened.descriptor.use { readable ->
                    check(!forbidden) { "Forbidden readable FD acquired" }
                    check(pin.handle.isLiveForIo() && readable.handle.isLiveForIo())
                    check(trace.metadataCalls == 3 && trace.pinnedKey != null)
                    val flags = trace.engine.raw
                        .fcntl(readable.handle, NativeConstants.F_GETFL, 0L)
                        .getOrThrow("F_GETFL")
                    check(flags and 3L == NativeConstants.O_RDONLY.toLong() && flags and NativeConstants.O_PATH.toLong() == 0L)
                    listOf(pin, readable).forEach { fd ->
                        val fdFlags = trace.engine.raw
                            .fcntl(fd.handle, NativeConstants.F_GETFD, 0L)
                            .getOrThrow("F_GETFD")
                        check(fdFlags and NativeConstants.FD_CLOEXEC.toLong() != 0L)
                        trace.events += "FLAGS fd=${fd.handle.value} fdflags=$fdFlags status=${if (fd === pin) pinFlags else flags}"
                    }
                    val actual = NativeArena.ofConfined().use { arena ->
                        val buffer = arena.allocate(256)
                        val all = mutableListOf<Byte>()
                        repeat(4) {
                            trace.reads++
                            val count = trace.engine.memory
                                .read(readable.handle, buffer, 256L)
                                .getOrThrow("direct-FD-read")
                            check(count in 0..256)
                            if (count == 0L) return@use all.toByteArray().toString(Charsets.UTF_8)
                            val chunk = ByteArray(count.toInt())
                            ManagedSegment.copy(buffer, 0, chunk, 0, chunk.size)
                            all.addAll(chunk.toList())
                            check(all.size <= 512)
                        }
                        error("No EOF within bounded direct-FD read")
                    }
                    check(actual == bytes) { "Wrong pinned bytes: $actual" }
                    check(pin.handle.isLiveForIo())
                    check(trace.reopens == beforeOpen + 1 && trace.readable == beforeReadable + 1)
                    trace.events += "DIRECT_FD_READ fd=${readable.handle.value} key=${trace.pinnedKey} bytes=$actual EOF=true pin-live=true"
                }
            }
        }
    }

    private fun rejectNonregular(
        trace: Trace,
        root: PreparedPath,
        proc: SupervisorOwnedFd,
        suffix: String,
    ) {
        trace.beginMetadata()
        val probe = OwnedProcFdReopen(trace.engine, trace::metadata)
        val before = trace.reopens
        val pin = when (val acquired = probe.acquire(root.descriptor, suffix)) {
            is SupervisorDescriptorResult.Opened -> acquired.descriptor
            is SupervisorDescriptorResult.Denied -> error("Nonregular acquisition errno=${acquired.errno}")
        }
        pin.use {
            val observed = runCatching { probe.reopenRegular(pin, proc) }
            val returned = observed.getOrNull()
            if (returned is SupervisorDescriptorResult.Opened) returned.descriptor.close()
            check(observed.exceptionOrNull() is IllegalArgumentException) { "Expected type rejection, got $observed" }
            check(trace.reopens == before)
            trace.events += "TYPE_REJECT suffix=$suffix readable-open-delta=0 cause=${observed.exceptionOrNull()}"
        }
    }

    private fun prepareNonregular(
        app: Path,
        appFd: Int,
        resources: MutableList<AutoCloseable>,
        receipts: MutableList<String>,
    ): List<String> {
        Files.createDirectory(app.resolve("directory"))
        val fifo = ProcessBuilder("mkfifo", app.resolve("fifo").toString()).start()
        awaitFifo(fifo)
        receipts += "MKFIFO retired=true exit=${fifo.exitValue()}"
        val socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also(resources::add)
        // The trusted directory FD keeps sun_path below Linux's 108-byte bound.
        socket.bind(UnixDomainSocketAddress.of(Path.of("/proc/self/fd/$appFd/socket")))
        val expectedTypes = mapOf(
            app.resolve("directory") to 0x4000,
            app.resolve("fifo") to 0x1000,
            app.resolve("socket") to 0xc000,
            Path.of("/dev/null") to 0x2000,
        )
        expectedTypes.forEach { (path, type) ->
            val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
            check(mode and 0xf000 == type)
            receipts += "NONREGULAR path=$path mode=$mode type=$type key=${attributes(path).fileKey()} no-readable-open=true"
        }
        return listOf("directory", "fifo", "socket")
    }

    private fun awaitFifo(fifo: Process) {
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null

        fun attempt(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { caught ->
                if (caught is InterruptedException) interrupted = true
                failure = CleanupFailures.merge(failure, caught)
            }
            interrupted = Thread.interrupted() || interrupted
        }
        try {
            attempt {
                if (interrupted) throw InterruptedException("FIFO await was already interrupted")
                check(fifo.waitFor(10, TimeUnit.SECONDS)) { "mkfifo timeout" }
                check(fifo.exitValue() == 0) { "mkfifo failed exit=${fifo.exitValue()} stderr=${fifo.errorStream.readAllBytes().toString(Charsets.UTF_8)}" }
            }
            attempt { if (fifo.isAlive) fifo.destroyForcibly() }
            attempt { check(fifo.waitFor(10, TimeUnit.SECONDS)) { "mkfifo retirement timeout" } }
            attempt { fifo.inputStream.close() }
            attempt { fifo.errorStream.close() }
            attempt { fifo.outputStream.close() }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    private fun isMutation(case: KernelCase): Boolean =
        when (case) {
        KernelCase.RENAME_REPLACE, KernelCase.ROOT_RENAME_REPLACE, KernelCase.UNLINK, KernelCase.MOVE_OUTSIDE -> true
        KernelCase.BASELINE, KernelCase.ALLOWED, KernelCase.FORBIDDEN, KernelCase.NONREGULAR, KernelCase.ESCAPES -> false
    }

    private fun mutate(
        case: KernelCase,
        app: Path,
        outside: Path,
        index: Int,
    ) {
        when (case) {
            KernelCase.RENAME_REPLACE -> {
                Files.move(app.resolve("target"), app.resolve("held"))
                write(app.resolve("target"), "replacement-never-authorized")
            }
            KernelCase.ROOT_RENAME_REPLACE -> {
                Files.move(app, app.resolveSibling("held-root-$index"))
                Files.createDirectory(app)
                write(app.resolve("target"), "replacement-root-never-authorized")
            }
            KernelCase.UNLINK -> Files.delete(app.resolve("target"))
            KernelCase.MOVE_OUTSIDE -> Files.move(app.resolve("target"), outside.resolve("moved-$index"))
            KernelCase.BASELINE, KernelCase.ALLOWED, KernelCase.FORBIDDEN, KernelCase.NONREGULAR, KernelCase.ESCAPES -> error("Not a mutation: $case")
        }
    }

    private fun write(
        path: Path,
        bytes: String,
    ) {
        Files.writeString(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java)

    private fun descriptorInventory(
        forbidden: Path,
        receipts: MutableList<String>,
        descriptors: () -> List<Path> = { Files.list(Path.of("/proc/self/fd")).use { it.toList() } },
        readLink: (Path) -> Path = { Files.readSymbolicLink(it) },
        readInfo: (Path) -> String = { Files.readString(it) },
    ) {
        descriptors().forEach { path ->
            val result = runCatching {
                val target = readLink(path)
                val info = readInfo(Path.of("/proc/self/fdinfo/${path.fileName}"))
                val flags = info
                    .lineSequence()
                    .first { it.startsWith("flags:") }
                    .substringAfter(':')
                    .trim()
                    .toLong(8)
                if (target == forbidden) {
                    check(flags and NativeConstants.O_PATH.toLong() != 0L) { "Forbidden readable preopen: $path" }
                }
                "INHERITED_OR_SETUP fd=${path.fileName} target=$target info=${info.replace('\n', ';')}"
            }
            receipts += result.getOrElse { failure ->
                if (failure !is NoSuchFileException) throw failure
                val disappearance = runCatching { readLink(path) }.exceptionOrNull()
                if (disappearance !is NoSuchFileException) {
                    if (disappearance != null) throw disappearance
                    throw failure
                }
                "INVENTORY_TRANSIENT fd=${path.fileName} cause=$failure"
            }
        }
    }

    private fun environment(
        root: Path,
        abi: Int,
    ) {
        check(root.fileSystem.provider().scheme == "file")
        println(
            "ENV kernel=${System.getProperty(
                "os.version",
            )} arch=${System.getProperty(
                "os.arch",
            )} java=${System.getProperty(
                "java.runtime.version",
            )} vendor=${System.getProperty("java.vendor")} vm=${System.getProperty("java.vm.name")} provider=${root.fileSystem.provider()} abi=$abi fs=${Files.getFileStore(root)}",
        )
        listOf(
            "/proc/sys/kernel/osrelease",
            "/proc/sys/kernel/version",
            "/proc/sys/kernel/ostype",
            "/proc/version",
            "/proc/self/status",
            "/proc/self/uid_map",
            "/proc/self/gid_map",
            "/proc/self/attr/current",
            "/sys/kernel/security/lsm",
            "/proc/cmdline",
        ).forEach { name ->
            val result = runCatching { Files.readString(Path.of(name)) }
            println("ENV $name=${result.fold({ it }, { "UNREADABLE $it" })}")
        }
        listOf("pid", "mnt", "user").forEach { println("NAMESPACE $it=${Files.readSymbolicLink(Path.of("/proc/self/ns/$it"))}") }
        val config = Path.of("/boot/config-${System.getProperty("os.version")}")
        val configReceipt = runCatching { Files.readAllLines(config).filter { it.contains("LANDLOCK") || it.startsWith("CONFIG_LSM=") || it.startsWith("CONFIG_SECURITY=") } }
        println("KERNEL_CONFIG path=$config receipt=${configReceipt.fold({ it.toString() }, { "UNREADABLE $it" })}")
        println("PREEXISTING_MAPPINGS ${Files.readString(Path.of("/proc/self/maps"))}")
        val mounts = Files.readAllLines(Path.of("/proc/self/mountinfo"))
        listOf(root, Path.of("/proc/self/fd"), Path.of("/dev/null")).forEach { path ->
            val matching = mounts
                .filter { line ->
                val mount = line.split(' ')[4]
                path.toString() == mount || path.toString().startsWith(if (mount == "/") "/" else "$mount/")
            }.maxBy { it.split(' ')[4].length }
            println("MOUNT path=$path entry=$matching key=${attributes(path).fileKey()}")
        }
        println("LIMIT security-envelope=observed-not-reconstructed config=not-qualified worker-only-external-restrictions=unknown helper-equivalence=not-exercised")
    }
}
