package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.frozenCopy
import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupportRecipe
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ControlTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ResultTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.RootTag
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannelTest
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.platform.syscall.SeccompAction
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedHelperReadPlanTest {
    @TempDir lateinit var directory: Path

    private inner class Fixture : AutoCloseable {
        val application = Files.createDirectory(directory.resolve("application"))
        val support = Files.writeString(directory.resolve("runtime.jar"), "reviewed-runtime")
        val closed = mutableListOf<Int>()
        var duplicates = 0
        var opens = 0
        var failDuplicate = Int.MAX_VALUE
        var duplicateFailure: Error? = null
        var duplicateErrno = 13
        private val real = LinuxNative.snapshot()
        val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    opens++
                    return real.fileSystem.open(path, flags)
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    closed += fd.value
                    return real.fileSystem.close(fd)
                }
            }
            override val raw = object : RawSyscallOperations by real.raw {
                override fun fcntl(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    cmd: Int,
                    arg: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (cmd == 1030 && ++duplicates == failDuplicate) {
                        duplicateFailure?.let { throw it }
                        return LinuxNative.SyscallResult.Error(duplicateErrno, -1)
                    }
                    return real.raw.fcntl(fd, cmd, arg)
                }
            }
        }
        val policy = CanonicalPolicyArtifact.worker(
            RuntimeProfile.HOTSPOT_JIT,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(application)
                .allowFsRead(support)
                .build(),
        )
        val observation = HelperSecurityObservation().observeAncestry(Pid(ProcessHandle.current().pid().toInt()), native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        val resources = PreparedResourceSet.capture(policy.definition, native)
        val binding = HelperBinding(observation.identity.pid, observation.identity.startTimeTicks, 1, resources.generation, sha(policy.toJson().toByteArray()))
        val reviewed = listOf(ReviewedHelperSupport(support, sha("reviewed-runtime".toByteArray())))

        fun capture(
            binding: HelperBinding = this.binding,
            support: List<ReviewedHelperSupport> = reviewed,
            lifetime: HelperSupportLifetime = HelperSupportLifetime.NoWriters,
        ): PreparedHelperReadPlan = PreparedHelperReadPlan.capture(policy, resources, binding, support, lifetime, native, observation.ancestry)

        override fun close() = resources.close()
    }

    @Test
    fun `reviewed recipe copies membership and preserves lexical aliases`() {
        val input = mutableListOf(ReviewedHelperSupport(Path.of("runtime.jar"), "a".repeat(64)))
        val recipe = ReviewedHelperSupportRecipe.capture(input)
        input.clear()
        assertEquals(listOf(ReviewedHelperSupport(Path.of("runtime.jar"), "a".repeat(64))), recipe.entries)
        assertFailsWith<UnsupportedOperationException> { (recipe.entries as MutableList<ReviewedHelperSupport>).clear() }
        assertEquals(2, ReviewedHelperSupportRecipe.capture(listOf(recipe.entries.single(), recipe.entries.single().copy(path = Path.of(".", "runtime.jar")))).entries.size)
    }

    @Test
    fun `reviewed recipe rejects empty and oversized membership`() {
        assertFailsWith<IllegalArgumentException> { ReviewedHelperSupportRecipe.capture(emptyList()) }
        val many = (0 until 65).map { ReviewedHelperSupport(Path.of("runtime$it.jar"), "a".repeat(64)) }
        assertEquals(64, ReviewedHelperSupportRecipe.capture(many.take(64)).entries.size)
        assertFailsWith<IllegalArgumentException> { ReviewedHelperSupportRecipe.capture(many) }
    }

    @Test
    fun `reviewed recipe rejects malformed digest and lexical duplicate`() {
        val entry = ReviewedHelperSupport(Path.of("a"), "a".repeat(64))
        assertFailsWith<IllegalArgumentException> { ReviewedHelperSupportRecipe.capture(listOf(entry.copy(sha256 = "A".repeat(64)))) }
        assertFailsWith<IllegalArgumentException> { ReviewedHelperSupportRecipe.capture(listOf(entry.copy(sha256 = "abc"))) }
        assertFailsWith<IllegalArgumentException> { ReviewedHelperSupportRecipe.capture(listOf(entry, entry)) }
    }

    @Test
    fun `normalized duplicate takes precedence over malformed digest`() =
        Fixture().use { f ->
        val alias = f.support.parent
            .resolve(".")
            .resolve(f.support.fileName)
        val invalid = listOf(f.reviewed.single(), ReviewedHelperSupport(alias, "bad"))
        val failure = assertFails { f.capture(support = invalid) }
        assertTrue(failure.message.orEmpty().contains("distinct exact support files"))
        val earlier = assertFails { f.capture(support = invalid, lifetime = HelperSupportLifetime.Uncontrolled) }
        assertTrue(earlier.message.orEmpty().contains("no-writer"))
        val badBinding = assertFails { f.capture(binding = f.binding.copy(resourceGeneration = "0".repeat(64)), support = invalid) }
        assertTrue(badBinding.message.orEmpty().contains("resource generation mismatch"))
        val before = f.opens
        val malformed = assertFails { f.capture(support = listOf(f.reviewed.single().copy(sha256 = "bad"))) }
        assertTrue(malformed.message.orEmpty().contains("reviewed SHA-256"))
        assertEquals(before, f.opens)
        }

    @Test
    fun `same support inode via hard link rolls back acquired pins`() =
        Fixture().use { f ->
        val alias = Files.createLink(directory.resolve("alias.jar"), f.support)
        val policy = CanonicalPolicyArtifact.worker(
            RuntimeProfile.HOTSPOT_JIT,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(f.application)
                .allowFsRead(f.support)
                .allowFsRead(alias)
                .build(),
        )
        PreparedResourceSet.capture(policy.definition, f.native).use { resources ->
            val binding = f.binding.copy(resourceGeneration = resources.generation, policyDigest = sha(policy.toJson().toByteArray()))
            val before = f.closed.size
            val failure = assertFails {
                PreparedHelperReadPlan.capture(
                    policy,
                    resources,
                    binding,
                    f.reviewed + f.reviewed.single().copy(path = alias),
                    HelperSupportLifetime.NoWriters,
                    f.native,
                    f.observation.ancestry,
                )
            }
            assertTrue(failure.message.orEmpty().contains("Duplicate helper support object"))
            assertEquals(before + 2, f.closed.size)
            resources.validate()
        }
        }

    @Test
    fun `missing support and conflicting writable grant refuse`() =
        Fixture().use { f ->
        val missing = f.reviewed
            .single()
            .copy(path = directory.resolve("missing.jar"))
        assertFails { f.capture(support = listOf(missing)) }
        val policy = CanonicalPolicyArtifact.worker(
            RuntimeProfile.HOTSPOT_JIT,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(f.application)
                .allowFsRead(f.support)
                .allowFsWrite(f.support)
                .build(),
        )
        PreparedResourceSet.capture(policy.definition, f.native).use { resources ->
            val binding = f.binding.copy(resourceGeneration = resources.generation, policyDigest = sha(policy.toJson().toByteArray()))
            val failure = assertFails { PreparedHelperReadPlan.capture(policy, resources, binding, f.reviewed, HelperSupportLifetime.NoWriters, f.native, f.observation.ancestry) }
            assertTrue(failure.message.orEmpty().contains("Writable source grant"))
            resources.validate()
        }
        }

    @Test
    fun `writable unix mode alone does not contradict no writers premise`() =
        Fixture().use { f ->
        val store = Files.getFileStore(f.support)
        assumeTrue(store.supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(
            f.support,
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rw-rw-r--"),
        )
        f.capture().use { it.validate() }
        }

    @Test
    fun `support originals and transfer duplicates are separate from borrowed application roots`() =
        Fixture().use { f ->
        val borrowed = f.resources.grants
            .map { it.path.descriptor.value }
            .toSet()
        val before = f.closed.size
        f.capture().use { plan ->
            plan.validate()
            val transfers = plan.duplicateForTransfer(f.native)
            assertEquals(2, transfers.size)
            assertEquals(2, transfers.map { it.handle.value }.distinct().size)
            transfers.forEach {
                assertTrue(it.handle.value !in borrowed)
                it.close()
            }
            plan.validate()
        }
        assertEquals(3, f.closed.size - before)
        assertTrue(f.closed.none { it in borrowed })
        f.resources.validate()
    }

    @Test
    fun `reservation transfers support originals until physical helper retirement and never borrowed roots`() =
        Fixture().use { f ->
        val plan = f.capture()
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic
            .AtomicInteger()
        val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
            object : SocketHelper {
                override fun execute(
                    operation: SocketOperation,
                    socket: SupervisorOwnedFd,
                ): SocketHelperResult = error("Legacy operation")

                override fun runConfined(exchange: ConfinedHelperJob) {
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                }

                override fun close() {
                    if (closed.incrementAndGet() == 1) error("controlled reap failure")
                }
            }
        }
        try {
            val deadline = io.mazewall.ffi.networking
                .ConfinedDeadline(100)
            assertFails { pool.reserveConfined(plan, deadline) }
            plan.validate()
            pool.prewarm()
            val session = pool.reserveConfined(plan, deadline)
            session.cancel()
            release.countDown()
            assertFails { session.close() }
            plan.validate()
            f.resources.validate()
            session.close()
            assertFails { plan.validate() }
            f.resources.validate()
        } finally {
            release.countDown()
            pool.close()
            plan.close()
        }
    }

    @Test
    fun `direct session feeds one duplicate per adoption and exposes only the committed read`() =
        Fixture().use { f ->
        ConfinedHelperChannelTest.Fixture(81105).use { wire ->
            val plan = f.capture()
            val binding = plan.manifest.binding
            val cap = ConfinedDeadline(5_000_000_000)
            val turns = java.util.concurrent.ArrayBlockingQueue<CompletableFuture<Unit>>(1)
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val rootOwners = mutableListOf<SupervisorOwnedFd>()
            val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                object : SocketHelper {
                    override fun execute(
                        operation: SocketOperation,
                        socket: SupervisorOwnedFd,
                    ): SocketHelperResult = error("Legacy request")

                    override fun runConfined(exchange: ConfinedHelperJob) {
                        val io = ConfinedHelperProcessIo(wire.channel, exchange, {}, { capability, owner ->
                            assertEquals(capability.identity, HelperSecurityObservation().observeRoot(owner.handle))
                            rootOwners += owner
                        }, { _, _ ->
                            received.countDown()
                            check(release.await(2, TimeUnit.SECONDS))
                        })
                        while (!exchange.cancelled) {
                            val turn = turns.poll(25, TimeUnit.MILLISECONDS) ?: continue
                            try {
                                io.execute(requireNotNull(exchange.poll(wire.now)))
                                turn.complete(Unit)
                            } catch (failure: Throwable) {
                                turn.completeExceptionally(failure)
                                throw failure
                            }
                        }
                    }

                    override fun close() = Unit
                }
            }

            fun turn(): CompletableFuture<Unit> = CompletableFuture<Unit>().also { check(turns.offer(it)) }

            fun feed(frame: Frame) = wire.input(ConfinedHelperProtocol.encode(frame))
            try {
                pool.prewarm()
                val session = pool.reserveConfined(plan, cap)
                val setup = session.setupConfined(cap)
                assertEquals(0, f.duplicates)
                feed(Frame.Control(ControlTag.SetupAccepted, binding, cap))
                turn().get(2, TimeUnit.SECONDS)
                assertEquals(1, f.duplicates)
                assertFalse(setup.isDone)
                repeat(2) { index ->
                    feed(Frame.Root(RootTag.Expected, binding, index, cap))
                    feed(Frame.Root(RootTag.Received, binding, index, cap))
                    turn().get(2, TimeUnit.SECONDS)
                    assertFalse(rootOwners[index].handle.isLiveForIo())
                    assertEquals(2, f.duplicates)
                    assertFalse(setup.isDone)
                }
                feed(Frame.Control(ControlTag.Ready, binding, cap))
                turn().get(2, TimeUnit.SECONDS)
                assertEquals(ConfinedSetupResult.Ready(binding), setup.get(2, TimeUnit.SECONDS))
                val request = ConfinedReadRequest(binding, 1, HelperPurpose.Application, 0, "file")
                val result = session.readConfined(request, cap)
                val opened = ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Opened, binding, 1, cap))
                wire.receiveRightsAt = wire.receivedBytes + opened.size
                wire.input(opened + byteArrayOf(0))
                feed(Frame.Result(ResultTag.Done, binding, 1, cap))
                val readTurn = turn()
                assertTrue(received.await(2, TimeUnit.SECONDS))
                assertFalse(result.isDone)
                assertTrue(wire.closed.isEmpty())
                release.countDown()
                readTurn.get(2, TimeUnit.SECONDS)
                assertIs<SupervisorDescriptorResult.Opened>(result.get(2, TimeUnit.SECONDS)).descriptor.close()
                assertEquals(listOf(812), wire.closed)
                Files.move(f.support, directory.resolve("old-runtime"))
                Files.writeString(f.support, "reviewed-runtime")
                assertFails { session.readConfined(request.copy(id = 2), cap) }
                session.close()
                assertTrue(session.confinedRetired)
                assertTrue(f.resources.grants.all { it.path.descriptor.isLiveForIo() })
            } finally {
                release.countDown()
                pool.close()
                plan.close()
            }
        }
    }

    @Test
    fun `binding mismatch and missing lifetime premise reject before duplication`() =
        Fixture().use { f ->
        for (binding in listOf(f.binding.copy(resourceGeneration = "0".repeat(64)), f.binding.copy(policyDigest = "0".repeat(64)), f.binding.copy(domainGeneration = 0))) {
            assertFails { f.capture(binding).close() }
        }
        assertFails { f.capture(lifetime = HelperSupportLifetime.Uncontrolled).close() }
        assertEquals(0, f.duplicates)
        f.resources.validate()
    }

    internal enum class FatalCase { Execution, CancelThenExecution, DirectCallback }

    @ParameterizedTest
    @EnumSource(FatalCase::class)
    internal fun `confined worker fatal survives cancellation publication and failed retirement`(case: FatalCase) =
        Fixture().use { f ->
            ConfinedHelperChannelTest.Fixture(81140).use { wire ->
                val plan = f.capture()
                val fatal = LinkageError("confined setup linkage")
                if (case == FatalCase.DirectCallback) {
                    f.failDuplicate = 1
                    f.duplicateFailure = fatal
                }
                val execute = CountDownLatch(1)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val uncaught = CompletableFuture<Throwable>()
                val observed = CompletableFuture<ConfinedHelperJob>()
                val reaps = java.util.concurrent.atomic
                    .AtomicInteger()
                val creates = java.util.concurrent.atomic
                    .AtomicInteger()
                val reapFailure = IllegalStateException("controlled reap failure")
                val cap = ConfinedDeadline(5_000_000_000)
                val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                    creates.incrementAndGet()
                    object : SocketHelper {
                        override fun execute(
                            operation: SocketOperation,
                            socket: SupervisorOwnedFd,
                        ): SocketHelperResult = error("Legacy request")

                        override fun runConfined(exchange: ConfinedHelperJob) {
                            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.complete(failure) }
                            observed.complete(exchange)
                            check(execute.await(2, TimeUnit.SECONDS))
                            val io = ConfinedHelperProcessIo(wire.channel, exchange, {
                                entered.countDown()
                                check(release.await(2, TimeUnit.SECONDS))
                                if (case != FatalCase.DirectCallback) throw fatal
                            }, { _, _ -> error("No root transfer") }, { _, _ -> error("No read") })
                            io.execute(requireNotNull(exchange.poll(wire.now)))
                        }

                        override fun close() {
                            observed.join().awaitQuiescence { 0 }
                            if (reaps.incrementAndGet() == 1) throw reapFailure
                        }
                    }
                }
                try {
                    pool.prewarm()
                    wire.input(ConfinedHelperProtocol.encode(Frame.Control(ControlTag.SetupAccepted, plan.manifest.binding, cap)))
                    val session = pool.reserveConfined(plan, cap)
                    val setup = session.setupConfined(cap)
                    execute.countDown()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    if (case == FatalCase.CancelThenExecution) session.cancel()
                    assertFalse(setup.isDone)
                    assertEquals(0, reaps.get())
                    plan.validate()
                    release.countDown()
                    val result = assertIs<ConfinedSetupResult.Rejected>(setup.get(2, TimeUnit.SECONDS))
                    assertSame(fatal, result.cause)
                    assertSame(fatal, uncaught.get(2, TimeUnit.SECONDS))
                    if (case == FatalCase.CancelThenExecution) assertTrue(fatal.suppressed.any { it is java.util.concurrent.CancellationException })
                    assertSame(reapFailure, assertFails { session.close() })
                    assertFalse(session.confinedRetired)
                    plan.validate()
                    f.resources.validate()
                    assertEquals(if (case == FatalCase.DirectCallback) 1 else 0, f.duplicates)
                    assertFails { pool.reserveConfined(plan, cap) }
                    session.close()
                    assertTrue(session.confinedRetired)
                    assertFails { plan.validate() }
                    f.resources.validate()
                    assertEquals(2, reaps.get())
                    assertEquals(1, creates.get())
                } finally {
                    execute.countDown()
                    release.countDown()
                    try {
                        pool.close()
                    } catch (expectedReapFailure: IllegalStateException) {
                        assertSame(reapFailure, expectedReapFailure)
                        pool.close()
                    }
                    plan.close()
                }
            }
        }

    @Test
    fun `wrong reviewed bytes duplicate identities and directory support roll back only new pins`() =
        Fixture().use { f ->
        val before = f.closed.size
        assertFails { f.capture(support = listOf(ReviewedHelperSupport(f.support, "0".repeat(64)))).close() }
        assertEquals(before + 1, f.closed.size)
        assertFails { f.capture(support = f.reviewed + f.reviewed).close() }
        assertFails { f.capture(support = listOf(ReviewedHelperSupport(f.application, "0".repeat(64)))).close() }
        f.resources.validate()
    }

    @Test
    fun `partial duplication failure preserves permission cause and closes acquired duplicates`() =
        Fixture().use { f ->
        f.capture().use { plan ->
            f.failDuplicate = 2
            val before = f.closed.size
            val failure = assertFails { plan.duplicateForTransfer(f.native) }
            assertTrue(failure.message.orEmpty().contains("errno=13"), failure.toString())
            assertEquals(before + 1, f.closed.size)
            plan.validate()
            f.resources.validate()
        }
    }

    @Test
    fun `caller input mutation cannot drop support validation and original name replacement invalidates`() =
        Fixture().use { f ->
        val input = f.reviewed.toMutableList()
        f.capture(support = input).use { plan ->
            input.clear()
            Files.move(f.support, directory.resolve("old-runtime"))
            Files.writeString(f.support, "reviewed-runtime")
            assertFails { plan.validate() }
            assertFails { plan.duplicateForTransfer(f.native) }
            assertEquals(0, f.duplicates)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 13, 5])
    fun `direct setup publishes exact duplication errno and retains originals after failed reap`(errno: Int) =
        Fixture().use { f ->
            ConfinedHelperChannelTest.Fixture(81141).use { wire ->
                val plan = f.capture()
                val cap = ConfinedDeadline(5_000_000_000)
                val execute = CountDownLatch(1)
                val roots = mutableListOf<SupervisorOwnedFd>()
                val reaps = java.util.concurrent.atomic
                    .AtomicInteger()
                val creates = java.util.concurrent.atomic
                    .AtomicInteger()
                val reapFailure = IllegalStateException("controlled duplication reap failure")
                f.failDuplicate = 2
                f.duplicateErrno = errno
                val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                    creates.incrementAndGet()
                    object : SocketHelper {
                        override fun execute(
                            operation: SocketOperation,
                            socket: SupervisorOwnedFd,
                        ): SocketHelperResult = error("Legacy request")

                        override fun runConfined(exchange: ConfinedHelperJob) {
                            check(execute.await(2, TimeUnit.SECONDS))
                            val io = ConfinedHelperProcessIo(wire.channel, exchange, {}, { _, owner -> roots += owner }, { _, _ -> error("No read") })
                            while (!exchange.cancelled) io.execute(requireNotNull(exchange.poll(wire.now)))
                        }

                        override fun close() {
                            if (reaps.incrementAndGet() == 1) throw reapFailure
                        }
                    }
                }
                try {
                    pool.prewarm()
                    val session = pool.reserveConfined(plan, cap)
                    wire.input(ConfinedHelperProtocol.encode(Frame.Control(ControlTag.SetupAccepted, plan.manifest.binding, cap)))
                    wire.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, plan.manifest.binding, 0, cap)))
                    wire.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Received, plan.manifest.binding, 0, cap)))
                    val setup = session.setupConfined(cap)
                    execute.countDown()
                    val result = assertIs<ConfinedSetupResult.Rejected>(setup.get(2, TimeUnit.SECONDS))
                    assertEquals(if (errno == 5) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission, result.reason)
                    assertEquals(errno, result.errno)
                    assertEquals(errno, assertIs<ConfinedHelperRejection>(result.cause).errno)
                    assertEquals(2, f.duplicates)
                    val root = roots.single()
                    assertFalse(root.handle.isLiveForIo())
                    assertEquals(1, f.closed.count { it == root.handle.value })
                    assertSame(reapFailure, assertFails { session.close() })
                    assertFalse(session.confinedRetired)
                    plan.validate()
                    f.resources.validate()
                    assertFails { pool.reserveConfined(plan, cap) }
                    session.close()
                    assertTrue(session.confinedRetired)
                    assertFails { plan.validate() }
                    f.resources.validate()
                    assertEquals(1, creates.get())
                } finally {
                    execute.countDown()
                    try {
                        pool.close()
                    } catch (expectedReapFailure: IllegalStateException) {
                        assertSame(reapFailure, expectedReapFailure)
                        pool.close()
                    }
                    plan.close()
                }
            }
        }

    @Test
    fun `pinning cannot bless modified support bytes and close is single use`() =
        Fixture().use { f ->
        val plan = f.capture()
        try {
            Files.writeString(f.support, "changed-runtime")
            assertFails { plan.validate() }
        } finally {
            val before = f.closed.size
            plan.close()
            plan.close()
            assertEquals(before + 1, f.closed.size)
        }
        assertFails { plan.validate() }
        assertFalse(f.resources.grants.any { !it.path.descriptor.isLiveForIo() })
    }

    @Test
    fun `source policy is frozen before the first native acquisition`() =
        Fixture().use { f ->
        val paths = f.policy.definition.allowedFsReadPaths
            .toMutableSet()
        val supplied = f.policy.definition.copy(allowedFsReadPaths = paths)
        val expected = supplied.frozenCopy()
        val engine = object : NativeEngine by f.native {
            override val fileSystem = object : NativeFileSystem by f.native.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    paths.clear()
                    return f.native.fileSystem.open(path, flags)
                }
            }
        }
        PreparedResourceSet.capture(supplied, engine).use { it.requireSource(expected, engine) }
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `static denial cannot be replaced by helper resource permission`() =
        Fixture().use { f ->
        for (route in listOf(Syscall.OPEN, Syscall.OPENAT)) {
            val denied = CanonicalPolicyArtifact.Worker(f.policy.runtime, f.policy.definition.copy(syscallActions = f.policy.definition.syscallActions + (route to SeccompAction.ACT_ERRNO(13))))
            PreparedResourceSet.capture(denied.definition, f.native).use { resources ->
                val before = f.closed.size
                val binding = f.binding.copy(resourceGeneration = resources.generation, policyDigest = sha(denied.toJson().toByteArray()))
                val failure = assertFails { PreparedHelperReadPlan.capture(denied, resources, binding, f.reviewed, HelperSupportLifetime.NoWriters, f.native, f.observation.ancestry) }
                assertTrue(failure.message.orEmpty().contains("statically denied"), failure.toString())
                assertEquals(before, f.closed.size)
            }
        }
        assertEquals(0, f.duplicates)
    }

    @Test
    fun `automatic runtime directories and outside support do not grant application authority`() =
        Fixture().use { f ->
        val onlySupport = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(f.support).build())
        PreparedResourceSet.capture(onlySupport.definition, f.native).use { resources ->
            val binding = f.binding.copy(resourceGeneration = resources.generation, policyDigest = sha(onlySupport.toJson().toByteArray()))
            val failure = assertFails { PreparedHelperReadPlan.capture(onlySupport, resources, binding, f.reviewed, HelperSupportLifetime.NoWriters, f.native, f.observation.ancestry) }
            assertTrue(failure.message.orEmpty().contains("explicit application"), failure.toString())
        }
        val outside = Files.writeString(directory.resolve("outside.jar"), "reviewed-runtime")
        assertFails { f.capture(support = listOf(f.reviewed.single().copy(path = outside))).close() }
        val inside = Files.writeString(f.application.resolve("runtime.jar"), "reviewed-runtime")
        assertFails { f.capture(support = listOf(f.reviewed.single().copy(path = inside))).close() }
        f.resources.validate()
    }

    @Test
    fun `manifest binds observed held descriptors ancestry and reviewed bytes in transfer order`() =
        Fixture().use { f ->
        f.capture().use { plan ->
            val manifest = plan.manifest
            assertEquals(f.binding, manifest.binding.owner)
            assertEquals(f.observation.ancestry.canonical, manifest.ancestry.canonical)
            assertEquals(listOf(HelperPurpose.Application, HelperPurpose.RuntimeSupport), manifest.capabilities.map { it.purpose })
            assertEquals(listOf("0".repeat(64), f.reviewed.single().sha256), manifest.capabilities.map { it.contentDigest })
            val duplicates = plan.duplicateForTransfer(f.native)
            try {
                assertEquals(manifest.capabilities.map { it.identity }, duplicates.map { HelperSecurityObservation().observeRoot(it.handle) })
                val frame = ConfinedHelperProtocol.Frame.Setup(
                    manifest,
                    io.mazewall.ffi.networking
                    .ConfinedDeadline(19),
                )
                assertEquals(manifest.binding, ConfinedHelperProtocol.decode(ConfinedHelperProtocol.encode(frame)).binding)
            } finally {
                duplicates.asReversed().forEach { it.close() }
            }
            f.resources.validate()
        }
    }

    private fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
