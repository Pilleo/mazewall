package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeByte
import io.mazewall.ffi.memory.writeInt
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannelTest
import io.mazewall.ffi.networking.SupervisorHelperCredentials
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeoutException

class SocketHelperProcessTest {
    private enum class TimeChange { None, Current, Children, Offset, Missing, Permission }

    private fun timeObserver(
        changedTid: Int,
        change: TimeChange,
        seen: MutableList<Path> = mutableListOf(),
        transform: (Path, String) -> String = { _, text -> text },
    ): HelperSecurityObservation =
        HelperSecurityObservation(
        namespaceKey = { path ->
            seen.add(path)
            val changed = path.getName(3).toString().toInt() == changedTid
            when {
                changed && change == TimeChange.Current && path.fileName.toString() in setOf("time", "time_for_children") -> "(dev=4,ino=7)"
                changed && change == TimeChange.Children && path.fileName.toString() == "time_for_children" -> "(dev=4,ino=7)"
                changed && change == TimeChange.Missing && path.fileName.toString() == "time" -> throw NoSuchFileException(path.toString())
                changed && change == TimeChange.Permission && path.fileName.toString() == "time" -> throw AccessDeniedException(path.toString())
                else -> "(dev=4,ino=5)"
            }
        },
        readBounded = { path, _ ->
            val pid = path.getName(1).toString().toInt()
            val text = when (path.fileName.toString()) {
                "stat" -> "$pid (fixture) S " + "0 ".repeat(18) + "17 0\n"
                "status" ->
                    "Tgid:\t$pid\nPid:\t${path.parent.fileName}\nUid:\t1 1 1 1\nGid:\t2 2 2 2\nGroups:\t2\n" +
                    "CapInh:\t0000000000000000\nCapPrm:\t0000000000000000\nCapEff:\t0000000000000000\n" +
                    "CapBnd:\t0000000000000000\nCapAmb:\t0000000000000000\nNoNewPrivs:\t0\nSeccomp:\t0\nSeccomp_filters:\t0\n"
                "timens_offsets" -> "monotonic 0 ${if (pid == changedTid && change == TimeChange.Offset) 1 else 0}\nboottime 0 0\n"
                "uid_map", "gid_map" -> "0 0 4294967295\n"
                "mountinfo" -> "23 1 0:1 / / rw - tmpfs tmpfs rw\n"
                else -> error("Unexpected observation $path")
            }
            transform(path, text).toByteArray()
        },
    )

