package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readString
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.ffi.networking.ConfinedHelperChannelTest
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfinedHelperConfinementTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `failed health read retains native errno before confinement or readiness`() {
        io.mazewall.enforcer.api.IsolatedProcessTester
            .runIsolatedMethod(javaClass.name, "verifyHealthFailure", directory.toString())
    }

    private fun verifyHealthFailure(path: String) {
        val real = LinuxNative.snapshot()
        val application = Path.of(path)
        val support = Files.writeString(application.resolve("health.jar"), "health")
        for (errno in listOf(1, 13, 5)) {
            var reads = 0
            val engine = object : NativeEngine by real {
                override val memory = object : io.mazewall.platform.native.NativeMemory by real.memory {
                    override fun read(
                        fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                        buf: ManagedSegment,
                        count: Long,
                    ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                        assertEquals(1L, count)
                        reads++
                        return LinuxNative.SyscallResult.Error(errno, -1L)
                    }
                }
            }
            LinuxNative.setEngine(engine)
            val native = LinuxNative.snapshot()
            val observer = HelperSecurityObservation()
            PreparedPath.open(application, native).use { root ->
                PreparedPath.open(support, native).use { runtime ->
                    val base = ConfinedHelperTestData.manifest()
                    val capabilities = listOf(
                        base.capabilities[0].copy(identity = observer.observeRoot(root.descriptor)),
                        base.capabilities[1].copy(
                            identity = observer.observeRoot(runtime.descriptor),
                            contentDigest = java.util.HexFormat
                                .of()
                                .formatHex(
                                    java.security.MessageDigest
                                    .getInstance("SHA-256")
                                    .digest("health".toByteArray()),
                                ),
                        ),
                    )
                    val manifest = ConfinedManifest.create(base.binding.owner, base.ancestry, capabilities)
                    ConfinedHelperConfinement(native, observer, -1).use { confinement ->
                        confinement.adopt(
                            capabilities[0],
                            io.mazewall.ffi.internal
                            .duplicateHelperResource(root.descriptor, native),
                        )
                        confinement.adopt(
                            capabilities[1],
                            io.mazewall.ffi.internal
                            .duplicateHelperResource(runtime.descriptor, native),
                        )
                        val caught = assertFails {
                            confinement.seal(manifest, ConfinedDeadline.mint(native) { 5_000_000_000L }) { error("Health failure must precede installation") }
                        }
                        assertEquals(1, reads, caught.toString())
                        val failure = assertIs<ConfinedHelperRejection>(caught, caught.toString())
                        assertEquals(if (errno == 5) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission, failure.reason)
                        assertEquals(errno, failure.errno)
                        assertFails { confinement.open(ConfinedReadRequest(manifest.binding, 1, HelperPurpose.Application, 0, "health.jar")) }
                    }
                    assertTrue(root.descriptor.isLiveForIo() && runtime.descriptor.isLiveForIo())
                }
            }
        }
    }

    @Test
    fun `Main channel cleanup preserves the primary ordinary or fatal failure`() {
        io.mazewall.enforcer.api.IsolatedProcessTester
            .runIsolatedMethod(javaClass.name, "verifyMainCleanup")
    }

    private fun verifyMainCleanup() {
        for (fatal in listOf(false, true)) {
            val failure: Throwable = if (fatal) AssertionError("controlled fatal authentication") else ConfinedHelperRejection(ConfinedRejectReason.Permission, 13)
            val closeFailure = IllegalStateException("controlled channel close")
            val native = io.mazewall.enforcer.api
                .MockNativeEngine()
            var closes = 0
            native.networking.socketResult = LinuxNative.SyscallResult.Success(81140L + if (fatal) 1 else 0)
            native.onSyscall = { nr, _, _, _, _, _, _ ->
                check(
                    nr == io.mazewall.platform.syscall.Arch
                    .current()
                    .getsockopt
                    .toLong(),
                )
                throw failure
            }
            native.fileSystem.onClose = {
                closes++
                throw closeFailure
            }
            LinuxNative.setEngine(native)
            val parent = ProcessHandle
                .current()
                .parent()
                .orElseThrow()
                .pid()
            assertSame(failure, assertFails { SocketHelperMain.main(arrayOf("/controlled-helper.sock", parent.toString(), "--confined-read-v1")) })
            assertTrue(failure.suppressed.any { it === closeFailure })
            assertEquals(1, closes)
        }
    }

    private val status = "Name:\thelper\nTgid:\t123\nPid:\t321\nUid:\t4294967295\t4294967295\t4294967295\t4294967295\n" +
        "Gid:\t2\t2\t2\t2\nGroups:\t3 2\n" +
        "CapInh:\t0000000000000000\nCapPrm:\t0000000000000000\nCapEff:\t0000000000000000\n" +
        "CapBnd:\t000001ffffffffff\nCapAmb:\t0000000000000000\nNoNewPrivs:\t0\nSeccomp:\t0\nSeccomp_filters:\t0\n"
    private val offsets = "monotonic 0 0\nboottime 0 0\n"
    private val stat = "123 (helper (name)) S " + "0 ".repeat(18) + "17 0\n"
    private val mount = "23 1 0:1 / / rw,relatime - tmpfs tmpfs rw\n"

    private fun observer(change: (Path, String) -> String = { _, value -> value }): HelperSecurityObservation =
        HelperSecurityObservation(
        namespaceKey = { if (it.fileName.toString() == "root") "(dev=1,ino=2)" else "(dev=4,ino=5)" },
        readBounded = { path, limit ->
            val text = when (path.fileName.toString()) {
                "status" -> status
                "stat" -> stat
                "timens_offsets" -> offsets
                "uid_map", "gid_map" -> "         0          0 4294967295\n"
                "mountinfo" -> mount
                else -> error("Unexpected observation $path")
            }
            assertTrue(limit in 256..4 * 1024 * 1024)
            change(path, text).toByteArray()
        },
    )

    @Test
    fun `ancestry observes exact task unsigned IDs namespaces and owner incarnation`() {
        val seen = mutableListOf<Path>()
        val observation = observer { path, text ->
            seen.add(path)
            text
        }.observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap)
        assertEquals(ProcessIdentity(Pid(123), 17), observation.identity)
        assertTrue(observation.ancestry.canonical.startsWith("uid=4294967295,4294967295,4294967295,4294967295\ngid=2,2,2,2\ngroups=2,3\n"))
        assertTrue(observation.ancestry.canonical.endsWith("noNewPrivs=0\nseccomp=0\nseccompFilters=0\nlineage=bootstrap-v1\n"))
        assertTrue(Path.of("/proc/123/task/321/status") in seen)
        assertTrue(Path.of("/proc/123/task/321/mountinfo") in seen)
        assertTrue(Path.of("/proc/123/stat") in seen)
        observation.time.requireCommon(observation.time)
    }

    @Test
    fun `missing duplicate changed task malformed maps and permission observations refuse`() {
        val changes: List<(Path, String) -> String> = listOf(
            { path, text -> if (path.fileName.toString() == "status") text.replace("Uid:", "Missing:") else text },
            { path, text -> if (path.fileName.toString() == "status") text + "Uid:\t1 1 1 1\n" else text },
            { path, text -> if (path.fileName.toString() == "status") text.replace("Pid:\t321", "Pid:\t322") else text },
            { path, text -> if (path.fileName.toString() == "status") text.replace("4294967295", "4294967296") else text },
            { path, text -> if (path.fileName.toString() == "uid_map") "0 0 garbage\n" else text },
            { path, text -> if (path.fileName.toString() == "gid_map") "" else text },
            { path, text -> if (path.fileName.toString() == "mountinfo") "truncated mount\n" else text },
            { path, text -> if (path.fileName.toString() == "stat") text.replace("123 (", "124 (") else text },
            { path, text -> if (path.fileName.toString() == "status") "x".repeat(65537) else text },
            { path, text -> if (path.fileName.toString() == "timens_offsets") text.replace("monotonic 0 0", "monotonic 0 1") else text },
        )
        changes.forEach { change -> assertFails { observer(change).observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap) } }
        val denied = AccessDeniedException("status")
        val failure = assertFails { observer { _, _ -> throw denied }.observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap) }
        assertSame(denied, failure)
    }

    @Test
    fun `known Tier1 normalization preserves every unrelated ancestry field and common time domain`() {
        val bootstrap = observer().observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap)
        val tier1 = observer { path, text ->
            if (path.fileName.toString() == "status") {
                text
                    .replace("NoNewPrivs:\t0", "NoNewPrivs:\t1")
                    .replace("Seccomp:\t0", "Seccomp:\t2")
                    .replace("Seccomp_filters:\t0", "Seccomp_filters:\t1")
            } else {
                text
            }
        }.observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Tier1)
        tier1.requireLineage(bootstrap, HelperSecurityObservation.Lineage.Tier1)
        bootstrap.requireLineage(bootstrap, HelperSecurityObservation.Lineage.Bootstrap)
        val changes = mapOf(
            "uid" to "1,1,1,1",
            "gid" to "1,1,1,1",
            "groups" to "2",
            "capInh" to "0000000000000001",
            "capPrm" to "0000000000000001",
            "capEff" to "0000000000000001",
            "capBnd" to "0000000000000000",
            "capAmb" to "0000000000000001",
            "pidNs" to "(dev=4,ino=6)",
            "mountNs" to "(dev=4,ino=6)",
            "userNs" to "(dev=4,ino=6)",
            "timeNs" to "(dev=4,ino=6)",
            "timeChildNs" to "(dev=4,ino=6)",
            "uidMap" to "f".repeat(64),
            "gidMap" to "f".repeat(64),
            "rootKey" to "(dev=1,ino=3)",
            "mountViewDigest" to "f".repeat(64),
            "noNewPrivs" to "0",
            "seccomp" to "0",
            "seccompFilters" to "2",
            "lineage" to "bootstrap-v1",
        )
        changes.forEach { (key, value) ->
            val changed = tier1.ancestry.canonical
                .lines()
                .joinToString("\n") { row -> if (row.startsWith("$key=")) "$key=$value" else row }
            assertFails(key) { tier1.copy(ancestry = HelperAncestry.parse(changed)).requireLineage(bootstrap, HelperSecurityObservation.Lineage.Tier1) }
        }
        assertFails { tier1.copy(time = tier1.time.copy(forChildren = "(dev=4,ino=6)")).requireLineage(bootstrap, HelperSecurityObservation.Lineage.Tier1) }
        assertFails { tier1.copy(time = tier1.time.copy(monotonicOffsetNanos = 1)).requireLineage(bootstrap, HelperSecurityObservation.Lineage.Tier1) }
        assertFails { tier1.requireLineage(tier1, HelperSecurityObservation.Lineage.Tier1) }
        assertFails { tier1.requireLineage(bootstrap, HelperSecurityObservation.Lineage.Bootstrap) }
    }

    @Test
    fun `namespace bind mount roots are observed without treating their names as paths`() {
        val namespaceMount = "2516 530 0:4 mnt:[4026532895] /run/ns/test rw - nsfs nsfs rw\n"
        val observation = observer { path, text -> if (path.fileName.toString() == "mountinfo") namespaceMount else text }
            .observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap)
        assertTrue("mountViewDigest=" in observation.ancestry.canonical)
        assertFails {
            observer { path, text -> if (path.fileName.toString() == "mountinfo") namespaceMount.replace(" - nsfs ", " - tmpfs ") else text }
                .observeAncestry(Pid(123), Tid(321), HelperSecurityObservation.Lineage.Bootstrap)
        }
    }

    @Test
    fun `genuine O_PATH identity uses fd metadata and rejects missing or invalid mount info`() {
        val native = LinuxNative.snapshot()
        val file = Files.writeString(directory.resolve("support.jar"), "bytes")
        PreparedPath.open(file, native).use { pin ->
            val observation = HelperSecurityObservation().observeRoot(pin.descriptor)
            assertEquals(HelperObjectKind.Regular, observation.kind)
            assertEquals(Files.readAttributes(pin.descriptorPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString(), observation.key)
            assertTrue(observation.mountId > 0)
            for (info in listOf("", "mnt_id:\t0\nflags:\t010000000\n", "mnt_id:\t1\nflags:\t0\n", "mnt_id:\t1\nmnt_id:\t2\nflags:\t010000000\n")) {
                assertFails { HelperSecurityObservation(readBounded = { _, _ -> info.toByteArray() }).observeRoot(pin.descriptor) }
                assertTrue(pin.descriptor.isLiveForIo())
            }
            Files.move(file, directory.resolve("moved.jar"))
            assertEquals(observation, HelperSecurityObservation().observeRoot(pin.descriptor))
        }
        PreparedPath.open(directory, native).use { pin -> assertEquals(HelperObjectKind.Directory, HelperSecurityObservation().observeRoot(pin.descriptor).kind) }
    }

    @Test
    fun `readable result must be a real readonly regular description rather than an O_PATH pin`() {
        val native = LinuxNative.snapshot()
        val file = Files.writeString(directory.resolve("result"), "allowed")
        val observations = HelperSecurityObservation()
        PreparedPath.open(file, native).use { pin ->
            PreparedPath.open(Path.of("/proc/self/fd"), native).use { proc ->
                val reopen = io.mazewall.ffi.internal
                    .OwnedProcFdReopen(native) { Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java) }
                val result = reopen.reopenRegular(SupervisorOwnedFd(pin.descriptor, native.fileSystem), SupervisorOwnedFd(proc.descriptor, native.fileSystem)) as SupervisorDescriptorResult.Opened
                result.descriptor.use {
                    assertEquals(observations.observeRoot(pin.descriptor).key, observations.observeReadable(it.handle, native))
                }
                assertFails { observations.observeReadable(pin.descriptor, native) }
                assertFails { observations.observeReadable(proc.descriptor, native) }
            }
        }
    }

    @Test
    fun `entropy inventory admits only readonly genuine random devices`() {
        val native = LinuxNative.snapshot()
        val ordinary = Files.writeString(directory.resolve("not-random"), "ordinary")
        val observer = HelperSecurityObservation()
        for ((path, flags, allowed) in listOf(
            Triple(Path.of("/dev/random"), 0, true),
            Triple(Path.of("/dev/urandom"), 0, true),
            Triple(Path.of("/dev/null"), 0, false),
            Triple(ordinary, 0, false),
            Triple(Path.of("/dev/random"), 2, false),
            Triple(Path.of("/dev/random"), NativeConstants.O_PATH, false),
        )) {
            NativeArena.ofConfined().use { arena ->
                val opened = SupervisorDescriptorResult.from(
                    native.fileSystem.open(arena.allocateFrom(path.toString()), OpenFlags(flags or NativeConstants.O_CLOEXEC)),
                    native,
                ) as SupervisorDescriptorResult.Opened
                opened.descriptor.use { owner ->
                    val status = native.raw.fcntl(owner.handle, NativeConstants.F_GETFL, 0).getOrThrow("entropy flags")
                    assertEquals(allowed, observer.isRuntimeEntropy(Path.of("/proc/self/fd/${owner.handle.value}"), status), "$path flags=$flags")
                }
            }
        }
        assertFails { observer.isRuntimeEntropy(directory.resolve("missing"), 0) }
    }

    @Test
    fun `mapping inventory checks opened object identity across maps and stat device domains`() =
        MappingFixture().use { f ->
        f.checkRow(f.row)
        assertEquals(1, f.opens)
        assertEquals(1, f.closes)
        assertFails {
            f.checkRow(
                f.fields
            .toMutableList()
            .also { it[4] = (f.fields[4].toLong() + 1).toString() }
            .joinToString(" "),
            )
        }
        assertFails {
            f.checkRow(
                f.fields
            .toMutableList()
            .also { it[3] = "invalid" }
            .joinToString(" "),
            )
        }
        f.target = Files.writeString(directory.resolve("wrong-mapping"), "unreviewed")
        assertFails { f.checkRow(f.row) }
        assertEquals(f.opens, f.closes)
        assertTrue(f.pin.descriptor.isLiveForIo())
    }

    @Test
    fun `mapping access errors preserve errno without falling back to the path`() =
        MappingFixture().use { f ->
        for (errno in listOf(1, 13, 5)) {
            f.errno = errno
            val failure = assertIs<ConfinedHelperRejection>(assertFails { f.checkRow(f.row) })
            assertEquals(errno, failure.errno)
            assertEquals(if (errno == 5) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission, failure.reason)
        }
        assertEquals(0, f.opens)
        assertEquals(0, f.closes)
    }

    @Test
    fun `padded maps addresses use the canonical map_files range`() =
        MappingFixture().use { f ->
        f.expectedRange = "1000-2000"
        f.checkRow(
            f.fields
            .toMutableList()
            .also { it[0] = "00001000-00002000" }
            .joinToString(" "),
        )
        assertEquals(1, f.opens)
        assertEquals(1, f.closes)
    }

    @Test
    fun `Main sends exact typed setup rejection and retains the original failure`() {
        val denied = AccessDeniedException("owned-observation")
        for ((failure, reason, errno) in listOf(
            Triple(ConfinedHelperRejection(ConfinedRejectReason.Permission, 1), ConfinedRejectReason.Permission, 1),
            Triple(denied, ConfinedRejectReason.Permission, 13),
            Triple(ConfinedHelperRejection(ConfinedRejectReason.RuntimeClosure, 5), ConfinedRejectReason.RuntimeClosure, 5),
        )) {
            ConfinedHelperChannelTest.Fixture(81108).use { wire ->
            val observer = HelperSecurityObservation()
            ConfinedHelperConfinement(LinuxNative.snapshot(), observer, wire.socket.value).use { confinement ->
                val manifest = ConfinedHelperTestData.manifest()
                val cap = ConfinedDeadline(5_000_000_000)
                wire.input(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, cap)))
                assertSame(failure, assertFails { SocketHelperMain.serveConfined(wire.channel, confinement, observer, wire.native) { throw failure } })
                assertContentEquals(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Rejected(manifest.binding, reason, errno, cap)), wire.outgoing.toByteArray())
                assertTrue(wire.closed.isEmpty())
            }
        }
        }
    }

    @Test
    fun `Main rejection send failure cannot mask the setup failure`() =
        ConfinedHelperChannelTest.Fixture(81109).use { wire ->
        val observer = HelperSecurityObservation()
        ConfinedHelperConfinement(LinuxNative.snapshot(), observer, wire.socket.value).use { confinement ->
            val manifest = ConfinedHelperTestData.manifest()
            val failure = ConfinedHelperRejection(ConfinedRejectReason.Permission, 13)
            wire.input(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, ConfinedDeadline(5_000_000_000))))
            assertSame(
                failure,
                assertFails {
                SocketHelperMain.serveConfined(wire.channel, confinement, observer, wire.native) {
                wire.nextError = 32
                throw failure
            }
            },
            )
            assertEquals(1, failure.suppressed.size)
            assertTrue(
                failure.suppressed
                .single()
                .message
                .orEmpty()
                .contains("errno=32"),
            )
            assertEquals(0, wire.outgoing.size())
        }
    }

    @Test
    fun `Main preserves native close errno in a bound rejection without retrying the retired descriptor`() {
        for (errno in listOf(1, 13, 5)) {
            ConfinedHelperChannelTest.Fixture(81170L + errno).use { wire ->
            val native = io.mazewall.enforcer.api
                .MockNativeEngine()
            native.networking.socketResult = LinuxNative.SyscallResult.Success(81270L + errno)
            val owner = assertIs<SupervisorDescriptorResult.Opened>(SupervisorDescriptorResult.from(native.networking.socket(1, 1, 0), native)).descriptor
            var closes = 0
            native.fileSystem.onClose = {
                closes++
                LinuxNative.SyscallResult.Error(errno, -1L)
            }
            val observer = HelperSecurityObservation()
            ConfinedHelperConfinement(LinuxNative.snapshot(), observer, wire.socket.value).use { confinement ->
                val manifest = ConfinedHelperTestData.manifest()
                val cap = ConfinedDeadline(5_000_000_000)
                wire.input(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Setup(manifest, cap)))
                val failure = assertFails { SocketHelperMain.serveConfined(wire.channel, confinement, observer, wire.native) { owner.close() } }
                assertIs<IllegalStateException>(failure, "Existing close exception compatibility")
                assertFalse(owner.handle.isLiveForIo())
                owner.close()
                assertEquals(1, closes)
                val reason = if (errno == 5) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission
                assertContentEquals(ConfinedHelperProtocol.encode(ConfinedHelperProtocol.Frame.Rejected(manifest.binding, reason, errno, cap)), wire.outgoing.toByteArray())
            }
        }
        }
    }

    private class MappingFixture : AutoCloseable {
        val row = Files.readAllLines(Path.of("/proc/self/maps")).first { line ->
            val fields = line.split(Regex(" +"), limit = 6)
            fields.size == 6 && fields[5].startsWith('/') && !fields[5].endsWith(" (deleted)") && Files.isRegularFile(Path.of(fields[5]))
        }
        val fields = row.split(Regex(" +"), limit = 6)
        private val real = LinuxNative.snapshot()
        val pin = PreparedPath.open(Path.of(fields[5]), real)
        var target = Path.of(fields[5])
        var expectedRange = fields[0].split('-').joinToString("-") { it.trimStart('0').ifEmpty { "0" } }
        var errno = 0
        var opens = 0
        var closes = 0
        private val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun open(
                    path: ManagedSegment,
                    flags: OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    assertEquals("/proc/self/map_files/$expectedRange", path.readString(0))
                    assertEquals(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC, flags.value)
                    if (errno != 0) return LinuxNative.SyscallResult.Error(errno, -1)
                    return NativeArena.ofConfined().use { arena -> real.fileSystem.open(arena.allocateFrom(target.toString()), flags).also { opens++ } }
                }

                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
                    real.fileSystem.close(fd).also { closes++ }
            }
        }
        private val original = ConfinedHelperTestData.manifest()
        private val capability = HelperCapability(1, HelperPurpose.RuntimeSupport, HelperSecurityObservation().observeRoot(pin.descriptor), "e".repeat(64))
        private val manifest: ConfinedManifest = ConfinedManifest.create(original.binding.owner, original.ancestry, listOf(original.capabilities[0], capability))

        fun checkRow(text: String) =
            HelperSecurityObservation(readBounded = { path, _ ->
            assertEquals(Path.of("/proc/self/maps"), path)
            "$text\n".toByteArray()
        }).requireRuntimeMappings(manifest, native)

        override fun close() = pin.close()
    }
}
