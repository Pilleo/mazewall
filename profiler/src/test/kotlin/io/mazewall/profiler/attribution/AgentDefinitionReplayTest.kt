package io.mazewall.profiler.attribution

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentDefinitionReplayTest {
    @Test
    fun `identical definition records are idempotent during replay`() {
        val definition = definition(11, "bar")
        val record = StackDictionaryStreamEncoder.encodeStackDefinition(definition)

        val decoded = AgentDefinitionFile.decode(file(record, record))

        assertEquals(listOf(definition), decoded.stacks)
    }

    @Test
    fun `same definition identity with changed content is terminally rejected`() {
        val first = StackDictionaryStreamEncoder.encodeStackDefinition(definition(11, "bar"))
        val second = StackDictionaryStreamEncoder.encodeStackDefinition(definition(11, "changed"))

        assertFailsWith<IllegalArgumentException> {
            AgentDefinitionFile.decode(file(first, second))
        }
    }

    private fun file(vararg records: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("MZSD".toByteArray(StandardCharsets.US_ASCII))
        output.write(byteArrayOf(1, 0))
        records.forEach(output::write)
        return output.toByteArray()
    }

    private fun definition(
        id: Long,
        method: String,
    ): StackDefinition =
        StackDefinition(
        StackTraceId(id),
        listOf(ManagedFrame("app", "Foo", method, "()V", 1L, ManagedFrameKind.JAVA)),
        StackCaptureQuality.COMPLETE,
    )
}
