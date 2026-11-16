package io.mazewall.profiler.tierE.engine
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpfProgLoadRequestEncoderTest {
    @Test
    fun `diagnostic extension requests stats and preserves other default bytes`() {
        Arena.ofConfined().use { arena ->
            val program = listOf(TierEbpfEngine.Insn(code = 0x18, src = 1, imm = 0))
            val ordinary = BpfProgLoadRequestEncoder.encode(arena, program, listOf(41), 17, "tier_e")
            val enabled = BpfProgLoadRequestEncoder.encode(arena, program, listOf(41), 17, "tier_e", true)
            assertEquals(104L, ordinary.attr.byteSize())
            assertEquals(144L, enabled.attr.byteSize())
            assertEquals(140L, BpfProgLoadLayout.LOG_TRUE_SIZE)
            assertEquals(1, ordinary.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_LEVEL))
            assertEquals(4, enabled.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_LEVEL))
            for (offset in 0L until 104L) {
                if (offset !in 8L..23L && offset !in 24L..27L && offset !in 32L..39L) assertEquals(ordinary.attr.get(ValueLayout.JAVA_BYTE, offset), enabled.attr.get(ValueLayout.JAVA_BYTE, offset))
            }
            assertTrue((104L until 144L).all { enabled.attr.get(ValueLayout.JAVA_BYTE, it) == 0.toByte() })
            assertEquals(ordinary.instructions.toArray(ValueLayout.JAVA_BYTE).toList(), enabled.instructions.toArray(ValueLayout.JAVA_BYTE).toList())
            assertEquals(65536L, enabled.verifierLog.byteSize())
        }
    }

    @Test
    fun `enabled cap is checked before encoding but ordinary program cap is unchanged`() {
        Arena.ofConfined().use { arena ->
            val program = List(1025) { TierEbpfEngine.Insn(code = 0x95) }
            assertEquals(8192L, BpfProgLoadRequestEncoder.encode(arena, program.take(1024), emptyList(), 17, "limit", true).instructions.byteSize())
            assertFailsWith<IllegalArgumentException> { BpfProgLoadRequestEncoder.encode(arena, program, emptyList(), 17, "limit", true) }
            assertEquals(8200L, BpfProgLoadRequestEncoder.encode(arena, program, emptyList(), 17, "limit").instructions.byteSize())
        }
    }

    @Test
    fun `encodes program load attributes and substitutes pseudo map descriptors`() {
        Arena.ofConfined().use { arena ->
            val request =
                BpfProgLoadRequestEncoder.encode(
                    arena = arena,
                    program = listOf(TierEbpfEngine.Insn(code = 0x18, src = 1, imm = 0)),
                    mapFds = listOf(41),
                    programType = 17,
                    programName = "tier_e",
                )

            assertEquals(17, request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.PROGRAM_TYPE))
            assertEquals(1, request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.INSTRUCTION_COUNT))
            assertEquals(41L shl 32 or 0x1018L, request.instructions.get(ValueLayout.JAVA_LONG, 0))
            assertEquals(
                request.instructions.address(),
                request.attr.get(ValueLayout.ADDRESS, BpfProgLoadLayout.INSTRUCTIONS).address(),
            )
            assertEquals(
                "GPL",
                request.attr
                    .get(ValueLayout.ADDRESS, BpfProgLoadLayout.LICENSE)
                    .reinterpret(4)
                    .getString(0),
            )
            assertEquals(1, request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_LEVEL))
            assertEquals(
                request.verifierLog.byteSize().toInt(),
                request.attr.get(ValueLayout.JAVA_INT, BpfProgLoadLayout.LOG_SIZE),
            )
            assertEquals(
                request.verifierLog.address(),
                request.attr.get(ValueLayout.ADDRESS, BpfProgLoadLayout.LOG_BUFFER).address(),
            )
            assertEquals("tier_e", request.attr.asSlice(BpfProgLoadLayout.PROGRAM_NAME).getString(0))
        }
    }

    @Test
    fun `accepts the maximum ABI program name and rejects an overlong name`() {
        Arena.ofConfined().use { arena ->
            val maximumName = "a".repeat(BpfProgLoadLayout.PROGRAM_NAME_SIZE - 1)
            val request = BpfProgLoadRequestEncoder.encode(arena, emptyList(), emptyList(), 17, maximumName)

            assertEquals(maximumName, request.attr.asSlice(BpfProgLoadLayout.PROGRAM_NAME).getString(0))
            assertFailsWith<IllegalArgumentException> {
                BpfProgLoadRequestEncoder.encode(
                    arena,
                    emptyList(),
                    emptyList(),
                    17,
                    "a".repeat(BpfProgLoadLayout.PROGRAM_NAME_SIZE),
                )
            }
        }
    }

    @Test
    fun `rejects a pseudo map descriptor without a corresponding map fd`() {
        Arena.ofConfined().use { arena ->
            val error = assertFailsWith<IllegalArgumentException> {
                BpfProgLoadRequestEncoder.encode(
                    arena,
                    listOf(TierEbpfEngine.Insn(code = 0x18, src = 1, imm = 1)),
                    listOf(41),
                    17,
                    "tier_e",
                )
            }

            assertTrue(error.message.orEmpty().contains("Missing map FD"))
        }
    }

    @Test
    fun `rejects non-ASCII program names instead of silently substituting bytes`() {
        Arena.ofConfined().use { arena ->
            assertFailsWith<IllegalArgumentException> {
                BpfProgLoadRequestEncoder.encode(
                    arena = arena,
                    program = emptyList(),
                    mapFds = emptyList(),
                    programType = 17,
                    programName = "é",
                )
            }
        }
    }

    @Test
    fun `rejects instructions whose register fields cannot be encoded`() {
        Arena.ofConfined().use { arena ->
            assertFailsWith<IllegalArgumentException> {
                BpfProgLoadRequestEncoder.encode(
                    arena = arena,
                    program = listOf(TierEbpfEngine.Insn(code = 0x18, dst = 16)),
                    mapFds = emptyList(),
                    programType = 17,
                    programName = "tier_e",
                )
            }
        }
    }
}
