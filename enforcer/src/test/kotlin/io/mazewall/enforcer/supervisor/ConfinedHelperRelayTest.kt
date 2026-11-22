package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.internal.supervisor.ConfinedHelperJob.Command
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ControlTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.Frame
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.ResultTag
import io.mazewall.enforcer.internal.supervisor.ConfinedHelperProtocol.RootTag
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.internal.duplicateHelperResource
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannelTest
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.EOFException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ConfinedHelperRelayTest {
    @TempDir lateinit var directory: Path
    private val manifest = ConfinedHelperTestData.manifest()
    private val binding = manifest.binding
    private val deadline = ConfinedDeadline(100)
    private val native = LinuxNative.snapshot()

    private fun owner(): SupervisorOwnedFd = PreparedPath.open(directory, native).use { duplicateHelperResource(it.descriptor, native) }

    private fun request(id: Long) = ConfinedReadRequest(binding, id, HelperPurpose.Application, 0, "file")

    private fun setup(delivery: ConfinedDelivery = ConfinedDelivery.Relay): ConfinedHelperJob {
        val job = ConfinedHelperJob(manifest, delivery)
        job.submit(Command.BeginSetup(manifest, deadline))
        job.acknowledge(requireNotNull(job.poll(1)), ConfinedStage.SetupAccepted(binding))
        repeat(2) { index ->
            val fd = owner()
            job.submit(Command.OfferRoot(index, fd, deadline))
            val ticket = requireNotNull(job.poll(1))
            fd.close()
            job.acknowledge(ticket, ConfinedStage.RootAdopted(binding, index))
        }
        job.submit(Command.FinishSetup(2, deadline))
        job.ready(requireNotNull(job.poll(1)))
        assertEquals(ConfinedSetupResult.Ready(binding), job.setup.get(1, TimeUnit.SECONDS))
        return job
    }

    @Test
    fun `stage mailbox consumes rejected owners and root adoption requires committed intermediate close`() {
        val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay)
        val accepted = job.submit(Command.BeginSetup(manifest, deadline))
        val extra = owner()
        assertIs<ConfinedStage.Failed>(job.submit(Command.OfferRoot(0, extra, deadline)).join())
        assertFalse(extra.handle.isLiveForIo())
        job.acknowledge(requireNotNull(job.poll(1)), ConfinedStage.SetupAccepted(binding))
        assertEquals(ConfinedStage.SetupAccepted(binding), accepted.join())
        val root = owner()
        val adopted = job.submit(Command.OfferRoot(0, root, deadline))
        val ticket = requireNotNull(job.poll(1))
        assertFalse(adopted.isDone)
        assertFails { job.acknowledge(ticket, ConfinedStage.RootAdopted(binding, 0)) }
        assertTrue(root.handle.isLiveForIo())
        job.cancel(IOException("abort root"))
        job.quiesce()
        assertFalse(root.handle.isLiveForIo())
        assertIs<ConfinedStage.Failed>(adopted.join())
    }

    @Test
    fun `relay quarantine waits for claim forward upstream receipt and close before helper receipt`() {
        val job = setup()
        val exchange = job.beginRead(request(1), deadline)
        val fd = owner()
        job.quarantine(requireNotNull(job.poll(1)), fd)
        assertEquals(ConfinedStage.Quarantined(binding, 1), exchange.stage.join())
        assertFalse(exchange.completed.isDone)
        assertFalse(exchange.result.isDone)
        assertSame(fd, job.claimQuarantined(1))
        assertNull(job.claimQuarantined(1))
        assertFails { job.commitResult(1, deadline) }
        for ((command, stage) in listOf(
            Command.ResultForwarded(1, deadline) to ConfinedStage.Forwarded(binding, 1),
            Command.UpstreamReceived(1, deadline) to ConfinedStage.UpstreamAccepted(binding, 1),
        )) {
            val reply = job.submit(command)
            job.acknowledge(requireNotNull(job.poll(1)), stage)
            assertEquals(stage, reply.join())
            assertFails { job.commitResult(1, deadline) }
            assertTrue(fd.handle.isLiveForIo())
        }
        fd.close()
        job.submit(Command.IntermediateClosed(1, deadline))
        job.acknowledge(requireNotNull(job.poll(1)), ConfinedStage.IntermediateCommitted(binding, 1))
        job.commitResult(1, deadline)
        assertFalse(exchange.completed.isDone)
        job.done(1, deadline)
        assertEquals(ConfinedStage.Done(binding, 1), exchange.completed.join())
        assertFalse(exchange.result.isDone, "Relay never publishes an application descriptor")
        job.releaseClaim(1)
        job.cancel(IOException("retire"))
        job.quiesce()
        job.awaitQuiescence { TimeUnit.SECONDS.toNanos(1) }
    }

    @Test
    fun `direct result remains quarantined until DONE and failed publication disposes it`() {
        for (cancelFuture in listOf(false, true)) {
            val job = setup(ConfinedDelivery.Direct)
            val exchange = job.beginRead(request(1), deadline)
            val fd = owner()
            job.quarantine(requireNotNull(job.poll(1)), fd)
            assertNull(job.claimQuarantined(1))
            assertFalse(exchange.result.isDone)
            job.commitResult(1, deadline)
            assertFalse(exchange.result.isDone)
            if (cancelFuture) exchange.result.cancel(false)
            job.done(1, deadline)
            if (cancelFuture) {
                assertFalse(fd.handle.isLiveForIo())
            } else {
                assertSame(fd, assertIs<SupervisorDescriptorResult.Opened>(exchange.result.join()).descriptor)
                fd.close()
            }
            job.cancel(IOException("retire"))
            job.quiesce()
        }
    }

    @Test
    fun `read queue holds one active and one waiting request and expires waiting budget independently`() {
        val job = setup()
        val active = job.beginRead(request(1), deadline)
        val ticket = requireNotNull(job.poll(1))
        val queued = job.beginRead(request(2), ConfinedDeadline(20))
        val full = job.beginRead(request(3), deadline)
        assertIs<ConfinedStage.Failed>(full.stage.join())
        assertNull(job.poll(20))
        assertIs<ConfinedStage.Failed>(queued.completed.join())
        assertFalse(active.stage.isDone)
        job.denied(ticket, 13)
        assertEquals(ConfinedStage.Denied(binding, 1, 13), active.completed.join())
        assertIs<ConfinedStage.Failed>(job.beginRead(request(2), deadline).stage.join())
        job.cancel(IOException("retire"))
        job.quiesce()
    }

    @Test
    fun `cancel closes only unclaimed quarantine and waits for actual claimed owner quiescence`() {
        for (claim in listOf(false, true)) {
            val job = setup()
            val exchange = job.beginRead(request(1), deadline)
            val fd = owner()
            job.quarantine(requireNotNull(job.poll(1)), fd)
            if (claim) assertSame(fd, job.claimQuarantined(1))
            val cause = IOException("parent disappeared")
            job.cancel(cause)
            assertFalse(exchange.completed.isDone, "Rejection is published at I/O quiescence")
            job.quiesce()
            assertSame(cause, assertIs<ConfinedStage.Failed>(exchange.completed.join()).cause)
            assertEquals(claim, fd.handle.isLiveForIo())
            if (claim) {
                assertFails { job.awaitQuiescence { 0 } }
                fd.close()
                job.releaseClaim(1)
            }
            job.awaitQuiescence { TimeUnit.SECONDS.toNanos(1) }
            assertNull(job.claimQuarantined(1))
        }
    }

    @Test
    fun `accepted setup still expires while waiting for the next root`() {
        val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay)
        job.submit(Command.BeginSetup(manifest, deadline))
        job.acknowledge(requireNotNull(job.poll(1)), ConfinedStage.SetupAccepted(binding))
        assertNull(job.poll(100))
        assertTrue(job.cancelled)
        job.quiesce()
        assertEquals(ConfinedRejectReason.Timeout, assertIs<ConfinedSetupResult.Rejected>(job.setup.join()).reason)
    }

    @Test
    fun `actual helper socket interpreter acknowledges root zero before root one is offered`() =
        ConfinedHelperChannelTest.Fixture().use { wire ->
        val cap = ConfinedDeadline(5_000_000_000)
        val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay)
        val observed = mutableListOf<String>()
        val io = ConfinedHelperProcessIo(
            wire.channel,
            job,
            {
                assertTrue(wire.outgoing.size() == 0)
                observed += "setup"
            },
            { capability, fd ->
                assertTrue(fd.handle.isLiveForIo())
                observed += "root${capability.index}"
            },
            { _, _ -> error("Unexpected read") },
        )
        wire.input(ConfinedHelperProtocol.encode(Frame.Control(ControlTag.SetupAccepted, binding, cap)))
        val accepted = job.submit(Command.BeginSetup(manifest, cap))
        io.execute(requireNotNull(job.poll(wire.now)))
        assertEquals(ConfinedStage.SetupAccepted(binding), accepted.join())
        val root = owner()
        try {
            wire.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, binding, 0, cap)))
            wire.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Received, binding, 0, cap)))
            val adopted = job.submit(Command.OfferRoot(0, root, cap))
            io.execute(requireNotNull(job.poll(wire.now)))
            assertEquals(ConfinedStage.RootAdopted(binding, 0), adopted.join())
            assertFalse(root.handle.isLiveForIo())
            assertFalse(job.setup.isDone)
            assertEquals(listOf("setup", "root0"), observed)
            assertNull(job.poll(wire.now), "Root one is still withheld")
        } finally {
            job.cancel(IOException("finish fixture"))
            job.quiesce()
            root.close()
        }
    }

    @Test
    fun `helper socket receipt is withheld until staged upstream receipt and intermediate close`() =
        ConfinedHelperChannelTest.Fixture().use { wire ->
        val job = setup()
        val cap = ConfinedDeadline(5_000_000_000)
        val exchange = job.beginRead(request(1), cap)
        val opened = ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Opened, binding, 1, cap))
        wire.input(opened + byteArrayOf(0))
        wire.receiveRightsAt = opened.size
        val io = ConfinedHelperProcessIo(wire.channel, job, { error("Already set up") }, { _, _ -> error("Already rooted") }, { _, fd -> assertEquals(812, fd.handle.value) })
        io.execute(requireNotNull(job.poll(wire.now)))
        assertEquals(ConfinedStage.Quarantined(binding, 1), exchange.stage.join())
        val beforeReceipt = wire.outgoing.size()
        val intermediate = requireNotNull(job.claimQuarantined(1))
        try {
            for (command in listOf(Command.ResultForwarded(1, cap), Command.UpstreamReceived(1, cap))) {
                job.submit(command)
                io.execute(requireNotNull(job.poll(wire.now)))
                assertEquals(beforeReceipt, wire.outgoing.size())
                assertTrue(intermediate.handle.isLiveForIo())
                assertFalse(exchange.completed.isDone)
            }
            intermediate.close()
            wire.input(ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Done, binding, 1, cap)))
            val committed = job.submit(Command.IntermediateClosed(1, cap))
            io.execute(requireNotNull(job.poll(wire.now)))
            assertEquals(ConfinedStage.IntermediateCommitted(binding, 1), committed.join())
            assertEquals(ConfinedStage.Done(binding, 1), exchange.completed.join())
            assertContentEquals(ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Received, binding, 1, cap)), wire.outgoing.toByteArray().copyOfRange(beforeReceipt, wire.outgoing.size()))
            assertEquals(listOf(812), wire.closed)
        } finally {
            intermediate.close()
            job.releaseClaim(1)
            job.cancel(IOException("finish fixture"))
            job.quiesce()
        }
    }

    internal enum class RelayCase {
        Complete,
        RootAdoptedCancel,
        RootAdoptedEof,
        RootCloseFailure,
        BeforeClaim,
        AfterClaim,
        AfterForward,
        AfterUpstreamReceipt,
        IntermediateCloseFailure,
        HelperEofBeforeDone,
        ParentDoneSendFailure,
        ClaimedRetirement,
        LocalRootDeadline,
        LocalFrameDeadline,
        LocalStageDeadline,
        LocalResultDeadline,
        EarlyReceipt,
        EarlyDuplicate,
        EarlyWrongBinding,
        EarlyWrongId,
        EarlyWrongDeadline,
        EarlyCancel,
        EarlyTimeout,
        EarlyForwardFailure,
        EarlyForwardException,
    }

    @ParameterizedTest
    @EnumSource(RelayCase::class)
    internal fun `shared relay retains each staged owner through failure and physical retirement`(case: RelayCase) {
        ConfinedHelperChannelTest.Fixture(81100, 813).use { parent ->
            ConfinedHelperChannelTest.Fixture(81101, 814).use helperCase@{ helper ->
                val turns = java.util.concurrent.ArrayBlockingQueue<java.util.concurrent.CompletableFuture<Unit>>(1)
                val entered = java.util.concurrent.CountDownLatch(1)
                val observed = java.util.concurrent.CompletableFuture<ConfinedHelperJob>()
                val activeRead = java.util.concurrent.atomic
                    .AtomicReference<ConfinedReadExchange>()
                val reaps = java.util.concurrent.atomic
                    .AtomicInteger()
                var creates = 0
                val cap = ConfinedDeadline(5_000_000_000)
                val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                    creates++
                    object : SocketHelper {
                        override fun execute(
                            operation: SocketOperation,
                            socket: SupervisorOwnedFd,
                        ): SocketHelperResult = error("Legacy work")

                        override fun runConfined(exchange: ConfinedHelperJob) {
                            val io = ConfinedHelperProcessIo(helper.channel, exchange, {}, { _, _ -> }, { _, fd -> assertEquals(814, fd.handle.value) })
                            observed.complete(exchange)
                            entered.countDown()
                            while (!exchange.cancelled) {
                                val turn = turns.poll(25, TimeUnit.MILLISECONDS) ?: continue
                                try {
                                    val ticket = requireNotNull(exchange.poll(helper.now))
                                    ticket.read?.let(activeRead::set)
                                    io.execute(ticket)
                                    turn.complete(Unit)
                                } catch (failure: Throwable) {
                                    turn.completeExceptionally(failure)
                                    throw failure
                                }
                            }
                        }

                        override fun close() {
                            reaps.incrementAndGet()
                        }
                    }
                }

                fun advance() {
                    val finished = java.util.concurrent.CompletableFuture<Unit>()
                    check(turns.offer(finished))
                    finished.get(2, TimeUnit.SECONDS)
                }
                try {
                    pool.prewarm()
                    val session = pool.reserveConfined(manifest, cap)
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val relay = ConfinedHelperRelay(parent.channel, session, {}, { capability, fd ->
                        assertTrue(capability.index in 0..1)
                        assertEquals(813, fd.handle.value)
                    })

                    fun stop(cause: Throwable) {
                        relay.accept(ConfinedRelayEvent.Cancel(cause))
                        session.close()
                        assertTrue(session.confinedRetired)
                        assertEquals(1, reaps.get())
                        assertEquals(1, creates)
                        assertFails { pool.reserveConfined(manifest, cap) }
                        activeRead.get()?.let { read ->
                            assertIs<ConfinedStage.Failed>(read.completed.get(2, TimeUnit.SECONDS))
                            assertFalse(read.result.isDone, "Relay must never export a final application owner")
                        }
                    }

                    fun receiveParent(
                        frame: Frame,
                        localCap: ConfinedDeadline,
                    ) {
                        parent.input(ConfinedHelperProtocol.encode(frame))
                        val received = parent.channel.receiveFrame(localCap)
                        assertEquals(localCap, received.localDeadline)
                        relay.accept(ConfinedRelayEvent.ParentFrame(received.frame, received.localDeadline))
                    }
                    val localCap = ConfinedDeadline(2_000_000_000)
                    helper.input(ConfinedHelperProtocol.encode(Frame.Control(ControlTag.SetupAccepted, binding, cap)))
                    if (case in setOf(RelayCase.LocalRootDeadline, RelayCase.LocalFrameDeadline, RelayCase.LocalStageDeadline)) {
                        receiveParent(Frame.Setup(manifest, cap), localCap)
                    } else {
                        relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Setup(manifest, cap), cap))
                    }
                    assertEquals(0, parent.outgoing.size())
                    if (case == RelayCase.LocalStageDeadline) {
                        parent.now = localCap.nanos
                        try {
                            assertFailsWith<java.util.concurrent.TimeoutException> { relay.accept(ConfinedRelayEvent.Tick) }
                            assertTrue(observed.join().cancelled)
                            assertEquals(0, parent.outgoing.size())
                        } finally {
                            stop(IOException("Stage budget exhausted"))
                        }
                        return@helperCase
                    }
                    advance()
                    if (case == RelayCase.LocalFrameDeadline) {
                        parent.nextError = 11
                        try {
                            assertFailsWith<java.util.concurrent.TimeoutException> { relay.accept(ConfinedRelayEvent.Tick) }
                            assertTrue(parent.now <= localCap.nanos + 25_000_000, "Setup reply exceeded local cap: ${parent.now}")
                            assertEquals(0, parent.outgoing.size())
                        } finally {
                            stop(IOException("Setup reply stalled"))
                        }
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.Tick)
                    val accepted = ConfinedHelperProtocol.encode(Frame.Control(ControlTag.SetupAccepted, binding, cap))
                    assertContentEquals(accepted, parent.outgoing.toByteArray())
                    parent.input(0)
                    parent.receiveRightsAt = 0
                    helper.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, binding, 0, cap)))
                    helper.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Received, binding, 0, cap)))
                    if (case == RelayCase.LocalRootDeadline) {
                        parent.native.onPoll = { _, _, timeout ->
                            parent.now += timeout * 1_000_000L
                            LinuxNative.SyscallResult.Success(if (parent.outgoing.size() > accepted.size) 0L else 1L)
                        }
                        try {
                            assertFailsWith<java.util.concurrent.TimeoutException> {
                                relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Root(RootTag.Offer, binding, 0, cap), cap))
                            }
                            assertTrue(parent.now <= localCap.nanos + 25_000_000, "Root delivery exceeded local cap: ${parent.now}")
                            assertContentEquals(accepted + ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, binding, 0, cap)), parent.outgoing.toByteArray())
                            assertTrue(parent.closed.isEmpty(), "Unreceived rights remain kernel-owned")
                        } finally {
                            stop(IOException("Root delivery stalled"))
                        }
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Root(RootTag.Offer, binding, 0, cap), cap))
                    val expected = ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, binding, 0, cap))
                    assertContentEquals(accepted + expected, parent.outgoing.toByteArray())
                    assertTrue(parent.closed.isEmpty())
                    if (case == RelayCase.RootCloseFailure) {
                        parent.native.fileSystem.onClose = { fd ->
                            parent.closed += fd.value
                            if (fd.value == 813) LinuxNative.SyscallResult.Error(5, -1L) else LinuxNative.SyscallResult.Success(0L)
                        }
                        assertFails { advance() }
                        observed.join().setup.get(2, TimeUnit.SECONDS)
                        val failure = assertFails { relay.accept(ConfinedRelayEvent.Tick) }
                        assertTrue(failure.message.orEmpty().contains("errno=5"))
                        stop(failure)
                        assertEquals(listOf(813), parent.closed)
                        assertContentEquals(accepted + expected, parent.outgoing.toByteArray(), "Failed close must not acknowledge root adoption")
                        return@helperCase
                    }
                    advance()
                    assertEquals(listOf(813), parent.closed)
                    if (case == RelayCase.RootAdoptedCancel || case == RelayCase.RootAdoptedEof) {
                        stop(if (case == RelayCase.RootAdoptedEof) EOFException("Parent vanished after root adoption") else IOException("Cancel after root adoption"))
                        assertContentEquals(accepted + expected, parent.outgoing.toByteArray())
                        assertTrue(helper.closed.isEmpty())
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.Tick)
                    val received = ConfinedHelperProtocol.encode(Frame.Root(RootTag.Received, binding, 0, cap))
                    assertContentEquals(accepted + expected + received, parent.outgoing.toByteArray())
                    parent.input(0)
                    parent.receiveRightsAt = parent.receivedBytes
                    helper.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Expected, binding, 1, cap)))
                    helper.input(ConfinedHelperProtocol.encode(Frame.Root(RootTag.Received, binding, 1, cap)))
                    relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Root(RootTag.Offer, binding, 1, cap), cap))
                    advance()
                    relay.accept(ConfinedRelayEvent.Tick)
                    helper.input(ConfinedHelperProtocol.encode(Frame.Control(ControlTag.Ready, binding, cap)))
                    relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Root(RootTag.Complete, binding, 2, cap), cap))
                    advance()
                    relay.accept(ConfinedRelayEvent.Tick)

                    val opened = ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Opened, binding, 1, cap))
                    helper.receiveRightsAt = helper.receivedBytes + opened.size
                    helper.input(opened + byteArrayOf(0))
                    val readCap = ConfinedDeadline(4_000_000_000)
                    if (case == RelayCase.LocalResultDeadline) {
                        receiveParent(Frame.Read(request(1), cap), readCap)
                    } else {
                        relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Read(request(1), cap), cap))
                    }
                    advance()
                    val beforeReceipt = helper.outgoing.size()
                    if (case == RelayCase.BeforeClaim) {
                        stop(IOException("Cancel before claim"))
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeReceipt, helper.outgoing.size())
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.Tick)
                    if (case == RelayCase.AfterClaim || case == RelayCase.ClaimedRetirement) {
                        if (case == RelayCase.ClaimedRetirement) {
                            session.cancel()
                            assertFails { session.close() }
                            assertFalse(session.confinedRetired)
                            assertEquals(0, reaps.get(), "A claimed relay owner still counts against retirement")
                            assertTrue(helper.closed.isEmpty(), "The reaper cannot close a claimant's descriptor")
                            assertFails { pool.reserveConfined(manifest, cap) }
                            assertFails { pool.prewarm() }
                        }
                        stop(IOException("Cancel after claim"))
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeReceipt, helper.outgoing.size())
                        return@helperCase
                    }
                    if (case == RelayCase.LocalResultDeadline) {
                        val beforeForward = parent.outgoing.size()
                        parent.nextError = 11
                        try {
                            assertFailsWith<java.util.concurrent.TimeoutException> {
                                relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Result(ResultTag.Expected, binding, 1, cap), cap))
                            }
                            assertTrue(parent.now <= readCap.nanos + 25_000_000, "Result forwarding exceeded local cap: ${parent.now}")
                            assertEquals(beforeForward, parent.outgoing.size())
                            assertEquals(beforeReceipt, helper.outgoing.size(), "No upstream receipt means no helper receipt")
                            assertEquals(listOf(814), helper.closed)
                        } finally {
                            stop(IOException("Result forwarding stalled"))
                        }
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Result(ResultTag.Expected, binding, 1, cap), cap))
                    val early = case in setOf(
                        RelayCase.EarlyReceipt,
                        RelayCase.EarlyDuplicate,
                        RelayCase.EarlyWrongBinding,
                        RelayCase.EarlyWrongId,
                        RelayCase.EarlyWrongDeadline,
                        RelayCase.EarlyCancel,
                        RelayCase.EarlyTimeout,
                        RelayCase.EarlyForwardFailure,
                        RelayCase.EarlyForwardException,
                    )
                    if (early) {
                        val beforeDone = parent.outgoing.size()
                        val received = Frame.Result(ResultTag.Received, binding, 1, cap)
                        val invalid = when (case) {
                            RelayCase.EarlyWrongBinding -> received.copy(binding = binding.copy(manifestDigest = "0".repeat(64)))
                            RelayCase.EarlyWrongId -> received.copy(id = 2)
                            RelayCase.EarlyWrongDeadline -> received.copy(deadline = ConfinedDeadline(cap.nanos - 1))
                            else -> null
                        }
                        if (invalid != null) {
                            assertFails { relay.accept(ConfinedRelayEvent.ParentFrame(invalid, cap)) }
                            stop(IOException("Rejected invalid receipt"))
                            assertEquals(listOf(814), helper.closed)
                            assertEquals(beforeReceipt, helper.outgoing.size())
                            assertEquals(beforeDone, parent.outgoing.size())
                            return@helperCase
                        }
                        relay.accept(ConfinedRelayEvent.ParentFrame(received, cap))
                        assertEquals(beforeReceipt, helper.outgoing.size(), "No helper receipt before forwarding completes")
                        assertEquals(beforeDone, parent.outgoing.size(), "No DONE before forwarding completes")
                        assertTrue(helper.closed.isEmpty())
                        when (case) {
                            RelayCase.EarlyDuplicate -> assertFails { relay.accept(ConfinedRelayEvent.ParentFrame(received, cap)) }
                            RelayCase.EarlyTimeout -> {
                                parent.now = cap.nanos
                                assertFailsWith<java.util.concurrent.TimeoutException> { relay.accept(ConfinedRelayEvent.Tick) }
                            }
                            RelayCase.EarlyForwardFailure -> {
                                observed.join().cancel(IOException("forward failed"))
                                observed.join().quiesce()
                                assertFails { relay.accept(ConfinedRelayEvent.Tick) }
                            }
                            RelayCase.EarlyForwardException -> {
                                val ticket = requireNotNull(observed.join().poll(helper.now))
                                assertIs<Command.ResultForwarded>(ticket.command)
                                val cause = IOException("independent forwarding failure")
                                assertTrue(ticket.reply.completeExceptionally(cause))
                                val failure = assertFailsWith<java.util.concurrent.CompletionException> { relay.accept(ConfinedRelayEvent.Tick) }
                                assertSame(cause, failure.cause)
                            }
                            else -> Unit
                        }
                        if (case != RelayCase.EarlyReceipt) {
                            stop(IOException("Stop pending receipt"))
                            assertEquals(listOf(814), helper.closed)
                            assertEquals(beforeReceipt, helper.outgoing.size())
                            assertEquals(beforeDone, parent.outgoing.size())
                            return@helperCase
                        }
                    }
                    advance()
                    relay.accept(ConfinedRelayEvent.Tick)
                    val beforeDone = parent.outgoing.size()
                    assertTrue(helper.closed.isEmpty(), "Claimed intermediate remains live until upstream receipt")
                    relay.accept(ConfinedRelayEvent.Tick)
                    assertEquals(beforeReceipt, helper.outgoing.size())
                    assertEquals(beforeDone, parent.outgoing.size())
                    if (case == RelayCase.AfterForward) {
                        stop(EOFException("Parent vanished before result receipt"))
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeReceipt, helper.outgoing.size())
                        assertEquals(beforeDone, parent.outgoing.size())
                        return@helperCase
                    }

                    if (!early) relay.accept(ConfinedRelayEvent.ParentFrame(Frame.Result(ResultTag.Received, binding, 1, cap), cap))
                    advance()
                    assertTrue(helper.closed.isEmpty(), "UpstreamAccepted must precede the claimant's close")
                    if (case == RelayCase.AfterUpstreamReceipt) {
                        stop(IOException("Cancel before intermediate close"))
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeReceipt, helper.outgoing.size())
                        assertEquals(beforeDone, parent.outgoing.size())
                        return@helperCase
                    }
                    if (case == RelayCase.IntermediateCloseFailure) {
                        helper.native.fileSystem.onClose = { fd ->
                            helper.closed += fd.value
                            if (fd.value == 814) LinuxNative.SyscallResult.Error(13, -1L) else LinuxNative.SyscallResult.Success(0L)
                        }
                        val failure = assertFails { relay.accept(ConfinedRelayEvent.Tick) }
                        assertTrue(failure.message.orEmpty().contains("errno=13"))
                        stop(failure)
                        assertEquals(listOf(814), helper.closed, "A retired integer must not be retried")
                        assertEquals(beforeReceipt, helper.outgoing.size(), "Failed intermediate close must not acknowledge helper receipt")
                        assertEquals(beforeDone, parent.outgoing.size())
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.Tick)
                    assertEquals(listOf(814), helper.closed)
                    assertEquals(beforeReceipt, helper.outgoing.size(), "IntermediateClosed has not been interpreted yet")
                    if (case == RelayCase.HelperEofBeforeDone) {
                        assertFails { advance() }
                        assertIs<ConfinedStage.Failed>(activeRead.get().completed.get(2, TimeUnit.SECONDS))
                        relay.accept(ConfinedRelayEvent.Tick)
                        val failure = assertFails { relay.accept(ConfinedRelayEvent.Tick) }
                        stop(failure)
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeDone, parent.outgoing.size(), "No helper DONE means no parent DONE")
                        return@helperCase
                    }
                    helper.input(ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Done, binding, 1, cap)))
                    advance()
                    relay.accept(ConfinedRelayEvent.Tick)
                    if (case == RelayCase.ParentDoneSendFailure) {
                        parent.nextError = 13
                        val failure = assertFails { relay.accept(ConfinedRelayEvent.Tick) }
                        assertTrue(failure.message.orEmpty().contains("errno=13"))
                        session.close()
                        assertTrue(session.confinedRetired)
                        assertEquals(listOf(814), helper.closed)
                        assertEquals(beforeDone, parent.outgoing.size())
                        assertFalse(activeRead.get().result.isDone)
                        assertEquals(1, reaps.get())
                        assertEquals(1, creates)
                        return@helperCase
                    }
                    relay.accept(ConfinedRelayEvent.Tick)
                    assertContentEquals(
                        ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Received, binding, 1, cap)),
                        helper.outgoing.toByteArray().copyOfRange(beforeReceipt, helper.outgoing.size()),
                    )
                    assertContentEquals(ConfinedHelperProtocol.encode(Frame.Result(ResultTag.Done, binding, 1, cap)), parent.outgoing.toByteArray().copyOfRange(beforeDone, parent.outgoing.size()))
                    relay.accept(ConfinedRelayEvent.Cancel(IOException("Completed staged fixture")))
                    session.close()
                    assertTrue(session.confinedRetired)
                } finally {
                    pool.close()
                }
            }
        }
    }

    @Test
    fun `Session preserves the typed seal rejection through actual helper IO quiescence`() {
        for (errno in listOf(1, 13, 5)) {
            ConfinedHelperChannelTest.Fixture(81120L + errno).use { wire ->
            val permits = java.util.concurrent.Semaphore(0)
            val cap = ConfinedDeadline(5_000_000_000)
            val reason = if (errno == 5) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission
            var creates = 0
            val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                creates++
                object : SocketHelper {
                    override fun execute(
                        operation: SocketOperation,
                        socket: SupervisorOwnedFd,
                    ): SocketHelperResult = error("Legacy work")

                    override fun runConfined(exchange: ConfinedHelperJob) {
                        val io = ConfinedHelperProcessIo(wire.channel, exchange, {}, { _, _ -> }, { _, _ -> error("No read") })
                        while (!exchange.cancelled) {
                            check(permits.tryAcquire(2, TimeUnit.SECONDS))
                            exchange.poll(wire.now)?.let(io::execute)
                        }
                    }

                    override fun close() = Unit
                }
            }

            fun feed(frame: Frame) = wire.input(ConfinedHelperProtocol.encode(frame))
            try {
                pool.prewarm()
                val session = pool.reserveConfined(manifest, cap)
                feed(Frame.Control(ControlTag.SetupAccepted, binding, cap))
                val accepted = session.beginSetup(manifest, cap)
                permits.release()
                assertIs<ConfinedStage.SetupAccepted>(accepted.get(2, TimeUnit.SECONDS))
                repeat(2) { index ->
                    feed(Frame.Root(RootTag.Expected, binding, index, cap))
                    feed(Frame.Root(RootTag.Received, binding, index, cap))
                    val adopted = session.offerRoot(index, owner(), cap)
                    permits.release()
                    assertEquals(ConfinedStage.RootAdopted(binding, index), adopted.get(2, TimeUnit.SECONDS))
                }
                feed(Frame.Rejected(binding, reason, errno, cap))
                val result = session.finishSetup(2, cap)
                permits.release()
                val rejected = assertIs<ConfinedSetupResult.Rejected>(result.get(2, TimeUnit.SECONDS))
                assertEquals(reason, rejected.reason)
                assertEquals(errno, rejected.errno)
                assertEquals(errno, assertIs<ConfinedHelperRejection>(rejected.cause).errno)
                session.close()
                assertTrue(session.confinedRetired)
                assertEquals(1, creates)
            } finally {
                permits.release()
                pool.close()
            }
        }
        }
    }
}