    @Test
    fun `only the exact known Tier1 owner layer permits helper setup forwarding`() {
        val bootstrap = timeObserver(-1, TimeChange.None)
        val parent = bootstrap.observeAncestry(Pid(123), Tid(333), HelperSecurityObservation.Lineage.Bootstrap)
        val child = bootstrap.observeAncestry(Pid(555), Tid(555), HelperSecurityObservation.Lineage.Bootstrap)

        fun ownerObserver(
            filters: Int,
            nnp: Int,
            groups: String,
        ) = timeObserver(-1, TimeChange.None, transform = { path, text ->
            if (path.toString() == "/proc/777/task/777/status") {
                text
                    .replace("NoNewPrivs:\t0", "NoNewPrivs:\t$nnp")
                    .replace("Seccomp:\t0", "Seccomp:\t2")
                    .replace("Seccomp_filters:\t0", "Seccomp_filters:\t$filters")
                    .replace("Groups:\t2", "Groups:\t$groups")
            } else {
                text
            }
        })
        val base = ConfinedHelperTestData.manifest()
        val deadline = ConfinedDeadline(2_000_000_000)
        for ((filters, nnp, groups) in listOf(Triple(1, 1, "2"), Triple(0, 1, "2"), Triple(2, 1, "2"), Triple(1, 0, "2"), Triple(1, 1, "2 3"))) {
            val observedOwner = ownerObserver(filters, nnp, groups)
            val owner = observedOwner.observeAncestry(Pid(777), Tid(777), HelperSecurityObservation.Lineage.Tier1)
            val manifest = ConfinedManifest.create(base.binding.owner.copy(ownerPid = Pid(777), ownerStartTicks = 17), owner.ancestry, base.capabilities)
            ConfinedHelperChannelTest.Fixture(81185).use { wire ->
                wire.native.process.tid = Tid(333)
                val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-tier1-owner-", "helper.sock")
                val process = MockProcess(555)
                val helper = SocketHelperProcess(process, wire.socket, endpoint, wire.native, SocketHelperProcess.ConfinedStartup(parent, child), observedOwner)
                val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay, deadline)
                val accepted = job.submit(ConfinedHelperJob.Command.BeginSetup(manifest, deadline))
                wire.input(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, manifest.binding, deadline)))
                try {
                    val failure = kotlin.test.assertFails { helper.runConfined(job) }
                    if (filters == 1 && nnp == 1 && groups == "2") {
                        assertEquals(ConfinedStage.SetupAccepted(manifest.binding), accepted.getNow(null), failure.toString())
                        assertArrayEquals(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, deadline)), wire.outgoing.toByteArray())
                    } else {
                        assertEquals(ConfinedRejectReason.Ancestry, kotlin.test.assertIs<ConfinedHelperRejection>(failure).reason)
                        assertEquals(0, wire.outgoing.size())
                        assertFalse(accepted.isDone)
                    }
                    assertTrue(process.isAlive && wire.closed.isEmpty())
                    job.cancel(failure)
                    job.quiesce()
                } finally {
                    helper.close()
                }
                assertFalse(process.isAlive)
                assertEquals(listOf(81185), wire.closed)
            }
        }
    }

    @Test
    fun `actual startup observes spawning and acquired child domains before authority`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyStartupTimeDomains")
    }

    private fun verifyStartupTimeDomains() {
        val classpath = Files.createTempFile("mw-time-domain-", ".jar")
        try {
            for (task in listOf(333, 555)) {
                for (change in TimeChange.entries) {
                val native = MockNativeEngine()
                native.process.tid = Tid(333)
                native.networking.socketResult = LinuxNative.SyscallResult.Success(81180)
                native.networking.acceptResult = LinuxNative.SyscallResult.Success(81181)
                native.pollResult = LinuxNative.SyscallResult.Success(1)
                native.onSyscall = { nr, _, _, _, buffer, size, _ ->
                    assertEquals(Arch.current().getsockopt.toLong(), nr)
                    (buffer as NativeArg.MemoryArg).value.writeInt(0, 555)
                    buffer.value.writeInt(4, 1)
                    buffer.value.writeInt(8, 2)
                    (size as NativeArg.MemoryArg).value.writeInt(0, 12)
                    LinuxNative.SyscallResult.Success(0)
                }
                val closed = mutableListOf<Int>()
                native.fileSystem.onClose = {
                    closed += it.value
                    LinuxNative.SyscallResult.Success(0)
                }
                LinuxNative.setEngine(native)
                val child = MockProcess(555)
                val seen = mutableListOf<Path>()
                var launches = 0
                val start = {
                    SocketHelperProcess.startConfined(classpath.toString(), timeObserver(task, change, seen)) { builder ->
                        launches++
                        assertTrue(seen.any { it.toString().endsWith("/task/333/ns/time_for_children") })
                        assertEquals(ProcessBuilder.Redirect.from(java.io.File("/dev/null")), builder.redirectInput())
                        child
                    }
                }
                if (change == TimeChange.None) {
                    start().use { assertTrue(child.isAlive) }
                } else {
                    assertThrows(Exception::class.java) { start() }
                }
                assertEquals(if (task == 333 && change != TimeChange.None) 0 else 1, launches, "$task/$change")
                if (launches == 1) assertFalse(child.isAlive, "Failed acquisition must retire its same child: $task/$change")
                assertEquals(if (launches == 1) setOf(81180, 81181) else setOf(81180), closed.toSet())
                assertEquals(closed.size, closed.toSet().size)
            }
            }
        } finally {
            Files.delete(classpath)
        }
    }

    @Test
    fun `operation thread and child time changes refuse before first setup or root send`() {
        val pid = Pid(ProcessHandle.current().pid().toInt())
        val baseline = timeObserver(-1, TimeChange.None)
        val parent = baseline.observeAncestry(pid, Tid(pid.value), HelperSecurityObservation.Lineage.Bootstrap)
        val child = baseline.observeAncestry(Pid(555), Tid(555), HelperSecurityObservation.Lineage.Bootstrap)
        for (task in listOf(333, 555)) {
            for (change in TimeChange.entries.filter { it != TimeChange.None }) {
                ConfinedHelperChannelTest.Fixture(81182).use { wire ->
            wire.native.process.tid = Tid(333)
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-operation-time-", "helper.sock")
            val process = MockProcess(555)
            val helper = SocketHelperProcess(process, wire.socket, endpoint, wire.native, SocketHelperProcess.ConfinedStartup(parent, child), timeObserver(task, change))
            val base = ConfinedHelperTestData.manifest()
            val manifest = ConfinedManifest.create(base.binding.owner.copy(ownerPid = pid, ownerStartTicks = 17), parent.ancestry, base.capabilities)
            val deadline = ConfinedDeadline(2_000_000_000)
            val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay, deadline)
            job.submit(ConfinedHelperJob.Command.BeginSetup(manifest, deadline))
            try {
                val failure = assertThrows(ConfinedHelperRejection::class.java) { helper.runConfined(job) }
                assertEquals(if (change == TimeChange.Permission) ConfinedRejectReason.Permission else ConfinedRejectReason.Ancestry, failure.reason)
                assertEquals(if (change == TimeChange.Permission) 13 else null, failure.errno)
                assertEquals(0, wire.outgoing.size())
                assertTrue(process.isAlive && wire.closed.isEmpty())
                job.cancel(failure)
                job.quiesce()
            } finally {
                helper.close()
            }
            assertFalse(process.isAlive)
            assertEquals(listOf(81182), wire.closed)
        }
            }
        }
    }

    @Test
    fun `failed startup reap stays owned by the spent pool until explicit close retries`() {
        for (fatal in listOf(false, true)) {
            ConfinedHelperChannelTest.Fixture(81130L + if (fatal) 1 else 0).use { wire ->
            val startup: Throwable = if (fatal) AssertionError("controlled startup") else java.io.IOException("controlled startup")
            val reapFailure = IllegalStateException("controlled startup reap")
            var reaps = 0
            var creates = 0
            val child = object : MockProcess(81130) {
                override fun destroyForcibly(): Process {
                    if (++reaps <= 2) throw reapFailure
                    destroy()
                    return this
                }
            }
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-startup-retention-", "helper.sock")
            val pool = SocketHelperPool(PoolProvisioning.PrestartedConfined) {
                creates++
                SocketHelperProcess.rollbackStartup(startup, child, wire.socket, null, endpoint, wire.native)
            }
            try {
                val failure = assertThrows(Exception::class.java) { pool.prewarm() }
                assertTrue(startup.suppressed.any { it === reapFailure })
                assertEquals(1, reaps)
                assertTrue(child.isAlive)
                assertThrows(IllegalStateException::class.java) { pool.prewarm() }
                assertThrows(IllegalStateException::class.java) { pool.reserveConfined(ConfinedHelperTestData.manifest(), ConfinedDeadline(5_000_000_000)) }
                assertSame(reapFailure, assertThrows(IllegalStateException::class.java) { pool.close() })
                assertTrue(child.isAlive)
                assertEquals(2, reaps)
                pool.close()
                assertFalse(child.isAlive)
                assertEquals(3, reaps)
                pool.close()
                assertEquals(3, reaps)
                assertEquals(1, creates)
                assertEquals(listOf(wire.socket.value), wire.closed)
                assertSame(startup, failure.cause)
            } finally {
                child.setAlive(false)
                pool.close()
                endpoint.close()
            }
        }
        }
    }

    @Test
    fun `confined process loop expires idle setup without pipe access or premature channel release`() =
        ConfinedHelperChannelTest.Fixture(81106).use { wire ->
        val pid = ProcessHandle.current().pid()
        val real = LinuxNative.snapshot()
        val ancestry = HelperSecurityObservation().observeAncestry(Pid(pid.toInt()), real.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        var pipeAccess = 0
        val process = object : MockProcess(pid) {
            override fun getInputStream(): java.io.InputStream {
                pipeAccess++
                return super.getInputStream()
            }

            override fun getOutputStream(): java.io.OutputStream {
                pipeAccess++
                return super.getOutputStream()
            }
        }
        val native = object : NativeEngine by wire.native {
            override val process = real.process
        }
        val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-confined-test-", "helper.sock")
        val helper = SocketHelperProcess(process, wire.socket, endpoint, native, SocketHelperProcess.ConfinedStartup(ancestry, ancestry))
        val job = ConfinedHelperJob(ConfinedHelperTestData.manifest(), ConfinedDelivery.Relay, ConfinedDeadline(1_100_000_000))
        wire.pollResult = 0L
        try {
            val failure = assertThrows(TimeoutException::class.java) { helper.runConfined(job) }
            job.cancel(failure)
            job.quiesce()
            val rejected = job.setup.get(1, java.util.concurrent.TimeUnit.SECONDS) as ConfinedSetupResult.Rejected
            assertEquals(ConfinedRejectReason.Timeout, rejected.reason)
            assertTrue(wire.now <= 1_125_000_000)
            assertEquals(0, pipeAccess)
            assertEquals(0, wire.outgoing.size())
            assertTrue(wire.socket.isLiveForIo())
            assertTrue(process.isAlive)
            assertThrows(IllegalStateException::class.java) { helper.runConfined(job) }
        } finally {
            helper.close()
        }
        assertFalse(process.isAlive)
        assertFalse(wire.socket.isLiveForIo())
        assertEquals(listOf(81106), wire.closed)
    }

    @Test
    fun `confined process independently checks setup ancestry before transmitting metadata`() {
        for (matched in listOf(false, true)) {
            ConfinedHelperChannelTest.Fixture(81107).use { wire ->
            val pid = Pid(ProcessHandle.current().pid().toInt())
            val real = LinuxNative.snapshot()
            val observation = HelperSecurityObservation().observeAncestry(pid, real.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
            val original = ConfinedHelperTestData.manifest()
            val manifest = ConfinedManifest.create(
                original.binding.owner.copy(ownerPid = pid, ownerStartTicks = observation.identity.startTimeTicks),
                if (matched) observation.ancestry else original.ancestry,
                original.capabilities,
            )
            val native = object : NativeEngine by wire.native {
                override val process = real.process
            }
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-confined-test-", "helper.sock")
            val helper = SocketHelperProcess(MockProcess(pid.value.toLong()), wire.socket, endpoint, native, SocketHelperProcess.ConfinedStartup(observation, observation))
            val cap = ConfinedDeadline(2_000_000_000)
            val job = ConfinedHelperJob(manifest, ConfinedDelivery.Relay, cap)
            val accepted = job.submit(ConfinedHelperJob.Command.BeginSetup(manifest, cap))
            wire.input(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Control(ConfinedHelperProtocol.ControlTag.SetupAccepted, manifest.binding, cap)))
            wire.afterReceive = { if (wire.incoming.isEmpty()) wire.pollResult = 0L }
            try {
                val failure = assertThrows(Exception::class.java) { helper.runConfined(job) }
                if (matched) {
                    assertInstanceOf(TimeoutException::class.java, failure)
                    assertEquals(ConfinedStage.SetupAccepted(manifest.binding), accepted.get(1, java.util.concurrent.TimeUnit.SECONDS))
                    assertArrayEquals(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, cap)), wire.outgoing.toByteArray())
                } else {
                    assertEquals(ConfinedRejectReason.Ancestry, assertInstanceOf(ConfinedHelperRejection::class.java, failure).reason)
                    assertEquals(0, wire.outgoing.size())
                }
                job.cancel(failure)
                job.quiesce()
            } finally {
                helper.close()
            }
        }
        }
    }

    @Test
    fun `confined process preserves independently observed permission failure before setup effects`() {
        for (errno in listOf(1, 13)) {
            ConfinedHelperChannelTest.Fixture(81150L + errno).use { wire ->
            val pid = Pid(ProcessHandle.current().pid().toInt())
            val real = LinuxNative.snapshot()
            val observation = HelperSecurityObservation().observeAncestry(pid, real.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
            val denied = ConfinedHelperRejection(ConfinedRejectReason.Permission, errno)
            val observer = HelperSecurityObservation(readBounded = { _, _ -> throw denied })
            val native = object : NativeEngine by wire.native {
                override val process = real.process
            }
            val endpoint = PrivateUnixEndpoint.create(RealProcessLauncher, "mw-confined-permission-", "helper.sock")
            val helper = SocketHelperProcess(MockProcess(pid.value.toLong()), wire.socket, endpoint, native, SocketHelperProcess.ConfinedStartup(observation, observation), observer)
            val cap = ConfinedDeadline(2_000_000_000)
            val job = ConfinedHelperJob(ConfinedHelperTestData.manifest(), ConfinedDelivery.Relay, cap)
            job.submit(ConfinedHelperJob.Command.BeginSetup(job.manifest, cap))
            try {
                val failure = assertThrows(ConfinedHelperRejection::class.java) { helper.runConfined(job) }
                assertSame(denied, failure)
                job.cancel(failure)
                job.quiesce()
                val rejected = assertInstanceOf(ConfinedSetupResult.Rejected::class.java, job.setup.join())
                assertEquals(ConfinedRejectReason.Permission, rejected.reason)
                assertEquals(errno, rejected.errno)
                assertEquals(0, wire.outgoing.size())
            } finally {
                job.cancel(denied)
                job.quiesce()
                helper.close()
            }
        }
        }
    }

    @Test
    fun `peer credentials use exact UAPI widths and preserve unsigned UID GID`() {
        val native = MockNativeEngine()
        native.onSyscall = { nr, _, level, option, buffer, size, _ ->
            assertEquals(Arch.current().getsockopt.toLong(), nr)
            assertEquals(1L, level.asLong)
            assertEquals(17L, option.asLong)
            val credential = (buffer as NativeArg.MemoryArg).value
            assertEquals(12L, credential.byteSize())
            credential.writeInt(0, 42)
            credential.writeInt(4, -1)
            credential.writeInt(8, Int.MIN_VALUE)
            (size as NativeArg.MemoryArg).value.writeInt(0, 12)
            LinuxNative.SyscallResult.Success(0L)
        }
        val socket = (SupervisorDescriptorResult.from(native.networking.socket(1, 1, 0), native) as SupervisorDescriptorResult.Opened).descriptor
        socket.use {
            assertEquals(SupervisorHelperCredentials.PeerCredentials(Pid(42), 4294967295L, 2147483648L), SupervisorHelperCredentials.observe(native, it.handle))
            SupervisorHelperCredentials.verify(native, it.handle, 42)
            assertThrows(IllegalStateException::class.java) { SupervisorHelperCredentials.verify(native, it.handle, 43) }
        }
    }

    @Test
    fun `peer credential malformed lengths invalid PID and permission errors refuse`() {
        for ((pid, size) in listOf(42 to 11, 42 to 13, 0 to 12, -1 to 12)) {
            val native = MockNativeEngine()
            native.onSyscall = { _, _, _, _, buffer, length, _ ->
                (buffer as NativeArg.MemoryArg).value.writeInt(0, pid)
                (length as NativeArg.MemoryArg).value.writeInt(0, size)
                LinuxNative.SyscallResult.Success(0L)
            }
            val socket = (SupervisorDescriptorResult.from(native.networking.socket(1, 1, 0), native) as SupervisorDescriptorResult.Opened).descriptor
            socket.use { assertThrows(IllegalStateException::class.java) { SupervisorHelperCredentials.observe(native, it.handle) } }
        }
        for (errno in listOf(1, 13)) {
            val native = MockNativeEngine().also { it.syscallResult = LinuxNative.SyscallResult.Error(errno, -1L) }
            val socket = (SupervisorDescriptorResult.from(native.networking.socket(1, 1, 0), native) as SupervisorDescriptorResult.Opened).descriptor
            socket.use {
                val failure = assertThrows(ConfinedHelperRejection::class.java) { SupervisorHelperCredentials.observe(native, it.handle) }
                assertEquals(ConfinedRejectReason.Permission, failure.reason)
                assertEquals(errno, failure.errno)
            }
        }
    }

    @Test
    fun `every concurrent close waits until its child is reaped`() {
        repeat(8) {
            val before = ProcessHandle
                .current()
                .children()
                .map { it.pid() }
                .toList()
                .toSet()
            val helper = SocketHelperProcess.start()
            val ready = java.util.concurrent.CyclicBarrier(3)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = (1..2).map {
                Thread.ofPlatform().start {
                    try {
                        ready.await()
                        helper.close()
                        assertTrue(ProcessHandle.current().children().noneMatch { it.pid() !in before && it.isAlive }, "close returned before child exit")
                    } catch (failure: Throwable) {
                        errors.add(failure)
                    }
                }
            }
            ready.await()
            threads.forEach {
                it.join(10000)
                assertFalse(it.isAlive)
            }
            assertTrue(errors.isEmpty(), errors.toString())
        }
    }

    @Test
    fun `helper connects original stream and datagram descriptions and remains reusable`() {
        SocketHelperProcess.start().use { helper ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                socket(1).use { original ->
                    assertEquals(SocketHelperResult.Connected, helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    server.accept().use { peer ->
                        peer.soTimeout = 5000
                        write(original, 42)
                        assertEquals(42, peer.getInputStream().read())
                    }
                }
            }
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 5000
                socket(2).use { original ->
                    assertEquals(SocketHelperResult.Connected, helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    write(original, 43)
                    val packet = DatagramPacket(ByteArray(1), 1)
                    server.receive(packet)
                    assertEquals(43, packet.data[0].toInt())
                }
            }
        }
    }

    @Test
    fun `nonblocking connect retains EINPROGRESS rather than creating a blocking stream`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            SocketHelperProcess.start().use { helper ->
                socket(1 or 2048).use { original ->
                    assertEquals(SocketHelperResult.Failed(115), helper.execute(SocketOperation.Connect(address(server.localPort)), original))
                    server.accept().close()
                }
            }
        }
    }

    @Test
    fun `protocol roundtrip is bounded and rejects invalid tags and lengths`() {
        val bytes = ByteArrayOutputStream()
        SocketHelperProtocol.writeOperation(DataOutputStream(bytes), SocketOperation.Connect(address(1234)))
        val decoded = SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) as SocketOperation.Connect
        assertArrayEquals(address(1234), decoded.address)
        for (malformed in listOf(byteArrayOf(99), byteArrayOf(1, 0, 0, 0, -1), byteArrayOf(1, 0, 0, 1, 0))) {
            assertThrows(java.io.IOException::class.java) { SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(malformed))) }
        }
    }

    private fun socket(type: Int): SupervisorOwnedFd =
        (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, type or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor

    private fun write(
        socket: SupervisorOwnedFd,
        byte: Int,
    ) {
        NativeArena.ofConfined().use { arena ->
            val data = arena.allocate(1)
            data.writeByte(0, byte.toByte())
            val result = LinuxNative.memory.write(socket.handle, data, 1)
            assertEquals(1L, (result as LinuxNative.SyscallResult.Success).value)
        }
    }

    private fun address(port: Int): ByteArray = byteArrayOf(2, 0, (port shr 8).toByte(), port.toByte(), 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0)
}
