package io.mazewall.tierE.daemon

import io.mazewall.tierE.shim.TierEBpfShim
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class SessionEngineTest {

    private class FakeShim : TierEBpfShim {
        var handleSeq = 0L
        val destroyed = mutableListOf<Long>()
        var failLoad: Boolean = false
        var failAttachMarker: Boolean = false
        var lastAttachedPath: String? = null

        override fun loadObject(bpfObjectPath: String): Long {
            if (failLoad) throw io.mazewall.tierE.shim.ShimException("loadObject", -1, "bad elf")
            return ++handleSeq
        }

        override fun setTargetTgid(handle: Long, tgid: Int) {}
        override fun attachSysEnter(handle: Long) {}

        override fun attachMarkerUprobe(handle: Long, pid: Int, sharedObjectPath: String) {
            if (failAttachMarker) {
                throw io.mazewall.tierE.shim.ShimException("attachMarkerUprobe", -22, "no such file")
            }
            lastAttachedPath = sharedObjectPath
        }

        override fun attachMarkerUsdt(handle: Long, pid: Int, sharedObjectPath: String) {
            attachMarkerUprobe(handle, pid, sharedObjectPath)
        }

        override fun ringFd(handle: Long): Int = -1
        override fun droppedTotal(handle: Long): ULong = 0uL
        override fun destroy(handle: Long) {
            destroyed += handle
        }

        override fun ringNew(handle: Long): Long = 0
        override fun ringPoll(rbHandle: Long, timeoutMs: Int): Int = 0
        override fun ringDestroy(rbHandle: Long) {}

        override fun unknownCounts(handle: Long): LongArray = LongArray(512)
        override fun readPerNr(handle: Long): LongArray = LongArray(1024)
    }

    private val shim = FakeShim()
    private val tmpDir: Path = Files.createTempDirectory("tier-e-test")

    private fun attrs(p: Path): Pair<Long, String> {
        @Suppress("UNCHECKED_CAST")
        val m = Files.readAttributes(
            p,
            "unix:ino,dev",
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ) as Map<String, Any>
        val ino = (m["ino"] as Number).toLong()
        val dev = (m["dev"] as Number).toLong()
        val major = ((dev shr 8) and 0xFFF) or ((dev shr 32) and -0x1000L)
        val minor = (dev and 0xFF) or ((dev shr 12) and -0x100L)
        return ino to "%02x:%02x".format(major, minor)
    }

    private fun mapsLineFor(p: Path): String {
        val (ino, devStr) = attrs(p)
        return "7f00-8000 r--p 00000000 $devStr $ino $p"
    }

    @AfterTest
    fun cleanup() {
        tmpDir.toFile().deleteRecursively()
    }

    private fun markerWithBuildId(hex: String = "aabbccdd"): Path {
        val bytes = ByteArray(512)
        bytes[0] = 0x7F; bytes[1] = 0x45; bytes[2] = 0x4C; bytes[3] = 0x46 // ELF magic
        bytes[4] = 2 // EI_CLASS = ELFCLASS64
        // One SHT_NOTE section at a fixed, self-consistent location:
        // shdr table right after ehdr is not needed by the extractor when we
        // hand-craft; instead craft minimal header + one section.
        // Layout: [0..64) ehdr; shstrtab; note section; shdrs at 0x40.
        val notePayloadName = byteArrayOf('G'.code.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 0)
        val buildIdBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val namesz = 4
        val descsz = buildIdBytes.size
        val nameAligned = (namesz + 3) and (Int.MAX_VALUE - 3)
        val descAligned = (descsz + 3) and (Int.MAX_VALUE - 3)

        val strtab = "note.sec".toByteArray(Charsets.US_ASCII) + 0
        var off = 0x40
        val shstrOff = off; off += strtab.size + 1
        val noteHdrOff = off; off += 12
        val noteNameOff = off; off += nameAligned
        val noteDescOff = off; off += descAligned

        fun putInt(dst: ByteArray, at: Int, v: Int) { for (i in 0..3) dst[at + i] = (v ushr (8 * i)).toByte() }
        fun putLong(dst: ByteArray, at: Int, v: Long) { for (i in 0..7) dst[at + i] = (v ushr (8 * i)).toByte() }

        putLong(bytes, E_SHOFF_OFF, off.toLong())
        putInt(bytes, E_SHENTSIZE_OFF, 64)
        putInt(bytes, E_SHNUM_OFF, 3) // null, shstrtab, note
        putInt(bytes, E_SHSTRNDX_OFF, 1)

        System.arraycopy(strtab, 0, bytes, shstrOff, strtab.size)

        putInt(bytes, noteHdrOff, namesz)
        putInt(bytes, noteHdrOff + 4, descsz)
        putInt(bytes, noteHdrOff + 8, 3) // NT_GNU_BUILD_ID
        System.arraycopy(notePayloadName, 0, bytes, noteNameOff, namesz)
        System.arraycopy(buildIdBytes, 0, bytes, noteDescOff, descsz)

        fun shdr(idx: Int, name: Int, type: Int, offset: Int, size: Int) {
            val base = off + idx * 64
            putInt(bytes, base + SH_NAME_OFF, name)
            putInt(bytes, base + SH_TYPE_OFF, type)
            putLong(bytes, base + SH_OFFSET_OFF, offset.toLong())
            putLong(bytes, base + SH_SIZE_OFF, size.toLong())
        }
        shdr(0, 0, 0, 0, 0) // SHT_NULL
        shdr(1, 1, 3, shstrOff, strtab.size + 1) // SHT_STRTAB
        shdr(2, 0 /* "note.sec" starts at index 0 */, 7 /* SHT_NOTE */, noteHdrOff, 12 + nameAligned + descAligned)

        return Files.write(tmpDir.resolve("marker-$hex.so"), bytes)
    }

    private fun engine(mapsProvider: (Int) -> Sequence<String>? = { null }) =
        SessionEngine(epoch = 7, shim = shim, mapsLineProvider = mapsProvider)

    private fun attachCmd(pid: Int = 100, path: Path) =
        ControlCommand.Attach(pid, AttachMode.UPROBE, path.toString())

    @Test
    fun `unmapped marker refused with loud reason`() {
        val so = markerWithBuildId()
        val engine = SessionEngine(1, shim, mapsLineProvider = { _ ->
            listOf("/usr/lib/other.so").asSequence().map { "7f00-8000 r--p 00000000 08:01 999 $it" }
        })
        val reply = engine.onAttach(attachCmd(path = so))
        assertEquals("ERR MARKER_NOT_MAPPED_IN_TARGET\n", reply.render())
        assertIs<SessionEngine.State.Dead>(engine.state)
        assertTrue(shim.destroyed.isEmpty())
    }

    @Test
    fun `happy path binds once then refuses re-bind in same epoch`() {
        val so = markerWithBuildId("00112233")
        val lines = sequenceOf(mapsLineFor(so))
        val engine = SessionEngine(2, shim, mapsLineProvider = { _ -> lines })
        val ok = engine.onAttach(attachCmd(pid = 555, path = so))
        assertEquals("OK ATTACHED epoch=2 buildid=00112233\n", ok.render())
        assertIs<SessionEngine.State.Running>(engine.state)

        val again = engine.onAttach(attachCmd(pid = 556, path = so))
        assertEquals("ERR ALREADY_BOUND tgid=555\n", again.render())

        assertEquals("OK DETACHED\n", engine.onDetach().render())
        assertIs<SessionEngine.State.Detached>(engine.state)
        assertTrue(shim.destroyed.isNotEmpty())
    }

    @Test
    fun `shim load failure kills the session`() {
        val so = markerWithBuildId("ff")
        val lines = sequenceOf(mapsLineFor(so))
        val engine = SessionEngine(3, shim, mapsLineProvider = { _ -> lines })
        shim.failLoad = true
        val reply = engine.onAttach(attachCmd(path = so))
        assertTrue(reply.text.startsWith("LOAD_BPF"))
        assertIs<SessionEngine.State.Dead>(engine.state)
    }

    @Test
    fun `mapped garbage elf yields BUILD_ID_UNREADABLE`() {
        val garbage = Files.write(tmpDir.resolve("garbage.so"), "definitely not elf".toByteArray())
        val engine = SessionEngine(4, shim, mapsLineProvider = { _ -> sequenceOf(mapsLineFor(garbage)) })
        val reply = engine.onAttach(attachCmd(path = garbage))
        assertEquals("ERR MARKER_BUILD_ID_UNREADABLE\n", reply.render())
    }

    private companion object {
        const val E_SHOFF_OFF = 0x28
        const val E_SHENTSIZE_OFF = 0x3A
        const val E_SHNUM_OFF = 0x3C
        const val E_SHSTRNDX_OFF = 0x3E
        const val SH_NAME_OFF = 0
        const val SH_TYPE_OFF = 4
        const val SH_OFFSET_OFF = 24
        const val SH_SIZE_OFF = 32
    }
}
