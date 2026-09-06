package io.mazewall.profiler.attribution

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentDefinitionFileTest {
    @Test
    fun `decodes stack and invocation records`() {
        val stack = payload {
            putInt(7); put(0); putShort(1)
            putString("bootstrap"); putString("Ljava/lang/String;"); putString("charAt"); putString("(I)C")
            putLong(12); put(0)
        }
        val invocation = payload { putLong(9); putLong(0); putInt(7); put(0) }
        val file = ByteArrayOutputStream().apply {
            val stats = payload { putLong(0); putLong(0) }
            write("MZSD".toByteArray()); write(byteArrayOf(1, 0)); record(1, stack); record(2, invocation); record(3, stats)
        }.toByteArray()

        val contents = AgentDefinitionFile.decode(file)

        assertEquals("java.lang.String", contents.stacks.single().frames.single().className)
        assertEquals(StackTraceId(7), contents.invocations.single().stackTraceId)
        assertEquals(InvocationId(9), contents.invocations.single().invocationId)
        assertEquals(0, contents.agentLosses)
    }

    private fun payload(block: LittleEndianWriter.() -> Unit): ByteArray = LittleEndianWriter().apply(block).bytes()

    private fun ByteArrayOutputStream.record(type: Int, payload: ByteArray) {
        write(type); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()); write(payload)
    }

    private class LittleEndianWriter {
        private val output = ByteArrayOutputStream()
        fun put(value: Int) { output.write(value) }
        fun putShort(value: Int) { output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()) }
        fun putInt(value: Int) { output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun putLong(value: Long) { output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun putString(value: String) { val bytes = value.toByteArray(); putShort(bytes.size); output.write(bytes) }
        fun bytes(): ByteArray = output.toByteArray()
    }
}
