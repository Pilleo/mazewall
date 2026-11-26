package io.mazewall.ffi.memory

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.ffi.Layouts
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.seccomp.BpfInstruction
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryWrappersTest {
    @Test
    fun `sock filter program packs exact bytes for adjacent instructions`() {
        Arena.ofConfined().use { arena ->
            val filters = listOf(
                BpfInstruction.Jmp(BpfInstruction.Comparison.EQUAL, 255, 128, 0x12345678),
                BpfInstruction.Ld(4),
            )
            val program = with(arena) { SockFilterSegment.packProgram(filters) }.unwrap
            assertTrue(program.isReadOnly)
            assertEquals(2, program.get(ValueLayout.JAVA_SHORT, Layouts.SOCK_FPROG_LEN_OFFSET).toInt())
            val address = program.get(ValueLayout.ADDRESS, Layouts.SOCK_FPROG_FILTER_OFFSET)
            val instructions = address.reinterpret(16, arena, null).asReadOnly()
            val expected = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
            expected
                .putShort(0x15)
                .put(0xff.toByte())
                .put(0x80.toByte())
                .putInt(0x12345678)
                .putShort(0x20)
                .put(0)
                .put(0)
                .putInt(4)
            assertContentEquals(expected.array(), instructions.toArray(ValueLayout.JAVA_BYTE))
        }
    }

    @Test
    fun `sock filter exposes typed access without writable filter getters or array factories`() {
        val types = listOf(SockFilterSegment::class.java, SockFilterSegment.Companion::class.java)
        val aliases = types.flatMap { it.declaredMethods.asList() }.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                (method.name.startsWith("get") || method.name == "allocateArray") &&
                (method.returnType == MemorySegment::class.java || method.returnType == ManagedSegment::class.java)
        }
        assertEquals(emptyList(), aliases.map { it.name }.sorted())
    }

    @Test
    fun `test SockFilterSegment getters and setters`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val segment = SockFilterSegment.allocate()
                segment.setCode(0x1234.toShort())
                segment.setJt(0x56.toByte())
                segment.setJf(0x78.toByte())
                segment.setK(0x9abcdef0.toInt())

                assertEquals(0x1234.toShort(), segment.getCode())
                assertEquals(0x56.toByte(), segment.getJt())
                assertEquals(0x78.toByte(), segment.getJf())
                assertEquals(0x9abcdef0.toInt(), segment.getK())
            }
        }
    }

    @Test
    fun `test PollFdSegment getters and setters`() {
        Arena.ofConfined().use { arena ->
            with(arena) {
                val segment = PollFdSegment.allocate()
                segment.setFd(42)
                segment.setEvents(0x1234.toShort())
                segment.setRevents(0x5678.toShort())

                assertEquals(42, segment.getFd())
                assertEquals(0x1234.toShort(), segment.getEvents())
                assertEquals(0x5678.toShort(), segment.getRevents())
            }
        }
    }

    @Test
    fun `test memory segment extension methods`() {
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(100)

            segment.writeByte(0, 0x12.toByte())
            assertEquals(0x12.toByte(), segment.readByte(0))

            segment.writeShort(2, 0x1234.toShort())
            assertEquals(0x1234.toShort(), segment.readShort(2))

            segment.writeInt(4, 0x12345678)
            assertEquals(0x12345678, segment.readInt(4))

            segment.writeIntUnaligned(9, 0x12345678)
            assertEquals(0x12345678, segment.readIntUnaligned(9))

            segment.writeLong(16, 0x1234567890abcdefL)
            assertEquals(0x1234567890abcdefL, segment.readLong(16))

            segment.writeLongUnaligned(25, 0x1234567890abcdefL)
            assertEquals(0x1234567890abcdefL, segment.readLongUnaligned(25))
        }
    }

    @Test
    fun `test getSystemStrerror`() {
        val error = getSystemStrerror(1) // EPERM
        assertTrue(error != null && error.isNotEmpty())
    }
}
