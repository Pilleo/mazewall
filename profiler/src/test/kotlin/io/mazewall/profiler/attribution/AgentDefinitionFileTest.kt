package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentDefinitionFileTest {
    @Test
    fun `decodes live snapshot envelope without changing embedded MZSD bytes`() {
        val payload = versionOneFile()
        val envelope = SnapshotEnvelope.encode(
            payload = payload,
            publicationId = 17,
            watermark = 23,
            closed = true,
        )

        val contents = AgentDefinitionFile.decode(envelope)

        assertEquals(SnapshotPublication(17, 23, true), contents.publication)
        assertEquals(0, contents.agentLosses)
        assertEquals(0, contents.stacks.size)
        assertContentEquals(payload, SnapshotEnvelope.decode(envelope).payload)
    }

    @Test
    fun `session bound snapshot rejects replay from a different session`() {
        val payload = versionOneFile()
        val envelope = SnapshotEnvelope.encode(
            payload = payload,
            publicationId = 17,
            watermark = 23,
            closed = true,
            sessionEpoch = SessionEpoch(41),
        )

        assertEquals(SessionEpoch(41), SnapshotEnvelope.decode(envelope).publication.sessionEpoch)
        assertEquals(SessionEpoch(41), AgentDefinitionFile.decodeForSession(envelope, SessionEpoch(41)).publication?.sessionEpoch)
        assertFailsWith<IllegalArgumentException> {
            AgentDefinitionFile.decodeForSession(envelope, SessionEpoch(42))
        }
    }

    @Test
    fun `legacy MZSD snapshot has unknown publication closure`() {
        val contents = AgentDefinitionFile.decode(versionOneFile())

        assertNull(contents.publication)
    }

    @Test
    fun `decodes version two virtual execution identity`() {
        val invocation = payload {
            putLong(9)
            putLong(0)
            putInt(7)
            put(0)
            putLong(42)
        }
        val execution = payload {
            putLong(42)
            put(1)
        }
        val stats = payload {
            putLong(0)
            putLong(0)
            putLong(3)
            putLong(99)
        }
        val file = ByteArrayOutputStream()
            .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(2, 0))
                record(4, execution)
                record(2, invocation)
                record(3, stats)
            }.toByteArray()

        val contents = AgentDefinitionFile.decode(file)

        assertEquals(listOf(ExecutionContext(ExecutionId(42), ExecutionKind.VIRTUAL)), contents.executions)
        assertEquals(contents.executions.single(), contents.invocations.single().executionContext)
        assertEquals(3, contents.virtualPinnedScopes)
        assertEquals(99, contents.virtualPinnedNanos)
    }

    @Test
    fun `decodes stack and invocation records`() {
        val stack = payload {
            putInt(7)
            put(0)
            putShort(1)
            putString("bootstrap")
            putString("Ljava/lang/String;")
            putString("charAt")
            putString("(I)C")
            putLong(12)
            put(0)
        }
        val invocation = payload {
            putLong(9)
            putLong(0)
            putInt(7)
            put(0)
        }
        val file = ByteArrayOutputStream()
            .apply {
            val stats = payload {
                putLong(0)
                putLong(0)
            }
            write("MZSD".toByteArray())
            write(byteArrayOf(1, 0))
            record(1, stack)
            record(2, invocation)
            record(3, stats)
        }.toByteArray()

        val contents = AgentDefinitionFile.decode(file)

        assertEquals(
            "java.lang.String",
            contents.stacks
            .single()
            .frames
            .single()
            .className,
        )
        assertEquals(StackTraceId(7), contents.invocations.single().stackTraceId)
        assertEquals(InvocationId(9), contents.invocations.single().invocationId)
        assertEquals(0, contents.agentLosses)
    }

    @Test
    fun `decodes inline invocation stack from version three`() {
        val inlineInvocation = payload {
            putLong(9)
            putLong(0)
            putInt(7)
            put(0)
            putLong(42)
            putInt(7)
            put(0)
            putShort(1)
            putString("bootstrap")
            putString("Ljava/lang/String;")
            putString("charAt")
            putString("(I)C")
            putLong(12)
            put(0)
        }
        val execution = payload {
            putLong(42)
            put(1)
        }
        val file = ByteArrayOutputStream()
            .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(3, 0))
                record(4, execution)
                record(5, inlineInvocation)
            }.toByteArray()

        val contents = AgentDefinitionFile.decode(file)

        assertEquals(StackTraceId(7), contents.stacks.single().stackTraceId)
        assertEquals(
            "java.lang.String",
            contents.stacks
            .single()
            .frames
            .single()
            .className,
        )
        assertEquals(InvocationId(9), contents.invocations.single().invocationId)
        assertEquals(StackTraceId(7), contents.invocations.single().stackTraceId)
        assertEquals(
            ExecutionKind.VIRTUAL,
            contents.invocations
            .single()
            .executionContext
            ?.kind,
        )
    }

    @Test
    fun `dictionary and inline transports preserve normalized decoded evidence`() {
        val dictionaryStack = payload {
            putInt(7)
            put(0)
            putShort(1)
            putString("bootstrap")
            putString("Ljava/lang/String;")
            putString("charAt")
            putString("(I)C")
            putLong(12)
            put(0)
        }
        val dictionaryInvocation = payload {
            putLong(9)
            putLong(0)
            putInt(7)
            put(0)
            putLong(42)
        }
        val inlineInvocation = payload {
            putLong(99)
            putLong(0)
            putInt(13)
            put(0)
            putLong(100)
            putInt(13)
            put(0)
            putShort(1)
            putString("bootstrap")
            putString("Ljava/lang/String;")
            putString("charAt")
            putString("(I)C")
            putLong(12)
            put(0)
        }
        val dictionary = AgentDefinitionFile.decode(
            ByteArrayOutputStream()
                .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(2, 0))
                record(1, dictionaryStack)
                record(
                    4,
                    payload {
                    putLong(42)
                    put(1)
                },
                )
                record(2, dictionaryInvocation)
            }.toByteArray(),
        )
        val inline = AgentDefinitionFile.decode(
            ByteArrayOutputStream()
                .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(3, 0))
                record(
                    4,
                    payload {
                    putLong(100)
                    put(1)
                },
                )
                record(5, inlineInvocation)
            }.toByteArray(),
        )

        val dictionaryInvocationDefinition = dictionary.invocations.single()
        val inlineInvocationDefinition = inline.invocations.single()
        assertEquals(
            dictionary.stacks.single().frames,
            inline.stacks.single().frames,
        )
        assertEquals(dictionaryInvocationDefinition.captureFailure, inlineInvocationDefinition.captureFailure)
        assertEquals(
            dictionaryInvocationDefinition.executionContext?.kind,
            inlineInvocationDefinition.executionContext?.kind,
        )
    }

    @Test
    fun `rejects stack records that exceed the native frame limit`() {
        val stack = payload {
            putInt(7)
            put(0)
            putShort(130)
        }
        val file = ByteArrayOutputStream()
            .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(2, 0))
                record(1, stack)
            }.toByteArray()

        assertFailsWith<IllegalArgumentException> { AgentDefinitionFile.decode(file) }
    }

    @Test
    fun `rejects definition files larger than the retained-state limit before reading`() {
        val file = Files.createTempFile("tier-e-definition-limit", ".mzsd")
        try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                channel.position(64L * 1024L * 1024L)
                channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            }

            assertFailsWith<IllegalArgumentException> { AgentDefinitionFile.read(file) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `classifies decoded record budget exhaustion`() {
        val stats = payload {
            putLong(0)
            putLong(0)
        }
        val file = ByteArrayOutputStream()
            .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(2, 0))
                repeat(300_001) { record(3, stats) }
            }.toByteArray()

        val failure = assertFailsWith<IllegalArgumentException> { AgentDefinitionFile.decode(file) }

        assertEquals("agent definition file exceeds decoded record budget", failure.message)
    }

    @Test
    fun `selective decoding retains a bounded invocation subset beyond the full record budget`() {
        val stats = payload {
            putLong(0)
            putLong(0)
        }
        val file = ByteArrayOutputStream()
            .apply {
                write("MZSD".toByteArray())
                write(byteArrayOf(2, 0))
                repeat(300_001) { record(3, stats) }
            }.toByteArray()

        val contents = AgentDefinitionFile.decodeForInvocations(file, emptySet())

        assertEquals(emptyList(), contents.invocations)
        assertEquals(0, contents.agentLosses)
    }

    private fun payload(block: LittleEndianWriter.() -> Unit): ByteArray = LittleEndianWriter().apply(block).bytes()

    private fun versionOneFile(): ByteArray =
        ByteArrayOutputStream()
            .apply {
        val stats = payload {
            putLong(0)
            putLong(0)
        }
        write("MZSD".toByteArray())
        write(byteArrayOf(1, 0))
        record(3, stats)
    }.toByteArray()

    private fun ByteArrayOutputStream.record(
        type: Int,
        payload: ByteArray,
    ) {
        write(type)
        write(
            ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size)
            .array(),
        )
        write(payload)
    }

    private class LittleEndianWriter {
        private val output = ByteArrayOutputStream()

        fun put(value: Int) {
            output.write(value)
        }

        fun putShort(value: Int) {
            output.write(
                ByteBuffer
                .allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(value.toShort())
                .array(),
            )
        }

        fun putInt(value: Int) {
            output.write(
                ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array(),
            )
        }

        fun putLong(value: Long) {
            output.write(
                ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array(),
            )
        }

        fun putString(value: String) {
            val bytes = value.toByteArray()
            putShort(bytes.size)
            output.write(bytes)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
