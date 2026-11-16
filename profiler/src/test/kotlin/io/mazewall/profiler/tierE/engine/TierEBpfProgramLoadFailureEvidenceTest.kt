package io.mazewall.profiler.tierE.engine

import io.mazewall.profiler.internal.attribution.TierESysEnterAttachmentBackend
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TierEBpfProgramLoadFailureEvidenceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `capacity facts never infer supported empty or complete causal evidence`() {
        fun state(
            size: Long,
            nul: Long = 2,
            errno: Int = 28,
        ) = TierEBpfProgramLoadFailureEvidence.classify(size, 65536, nul, errno)
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.UNKNOWN, state(0))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.SUFFICIENT, state(3))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.SUFFICIENT, state(65536))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.INSUFFICIENT, state(65537))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.SATURATED, state(0xffffffffL))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.MALFORMED, state(2))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.MALFORMED, state(0, -1))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.MALFORMED, state(65537, -1))
        assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.MALFORMED, state(3, errno = 14))
    }

    @Test
    fun `fixed schema retains exact resolved instructions and raw prefix including invalid text`() {
        Arena.ofConfined().use { arena ->
            val capture = capture(arena)
            val request = capture.request
            request.verifierLog.fill(0x7f)
            val log = byteArrayOf(32, 10, -1, 0, 32, 9)
            log.forEachIndexed { index, byte -> request.verifierLog.set(ValueLayout.JAVA_BYTE, index.toLong(), byte) }
            request.attr.set(ValueLayout.JAVA_INT, 140, -1)
            val path = directory.resolve("capture")
            TierEBpfProgramLoadFailureEvidence.write(capture, path)
            val bytes = Files.readAllBytes(path)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x4c505a4d, header.getInt(0))
            assertEquals(1, header.getInt(4))
            assertEquals(2048, header.getInt(8))
            assertEquals(8, header.getInt(12))
            assertEquals(7, header.getLong(16))
            assertEquals(42, header.getInt(24))
            assertEquals(19802, header.getInt(28))
            assertEquals(1, header.getInt(32))
            assertEquals(17, header.getInt(36))
            assertEquals(28, header.getInt(40))
            assertEquals(TierEBpfProgramLoadFailureEvidence.Capacity.SATURATED.ordinal, header.getInt(44))
            assertEquals(-1L, header.getLong(48))
            assertEquals(0xffffffffL, header.getLong(56))
            assertEquals(65536, header.getLong(64))
            assertEquals(3, header.getLong(72))
            assertEquals(32768, header.getInt(80))
            assertEquals(1, header.getInt(84))
            assertEquals(4, header.getInt(92))
            assertEquals(0, header.getInt(96))
            assertEquals(1, header.getInt(100))
            assertEquals(1, header.getInt(104))
            assertEquals(1, header.getInt(108))
            assertEquals("tier_e_marker", String(bytes, 256, 13, Charsets.US_ASCII))
            assertEquals(" ..", String(bytes, 336, 3, Charsets.US_ASCII))
            val instructions = request.instructions.toArray(ValueLayout.JAVA_BYTE)
            val full = request.verifierLog.toArray(ValueLayout.JAVA_BYTE)
            val prefix = full.copyOf(32768)
            val digest = MessageDigest.getInstance("SHA-256")
            assertContentEquals(digest.digest(instructions), bytes.copyOfRange(128, 160))
            assertContentEquals(digest.digest(full), bytes.copyOfRange(160, 192))
            assertContentEquals(digest.digest(prefix), bytes.copyOfRange(192, 224))
            assertContentEquals(instructions, bytes.copyOfRange(2048, 2056))
            assertContentEquals(prefix, bytes.copyOfRange(2056, bytes.size))
            assertEquals(34824, bytes.size)
            assertFalse(Files.exists(directory.resolve("capture.tmp")))
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions
                .toString(Files.getPosixFilePermissions(path)),
            )
        }
    }

    @Test
    fun `bounded summary and channel cleanup promote the identical fatal error`() {
        Arena.ofConfined().use { arena ->
            val capture = capture(arena)
            capture.request.verifierLog.fill(65)
            assertEquals("A".repeat(96), TierEBpfProgramLoadFailureEvidence.summary(capture.request.verifierLog))
            val ordinary = IOException("write")
            val fatal = LinkageError("close")
            val thrown = assertFailsWith<LinkageError> {
                TierEBpfProgramLoadFailureEvidence.write(
                    capture,
                    directory.resolve("fatal-close"),
                    write = { _, _ -> throw ordinary },
                    close = {
                        it.close()
                        throw fatal
                    },
                )
            }
            assertSame(fatal, thrown)
            assertSame(ordinary, thrown.suppressed.single())
            assertTrue(Files.exists(directory.resolve("fatal-close.tmp")))
            assertFalse(Files.exists(directory.resolve("fatal-close")))
        }
    }

    @Test
    fun `maximum artifact fits exactly and cap plus one is rejected before file creation`() {
        Arena.ofConfined().use { arena ->
            val maximum = capture(arena, 1024)
            TierEBpfProgramLoadFailureEvidence.write(maximum, directory.resolve("maximum"))
            assertEquals(43008L, Files.size(directory.resolve("maximum")))
            val over = maximum.copy(request = maximum.request.copy(instructions = arena.allocate(8193)))
            assertFailsWith<IllegalArgumentException> { TierEBpfProgramLoadFailureEvidence.write(over, directory.resolve("over")) }
            assertFalse(Files.exists(directory.resolve("over.tmp")))
        }
    }

    @Test
    fun `short writes progress while zero progress force and publication failures keep owned partial`() {
        Arena.ofConfined().use { arena ->
            val capture = capture(arena)
            TierEBpfProgramLoadFailureEvidence.write(capture, directory.resolve("short"), write = { channel, bytes ->
                val end = bytes.limit()
                bytes.limit(minOf(end, bytes.position() + 7))
                channel.write(bytes).also { bytes.limit(end) }
            })
            assertEquals(34824L, Files.size(directory.resolve("short")))
            assertFailsWith<IOException> { TierEBpfProgramLoadFailureEvidence.write(capture, directory.resolve("zero"), write = { _, _ -> 0 }) }
            assertEquals(0L, Files.size(directory.resolve("zero.tmp")))
            for (phase in listOf("write", "force", "publish")) {
                val original = IOException(phase)
                val thrown = assertFailsWith<IOException> {
                    TierEBpfProgramLoadFailureEvidence.write(
                        capture,
                        directory.resolve(phase),
                        write = { channel, bytes -> if (phase == "write") throw original else channel.write(bytes) },
                        force = { if (phase == "force") throw original else it.force(true) },
                        publish = { temp, final -> if (phase == "publish") throw original else Files.createLink(final, temp) },
                    )
                }
                assertSame(original, thrown)
                assertFalse(Files.exists(directory.resolve(phase)))
                assertTrue(Files.exists(directory.resolve("$phase.tmp")))
            }
        }
    }

    @Test
    fun `collisions and symlinks never overwrite and prohibit reinstall accumulation`() {
        Arena.ofConfined().use { arena ->
            for (kind in listOf("final", "symlink", "temp")) {
                val path = directory.resolve(kind)
                val retained = if (kind == "temp") directory.resolve("$kind.tmp") else path
                if (kind == "symlink") {
                    val original = Files.writeString(directory.resolve("original"), "retained")
                    Files.createSymbolicLink(path, original)
                } else {
                    Files.writeString(retained, "retained")
                }
                assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEBpfProgramLoadFailureEvidence.write(capture(arena), path) }
                assertEquals("retained", Files.readString(retained))
            }
            val path = directory.resolve("once")
            TierEBpfProgramLoadFailureEvidence.write(capture(arena), path)
            val original = Files.readAllBytes(path)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { TierEBpfProgramLoadFailureEvidence.write(capture(arena), path) }
            assertContentEquals(original, Files.readAllBytes(path))
        }
    }

    private fun capture(
        arena: Arena,
        count: Int = 1,
    ): TierEBpfProgramLoadFailureEvidence.Capture {
        val request = BpfProgLoadRequestEncoder.encode(arena, List(count) { TierEbpfEngine.Insn(0x18, src = 1, imm = 0) }, listOf(123), 17, "tier_e_marker", true)
        return TierEBpfProgramLoadFailureEvidence.Capture(request, 7, 42, 19802, 1, "tier_e_marker", 17, TierESysEnterAttachmentBackend.DIRECT_RAW_TRACEPOINT, 28, -1)
    }
}
