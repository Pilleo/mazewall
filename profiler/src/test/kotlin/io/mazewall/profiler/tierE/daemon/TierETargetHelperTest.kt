package io.mazewall.profiler.tierE.daemon

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierETargetHelperTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `installed distribution provides executable target helper`() {
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper")
        assertTrue(Files.isExecutable(launcher), "installed target helper must be executable")
    }

    @Test
    fun `register writes a readable v1 registration for a live process`() {
        val source = Files.writeString(root.resolve("agent.c"), "void mazewall_stack_marker(void) {}\n")
        val library = root.resolve("agent.so")
        val compiler = ProcessBuilder("cc", "-shared", "-fPIC", source.toString(), "-o", library.toString()).inheritIO().start()
        assertEquals(0, compiler.waitFor())
        val output = root.resolve("registration")
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()
        val process = ProcessBuilder(
            launcher.toString(),
            "register",
            "--registration-id",
            "orders",
            "--pid",
            ProcessHandle.current().pid().toString(),
            "--marker-path",
            library.toString(),
            "--session-tag",
            "4d5a",
            "--artifact-file",
            "orders.mzse",
            "--output",
            output.toString(),
        ).redirectErrorStream(true).start()
        val message = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), message)
        val target = TierETargetRegistrationFile.read(output)
        assertEquals("orders", target.registrationId)
        assertEquals(0x4d5a, target.sessionTag)
        assertEquals("orders.mzse", target.artifactFileName)
        assertEquals(
            listOf(
                "version=1",
                "registration_id=orders",
                "pid=${ProcessHandle.current().pid()}",
                "session_tag=4d5a",
                "process_incarnation=${requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(target.pid))}",
                "marker_path=$library",
                "marker_offset=${target.markerOffset.toString(16)}",
                "artifact_file=orders.mzse",
            ),
            Files.readAllLines(output),
        )
    }

    @Test
    fun `v1 attach does not attest local registration id or artifact`() {
        val pid = ProcessHandle.current().pid().toInt()
        val incarnation = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(pid))
        val registration = Files.writeString(
            root.resolve("registration"),
            """
            version=1
            registration_id=orders
            pid=$pid
            session_tag=4d5a
            process_incarnation=$incarnation
            marker_path=/agent.so
            marker_offset=20
            artifact_file=orders.mzse
        """.trimIndent(),
        )
        val otherRegistration = Files.writeString(
            root.resolve("other-registration"),
            Files
                .readString(registration)
                .replace("registration_id=orders", "registration_id=other")
                .replace("artifact_file=orders.mzse", "artifact_file=other.mzse"),
        )
        val socket = root.resolve("control.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val received = mutableListOf<String>()
            val responder = thread(isDaemon = true) {
                repeat(2) {
                    server.accept().use { channel ->
                        received.add(DataInputStream(Channels.newInputStream(channel)).readUTF())
                        DataOutputStream(Channels.newOutputStream(channel)).writeUTF("OK attached pid=$pid")
                    }
                }
            }
            val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()
            for (file in listOf(registration, otherRegistration)) {
                val process = ProcessBuilder(launcher.toString(), "attach", "--registration", file.toString(), "--socket", socket.toString())
                    .redirectErrorStream(true)
                    .start()
                val message = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), message)
            }
            responder.join(5_000)
            // The operator must trust the configured daemon: neither local name appears on wire.
            assertEquals(listOf("ATTACH $pid auto /agent.so 20 4d5a full-stream", "ATTACH $pid auto /agent.so 20 4d5a full-stream"), received)
        }
    }

    @Test
    fun `attach rejects error malformed and wrong pid replies`() {
        val pid = ProcessHandle.current().pid().toInt()
        val incarnation = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(pid))
        val registration = Files.writeString(
            root.resolve("registration"),
            """
            version=1
            registration_id=orders
            pid=$pid
            session_tag=4d5a
            process_incarnation=$incarnation
            marker_path=/agent.so
            marker_offset=20
            artifact_file=orders.mzse
        """.trimIndent(),
        )
        for ((index, reply) in listOf("ERR rejected", "OK", "OK attached pid=${pid + 1}", null).withIndex()) {
            val socket = root.resolve("control-$index.sock")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                val responder = thread(isDaemon = true) {
                    server.accept().use { channel ->
                        DataInputStream(Channels.newInputStream(channel)).readUTF()
                        if (reply != null) DataOutputStream(Channels.newOutputStream(channel)).writeUTF(reply)
                    }
                }
                val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()
                val process = ProcessBuilder(launcher.toString(), "attach", "--registration", registration.toString(), "--socket", socket.toString())
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText()
                assertNotEquals(0, process.waitFor(), reply ?: "EOF")
                responder.join(5_000)
            }
        }
    }

    @Test
    fun `register never replaces existing files symlinks or competing output`() {
        val source = Files.writeString(root.resolve("agent.c"), "void mazewall_stack_marker(void) {}\n")
        val library = root.resolve("agent.so")
        assertEquals(0, ProcessBuilder("cc", "-shared", "-fPIC", source.toString(), "-o", library.toString()).start().waitFor())
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()

        fun command(output: Path): ProcessBuilder =
            ProcessBuilder(
            launcher.toString(),
                "register",
                "--registration-id",
                "orders",
                "--pid",
                ProcessHandle.current().pid().toString(),
            "--marker-path",
                library.toString(),
                "--session-tag",
                "4d5a",
                "--artifact-file",
                "orders.mzse",
                "--output",
                output.toString(),
        ).redirectErrorStream(true)
        val existing = Files.writeString(root.resolve("existing"), "prior")
        val symlink = Files.createSymbolicLink(root.resolve("symlink"), existing)
        for (output in listOf(existing, symlink)) {
            val process = command(output).start()
            process.inputStream.readAllBytes()
            assertNotEquals(0, process.waitFor())
            assertEquals("prior", Files.readString(existing))
        }
        val race = root.resolve("race")
        val first = command(race).start()
        val second = command(race).start()
        first.inputStream.readAllBytes()
        second.inputStream.readAllBytes()
        assertEquals(1, listOf(first.waitFor(), second.waitFor()).count { it == 0 })
        assertEquals("orders", TierETargetRegistrationFile.read(race).registrationId)
    }

    @Test
    fun `register rejects unsafe input and exited process without publishing`() {
        val source = Files.writeString(root.resolve("agent.c"), "void mazewall_stack_marker(void) {}\n")
        val library = root.resolve("agent.so")
        assertEquals(0, ProcessBuilder("cc", "-shared", "-fPIC", source.toString(), "-o", library.toString()).start().waitFor())
        val child = ProcessBuilder("true").start()
        val exitedPid = child.pid()
        child.waitFor()
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()
        val cases = listOf(
            Triple(exitedPid.toString(), "orders.mzse", library),
            Triple(ProcessHandle.current().pid().toString(), "../bad", library),
            Triple(ProcessHandle.current().pid().toString(), "bad=field", library),
            Triple(ProcessHandle.current().pid().toString(), "orders.mzse", Files.copy(library, root.resolve("bad name.so"))),
        )
        cases.forEachIndexed { index, (pid, artifact, marker) ->
            val output = root.resolve("invalid-$index")
            val process = ProcessBuilder(
                launcher.toString(),
                "register",
                "--registration-id",
                "orders",
                "--pid",
                pid,
                "--marker-path",
                marker.toString(),
                "--session-tag",
                "4d5a",
                "--artifact-file",
                artifact,
                "--output",
                output.toString(),
            ).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            assertNotEquals(0, process.waitFor())
            assertTrue(!Files.exists(output))
        }
        val duplicateOutput = root.resolve("duplicate")
        val duplicate = ProcessBuilder(
            launcher.toString(),
            "register",
            "--registration-id",
            "orders",
            "--registration-id",
            "other",
            "--pid",
            ProcessHandle.current().pid().toString(),
            "--marker-path",
            library.toString(),
            "--session-tag",
            "4d5a",
            "--artifact-file",
            "orders.mzse",
            "--output",
            duplicateOutput.toString(),
        ).redirectErrorStream(true).start()
        duplicate.inputStream.readAllBytes()
        assertNotEquals(0, duplicate.waitFor())
        assertTrue(!Files.exists(duplicateOutput))
    }

    @Test
    fun `identity change during preparation prevents final publication`() {
        val source = Files.writeString(root.resolve("agent.c"), "void mazewall_stack_marker(void) {}\n")
        val library = root.resolve("agent.so")
        assertEquals(0, ProcessBuilder("cc", "-shared", "-fPIC", source.toString(), "-o", library.toString()).start().waitFor())
        val output = root.resolve("registration")
        var reads = 0
        assertFailsWith<IllegalArgumentException> {
            register(
                mapOf(
                    "--registration-id" to "orders",
                    "--pid" to ProcessHandle.current().pid().toString(),
                    "--marker-path" to library.toString(),
                    "--session-tag" to "4d5a",
                    "--artifact-file" to "orders.mzse",
                    "--output" to output.toString(),
                ),
            ) {
                reads++
                reads.toLong()
            }
        }
        assertEquals(2, reads)
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `attach rejects stale and duplicate registration before connecting`() {
        val pid = ProcessHandle.current().pid().toInt()
        val incarnation = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(pid))
        val registration = root.resolve("registration")
        val valid = """
            version=1
            registration_id=orders
            pid=$pid
            session_tag=4d5a
            process_incarnation=$incarnation
            marker_path=/agent.so
            marker_offset=20
            artifact_file=orders.mzse
        """.trimIndent()
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-target-helper").toAbsolutePath()
        for (content in listOf(valid.replace("process_incarnation=$incarnation", "process_incarnation=${incarnation + 1}"), "$valid\npid=$pid")) {
            Files.writeString(registration, content)
            val process = ProcessBuilder(launcher.toString(), "attach", "--registration", registration.toString(), "--socket", root.resolve("missing").toString())
                .redirectErrorStream(true)
                .start()
            val message = process.inputStream.bufferedReader().readText()
            assertNotEquals(0, process.waitFor())
            assertTrue(message.contains("stale") || message.contains("duplicate"), message)
        }
    }

    private fun fixture(): Path {
        val data = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        data
            .put(0, 0x7f)
            .put(1, 'E'.code.toByte())
            .put(2, 'L'.code.toByte())
            .put(3, 'F'.code.toByte())
        data.put(4, 2).put(5, 1)
        data.putLong(32, 64).putLong(40, 128)
        data
            .putShort(54, 56)
            .putShort(56, 1)
            .putShort(58, 64)
            .putShort(60, 3)
        data
            .putInt(64, 1)
            .putLong(72, 0)
            .putLong(80, 0x1000)
            .putLong(96, 512)
        data
            .putInt(196, 11)
            .putLong(216, 336)
            .putLong(224, 24)
            .putInt(232, 2)
            .putLong(248, 24)
        data.putLong(280, 384).putLong(288, 32)
        data.putInt(336, 1).putLong(344, 0x11a0)
        val name = "mazewall_stack_marker\u0000".toByteArray()
        data.position(385).put(name)
        return Files.write(root.resolve("fixed-agent.so"), data.array())
    }

    private fun arguments(
        library: Path,
        output: Path,
    ): List<String> =
        listOf(
        "register",
            "--registration-id",
            "orders",
            "--pid",
            ProcessHandle.current().pid().toString(),
        "--marker-path",
            library.toString(),
            "--session-tag",
            "4d5a",
            "--artifact-file",
            "orders.mzse",
            "--output",
            output.toString(),
    )

    @Test
    fun `fixed ELF offset and live proc starttime are written independently`() {
        val output = root.resolve("fixed.reg")
        main(arguments(fixture(), output).toTypedArray())
        val stat = Files.readString(Path.of("/proc/${ProcessHandle.current().pid()}/stat"))
        val independentlyObserved = stat
            .substringAfterLast(')')
            .trim()
            .split(Regex("\\s+"))[19]
            .toLong()
        val target = TierETargetRegistrationFile.read(output)
        assertEquals(0x1a0, target.markerOffset)
        assertEquals(independentlyObserved, target.processIncarnation)
        assertTrue(Files.readAllLines(output).contains("marker_offset=1a0"))
    }

    @Test
    fun `invalid option and value table never changes an existing output`() {
        val library = fixture()
        val output = Files.writeString(root.resolve("protected"), "previous")
        val base = arguments(library, output)

        fun changed(
            key: String,
            value: String,
        ): List<String> = base.toMutableList().apply { this[indexOf(key) + 1] = value }
        val cases = mutableListOf(
            emptyList(),
            base.dropLast(1),
            base + listOf("--unknown", "x"),
            base + listOf("--pid", "1"),
            base.filterNot { it == "--output" || it == output.toString() },
            listOf("unknown"),
        )
        listOf("0", "-1", "abc", "2147483648").forEach { cases += listOf(changed("--pid", it)) }
        listOf("0", "-1", "invalid", "80000000").forEach { cases += listOf(changed("--session-tag", it)) }
        listOf("", ".bad", "bad/id", "a".repeat(65)).forEach { cases += listOf(changed("--registration-id", it)) }
        listOf(".", "..", "../x", "x/y", "x\\y", " x", "x ", "x=y", "x\ny").forEach { cases += listOf(changed("--artifact-file", it)) }
        for (name in listOf("bad name", "bad=path", "bad\npath", "bad\tpath")) {
            val path = Files.copy(library, root.resolve(name))
            cases += listOf(changed("--marker-path", path.toString()))
        }
        cases += listOf(changed("--marker-path", "relative.so"))
        cases.forEach { args ->
            assertFailsWith<Exception>(args.toString()) { main(args.toTypedArray()) }
            assertEquals("previous", Files.readString(output))
        }
    }

    @Test
    fun `observation failures preserve identity before and after temporary creation`() {
        val library = fixture()
        val output = root.resolve("target.reg")
        val values = arguments(library, output).drop(1).chunked(2).associate { it[0] to it[1] }
        val start = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(ProcessHandle.current().pid().toInt()))
        for (after in listOf(false, true)) {
            for (failure in listOf(AccessDeniedException("proc", null, "EACCES"), java.io.IOException("I/O"), IllegalArgumentException("malformed"), LinkageError("fatal"))) {
                var calls = 0
                val caught = assertFailsWith<Throwable> {
                    register(values) {
                        calls++
                        if (!after || calls == 2) {
                            if (after) {
                                val temp = Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".tmp") }.findFirst().orElseThrow() }
                                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temp))
                            }
                            throw failure
                        }
                        start
                    }
                }
                assertSame(failure, caught)
                assertTrue(!Files.exists(output))
                assertEquals(0L, Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".tmp") }.count() })
            }
        }
    }

    @Test
    fun `real denied cleanup preserves primary and access denied as suppressed`() {
        assumeTrue((Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Number).toInt() != 0)
        val directory = Files.createDirectory(root.resolve("private"))
        val library = fixture()
        val output = directory.resolve("target.reg")
        val values = arguments(library, output).drop(1).chunked(2).associate { it[0] to it[1] }
        val start = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(ProcessHandle.current().pid().toInt()))
        val failure = IllegalStateException("primary")
        var calls = 0
        try {
            val caught = assertFailsWith<IllegalStateException> {
                register(values) {
                    if (++calls == 2) {
                        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
                        throw failure
                    }
                    start
                }
            }
            assertSame(failure, caught)
            assertTrue(caught.suppressed.single() is AccessDeniedException)
            assertTrue(!Files.exists(output))
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `cleanup uses fatal first folding and retains a confidential temporary`() {
        val library = fixture()
        val start = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(ProcessHandle.current().pid().toInt()))
        for (primaryFatal in listOf(false, true)) {
            for (cleanupFatal in listOf(false, true)) {
                val output = root.resolve("target-$primaryFatal-$cleanupFatal")
                val values = arguments(library, output).drop(1).chunked(2).associate { it[0] to it[1] }
                val primary = if (primaryFatal) LinkageError("primary") else IllegalStateException("primary")
                val cleanup = if (cleanupFatal) LinkageError("cleanup") else AccessDeniedException("cleanup")
                var temporary: Path? = null
                var observations = 0
                val caught = assertFailsWith<Throwable> {
                    register(values, remove = {
                        temporary = it
                        throw cleanup
                    }) {
                        if (++observations == 2) throw primary
                        start
                    }
                }
                val expected = if (cleanupFatal && !primaryFatal) cleanup else primary
                val suppressed = if (expected === primary) cleanup else primary
                assertSame(expected, caught)
                assertSame(suppressed, caught.suppressed.single())
                assertTrue(!Files.exists(output))
                val temp = requireNotNull(temporary)
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temp))
                Files.delete(temp)
            }
        }
    }

    @Test
    fun `postcommit unlink failure is observable while complete registration remains`() {
        val library = fixture()
        val output = root.resolve("committed")
        val values = arguments(library, output).drop(1).chunked(2).associate { it[0] to it[1] }
        val failure = AccessDeniedException("unlink", null, "EACCES")
        var temporary: Path? = null
        val caught = assertFailsWith<AccessDeniedException> {
            register(values, remove = {
                temporary = it
                throw failure
            })
        }
        assertSame(failure, caught)
        val temp = requireNotNull(temporary)
        assertTrue(Files.isSameFile(output, temp))
        assertEquals(0x1a0, TierETargetRegistrationFile.read(output).markerOffset)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temp))
        Files.delete(temp)
    }

    @Test
    fun `actual denied ELF read is propagated without a final file`() {
        assumeTrue((Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Number).toInt() != 0)
        val library = fixture()
        val output = root.resolve("unreadable.reg")
        val values = arguments(library, output).drop(1).chunked(2).associate { it[0] to it[1] }
        Files.setPosixFilePermissions(library, emptySet())
        try {
            assertFailsWith<AccessDeniedException> { register(values) }
            assertTrue(!Files.exists(output))
        } finally {
            Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `malformed local registrations and unavailable socket fail closed`() {
        val pid = ProcessHandle.current().pid().toInt()
        val start = requireNotNull(TierEProcessIdentityVerifier.observeLinuxIncarnation(pid))
        val valid = "version=1\nregistration_id=orders\npid=$pid\nsession_tag=4d5a\nprocess_incarnation=$start\n" +
            "marker_path=/agent.so\nmarker_offset=20\nartifact_file=orders.mzse\n"
        val registration = root.resolve("target.reg")
        val socket = root.resolve("absent.sock")
        val cases = listOf(
            valid + "pid=$pid\n",
            valid.replace("version=1", "version=3"),
            valid.replace("marker_offset=20", "marker_offset=nothex"),
            valid.replace("pid=$pid", "pid=0"),
            valid.replace("session_tag=4d5a", "session_tag=0"),
            valid.replace("artifact_file=orders.mzse", "artifact_file=../bad"),
            valid.replace("marker_path=/agent.so", "marker_path=/bad path.so"),
            valid.replace("version=1", "version=2") + "marker_proof_file=proof\n",
            valid.replace("artifact_file=orders.mzse\n", ""),
        )
        for (content in cases) {
            Files.writeString(registration, content)
            assertFailsWith<IllegalArgumentException>(content) {
                main(arrayOf("attach", "--registration", registration.toString(), "--socket", socket.toString()))
            }
        }
        Files.writeString(registration, valid)
        assertFailsWith<java.io.IOException> {
            main(arrayOf("attach", "--registration", registration.toString(), "--socket", socket.toString()))
        }
    }
}
