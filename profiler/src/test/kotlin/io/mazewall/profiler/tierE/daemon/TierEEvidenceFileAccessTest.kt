package io.mazewall.profiler.tierE.daemon

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEEvidenceFileAccessTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `group selection handles empty and populated lists but rejects malformed tokens`() {
        assertEquals(1000, selectEvidenceTestGid("  \t", 2000, 1000))
        assertEquals(3000, selectEvidenceTestGid(" 2000 3000 ", 2000, 1000))
        assertEquals(1000, selectEvidenceTestGid(" 2000 ", 2000, 1000))
        assertFailsWith<NumberFormatException> { selectEvidenceTestGid("2000 invalid", 2000, 1000) }
    }

    @Test
    fun `configuration is explicit decimal host gid with bounded range`() {
        assertEquals(null, TierEEvidenceFileAccess.fromEnvironment(emptyMap()).groupId)
        for (value in listOf("0", "1000", "2147483647")) {
            assertEquals(value.toInt(), TierEEvidenceFileAccess.fromEnvironment(mapOf("TIER_E_EVIDENCE_GID" to value)).groupId)
        }
        for (value in listOf("", "-1", "+1", " 1", "1 ", "1.0", "group", "2147483648", "4294967295")) {
            assertFailsWith<IllegalArgumentException>(value) {
                TierEEvidenceFileAccess.fromEnvironment(mapOf("TIER_E_EVIDENCE_GID" to value))
            }
        }
        assertEquals(null, TierEEvidenceFileAccess.fromEnvironment(mapOf("TIER_E_SOCKET_GID" to "1000")).groupId)
    }

    @Test
    fun `default and selected access have exact real permissions and selected gid`() {
        val file = Files.createTempFile(root, "owned", ".tmp")
        TierEEvidenceFileAccess().apply(file)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        val gid = selectedEvidenceTestGid(root)
        TierEEvidenceFileAccess(gid).apply(file)
        assertEquals(gid, Files.getAttribute(file, "unix:gid"))
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `acl permission and unsupported errors never widen permissions`() {
        for (errno in listOf(NativeConstants.EPERM, NativeConstants.EACCES, NativeConstants.ENOSYS, NativeConstants.EOPNOTSUPP)) {
            val file = Files.createTempFile(root, "owned", ".tmp")
            val native = object : NativeFileSystem by LinuxNative.fileSystem {
                override fun removeExtendedAttribute(
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    name: io.mazewall.ffi.memory.ManagedSegment,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(errno, -1)
            }
            val failure = assertFailsWith<IllegalStateException> {
                TierEEvidenceFileAccess(1000).apply(file, native)
            }
            assertTrue(failure.message.orEmpty().contains("errno=$errno"))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            val access = TierEEvidenceFileAccess(1000)
            val target = TierERegisteredTarget("orders", 123, 0x4d5a, 99, Path.of("/agent.so"), 10, "orders.mzse")
            assertFailsWith<IllegalStateException> {
                TierEEventJsonlFileSink(root, prepareTemporary = { access.apply(it, native) }).publish(
                    target,
                    io.mazewall.profiler.internal.attribution
                        .TierEProfile(
                            emptyList(),
                            io.mazewall.profiler.internal.attribution
                            .SessionIntegrity(),
                        ),
                    io.mazewall.profiler.internal.attribution.TierETerminalReason.COMPLETE,
                )
            }
            assertTrue(!Files.exists(root.resolve("orders-19802.events.jsonl")))
            val final = Files.writeString(root.resolve("orders-19802.tier-e.json"), "original")
            assertFailsWith<IllegalStateException> {
                TierETerminalReportFileSink(root, prepareTemporary = { access.apply(it, native) }).accept(
                    TierETerminalReport(
                        io.mazewall.profiler.internal.attribution.EvidenceIntegrity.INCOMPLETE,
                        "FINALIZATION_FAILED",
                        0,
                        false,
                        false,
                        false,
                        false,
                        false,
                        registrationId = "orders",
                        sessionEpoch = 19802L,
                    ),
                )
            }
            assertEquals("original", Files.readString(final))
        }
    }

    @Test
    fun `group and mode errors leave confidential owned data`() {
        val file = Files.createTempFile(root, "owned", ".tmp")
        Files.writeString(file, "complete")
        assertFailsWith<java.io.IOException> {
            TierEEvidenceFileAccess(1000).apply(file, setGroup = { _, _ -> throw java.io.IOException("group") })
        }
        assertFailsWith<java.io.IOException> {
            TierEEvidenceFileAccess().apply(file, setMode = { _, _ -> throw java.io.IOException("mode") })
        }
        assertEquals("complete", Files.readString(file))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `inherited named nonmember acl is removed before widening either sink output`() {
        org.junit.jupiter.api.Assumptions
            .assumeTrue(Files.isExecutable(Path.of("/usr/bin/setfacl")) && Files.isExecutable(Path.of("/usr/bin/getfacl")))
        val fixture = ProcessBuilder("/usr/bin/setfacl", "-m", "d:u:20001:r--", root.toString()).redirectErrorStream(true).start()
        val fixtureOutput = fixture.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, fixture.waitFor(), fixtureOutput)
        val temporary = Files.createTempFile(root, "inherited", ".tmp")

        fun acl(file: Path): String {
            val command = ProcessBuilder("/usr/bin/getfacl", "-cpn", file.toString()).redirectErrorStream(true).start()
            val output = command.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, command.waitFor(), output)
            return output
        }
        assertTrue(acl(temporary).contains("user:20001:r--"))
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"))
        assertTrue(acl(temporary).contains("mask::r--"))
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
        TierEEvidenceFileAccess(selectedEvidenceTestGid(root)).apply(temporary)
        assertTrue(!acl(temporary).contains("user:20001:"))
        val target = TierERegisteredTarget("orders", 123, 0x4d5a, 99, Path.of("/agent.so"), 10, "orders.mzse")
        val access = TierEEvidenceFileAccess(selectedEvidenceTestGid(root))
        val event = TierEEventJsonlFileSink(root, access = access).publish(
            target,
            io.mazewall.profiler.internal.attribution
                .TierEProfile(
                    emptyList(),
                    io.mazewall.profiler.internal.attribution
                    .SessionIntegrity(),
                ),
            io.mazewall.profiler.internal.attribution.TierETerminalReason.COMPLETE,
        )
        val report = TierETerminalReport(
            io.mazewall.profiler.internal.attribution.EvidenceIntegrity.INCOMPLETE,
            "FINALIZATION_FAILED",
            0,
            false,
            false,
            false,
            false,
            false,
            registrationId = "orders",
            sessionEpoch = 19802L,
        )
        TierETerminalReportFileSink(root, access).accept(report)
        for (file in listOf(event, root.resolve("orders-19802.tier-e.json"))) {
            assertTrue(!acl(file).contains("user:20001:"))
            assertEquals(access.groupId, Files.getAttribute(file, "unix:gid"))
            assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
        assertTrue(acl(root).contains("default:user:20001:r--"))
    }
}

internal fun selectedEvidenceTestGid(root: Path): Int {
    val current = Files.getAttribute(root, "unix:gid") as Int
    val status = Files.readAllLines(Path.of("/proc/self/status"))
    val groups = status.first { it.startsWith("Groups:") }.substringAfter(':')
    val effective = status
        .first { it.startsWith("Gid:") }
        .substringAfter(':')
        .trim()
        .split(Regex("\\s+"))[1]
        .toInt()
    return selectEvidenceTestGid(groups, current, effective)
}

private fun selectEvidenceTestGid(
    groups: String,
    current: Int,
    effective: Int,
): Int =
    groups
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .map { it.toInt() }
        .firstOrNull { it != current } ?: effective
