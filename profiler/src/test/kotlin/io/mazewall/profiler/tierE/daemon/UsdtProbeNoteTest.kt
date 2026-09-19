package io.mazewall.profiler.tierE.daemon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsdtProbeNoteTest {
    @Test
    fun `find stack marker v1 admits the actual x86 64 two argument ABI emitted by the native marker`() {
        val library = writeFixture(stapsdtNote(arguments = "8@%rdi 4@%esi"))

        val note = UsdtProbeNotes.findStackMarkerV1(library)

        assertEquals("8@%rdi 4@%esi", note.arguments)
    }

    @Test
    fun `find stack marker v1 maps the validated x86 marker probe virtual address to its file offset`() {
        val library = writeFixture(stapsdtNote(provider = "mazewall", probe = "stack_marker_v1"))

        val note = UsdtProbeNotes.findStackMarkerV1(library)

        assertEquals("8@%rdi 4@%esi", note.arguments)
        assertEquals(0x180L, note.fileOffset)
        assertEquals(0x400180u, note.virtualAddress)
    }

    @Test
    fun `find stack marker v1 admits a stapsdt note present only in the ELF section table`() {
        val library = writeSectionNoteFixture(stapsdtNote())

        val note = UsdtProbeNotes.findStackMarkerV1(library)

        assertEquals(0x180L, note.fileOffset)
        assertEquals("8@%rdi 4@%esi", note.arguments)
    }

    @Test
    fun `find stack marker v1 rejects duplicate matching notes`() {
        val library = writeFixture(
            stapsdtNote(provider = "mazewall", probe = "stack_marker_v1") +
                stapsdtNote(provider = "mazewall", probe = "stack_marker_v1"),
        )

        assertFailsWith<IllegalArgumentException> {
            UsdtProbeNotes.findStackMarkerV1(library)
        }
    }

    @Test
    fun `find stack marker v1 rejects the legacy unversioned marker note`() {
        val library = writeFixture(stapsdtNote(probe = "stack_marker"))

        assertFailsWith<IllegalArgumentException> {
            UsdtProbeNotes.findStackMarkerV1(library)
        }
    }

    @Test
    fun `find rejects an unsupported argument register contract`() {
        val library = writeFixture(stapsdtNote(arguments = "8@%rdi 4@%rdx"))

        assertFailsWith<IllegalArgumentException> {
            UsdtProbeNotes.findStackMarkerV1(library)
        }
    }

    @Test
    fun `find rejects a nonzero semaphore`() {
        val library = writeFixture(stapsdtNote(semaphore = 0x400200u))

        assertFailsWith<IllegalArgumentException> {
            UsdtProbeNotes.findStackMarkerV1(library)
        }
    }

    @Test
    fun `validated note rejects a caller supplied offset that is not its probe site`() {
        val library = writeFixture(stapsdtNote())
        val note = UsdtProbeNotes.findStackMarkerV1(library)

        assertFailsWith<IllegalArgumentException> {
            note.requireExpectedOffset(0x181)
        }
    }

    private fun writeFixture(notes: ByteArray): java.nio.file.Path = Files.createTempFile("tier-e-usdt-note", ".so").also { Files.write(it, elfWithNotes(notes)) }

    private fun elfWithNotes(notes: ByteArray): ByteArray {
        val bytes = ByteArray(0x300)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // ELFDATA2LSB
        bytes[6] = 1 // EV_CURRENT
        buffer.putShort(16, 3) // ET_DYN
        buffer.putShort(18, 62) // EM_X86_64
        buffer.putInt(20, 1)
        buffer.putLong(32, 64) // e_phoff
        buffer.putShort(52, 64) // e_ehsize
        buffer.putShort(54, 56) // e_phentsize
        buffer.putShort(56, 2) // e_phnum

        // PT_LOAD maps the whole fixture at 0x400000, making PC 0x400180 -> file offset 0x180.
        buffer.putInt(64, 1)
        buffer.putLong(64 + 8, 0)
        buffer.putLong(64 + 16, 0x400000)
        buffer.putLong(64 + 32, bytes.size.toLong())
        buffer.putLong(64 + 40, bytes.size.toLong())
        // PT_NOTE points at the synthetic SystemTap descriptor sequence.
        buffer.putInt(120, 4)
        buffer.putLong(120 + 8, 0x200)
        buffer.putLong(120 + 32, notes.size.toLong())
        buffer.putLong(120 + 40, notes.size.toLong())
        notes.copyInto(bytes, destinationOffset = 0x200)
        return bytes
    }

    private fun writeSectionNoteFixture(notes: ByteArray): java.nio.file.Path = Files.createTempFile("tier-e-usdt-section-note", ".so").also { Files.write(it, elfWithSectionNotes(notes)) }

    private fun elfWithSectionNotes(notes: ByteArray): ByteArray {
        val bytes = ByteArray(0x400)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // ELFDATA2LSB
        bytes[6] = 1 // EV_CURRENT
        buffer.putShort(16, 3) // ET_DYN
        buffer.putShort(18, 62) // EM_X86_64
        buffer.putInt(20, 1)
        buffer.putLong(32, 64) // e_phoff
        buffer.putLong(40, 0x280) // e_shoff
        buffer.putShort(52, 64) // e_ehsize
        buffer.putShort(54, 56) // e_phentsize
        buffer.putShort(56, 1) // e_phnum
        buffer.putShort(58, 64) // e_shentsize
        buffer.putShort(60, 2) // e_shnum

        buffer.putInt(64, 1) // PT_LOAD
        buffer.putLong(64 + 8, 0)
        buffer.putLong(64 + 16, 0x400000)
        buffer.putLong(64 + 32, bytes.size.toLong())
        buffer.putLong(64 + 40, bytes.size.toLong())

        // The second section header names an unallocated .note.stapsdt-style payload.
        val section = 0x280 + 64
        buffer.putInt(section + 4, 7) // SHT_NOTE
        buffer.putLong(section + 24, 0x200)
        buffer.putLong(section + 32, notes.size.toLong())
        notes.copyInto(bytes, destinationOffset = 0x200)
        return bytes
    }

    private fun stapsdtNote(
        provider: String = "mazewall",
        probe: String = "stack_marker_v1",
        arguments: String = "8@%rdi 4@%esi",
        semaphore: ULong = 0u,
    ): ByteArray {
        val descriptor = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        descriptor.putLong(0x400180)
        descriptor.putLong(0x400000)
        descriptor.putLong(semaphore.toLong())
        descriptor.put(provider.encodeToByteArray()).put(0)
        descriptor.put(probe.encodeToByteArray()).put(0)
        descriptor.put(arguments.encodeToByteArray()).put(0)
        val descriptorBytes = descriptor.array().copyOf(descriptor.position())
        val name = "stapsdt\u0000".encodeToByteArray()
        val note = ByteBuffer.allocate(12 + align4(name.size) + align4(descriptorBytes.size)).order(ByteOrder.LITTLE_ENDIAN)
        note.putInt(name.size)
        note.putInt(descriptorBytes.size)
        note.putInt(3)
        note.put(name)
        note.position(12 + align4(name.size))
        note.put(descriptorBytes)
        return note.array()
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()
}
