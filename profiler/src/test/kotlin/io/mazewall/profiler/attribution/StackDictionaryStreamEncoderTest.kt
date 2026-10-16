package io.mazewall.profiler.internal.attribution
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StackDictionaryStreamEncoderTest {
    @Test
    fun `enqueues and encodes definitions compatible with AgentDefinitionFile`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 10)
        val frame = ManagedFrame("app", "com.example.Foo", "bar", "(I)V", 42L, ManagedFrameKind.JAVA)
        val def = StackDefinition(StackTraceId(101L), listOf(frame), StackCaptureQuality.COMPLETE)

        encoder.enqueue(def)
        assertEquals(1, encoder.pendingCount)

        val encoded = encoder.drainEncoded()
        assertEquals(0, encoder.pendingCount)
        assertEquals(101L, encoder.watermark)
        assertEquals(0L, encoder.droppedCount)

        // Decode with AgentDefinitionFile by adding the MZSD header and stats record
        val fullFile = ByteArrayOutputStream()
            .apply {
            write("MZSD".toByteArray())
            write(byteArrayOf(1, 0)) // Version 1, reserved 0
            write(encoded)
            // Empty stats record
            val statsPayload = ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(0L)
                .putLong(0L)
                .array()
            val statsHeader = ByteBuffer
                .allocate(5)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(3.toByte())
                .putInt(statsPayload.size)
                .array()
            write(statsHeader)
            write(statsPayload)
        }.toByteArray()

        val decoded = AgentDefinitionFile.decode(fullFile)
        assertEquals(1, decoded.stacks.size)
        val decodedDef = decoded.stacks.single()
        assertEquals(StackTraceId(101L), decodedDef.stackTraceId)
        assertEquals(StackCaptureQuality.COMPLETE, decodedDef.captureQuality)
        assertEquals(1, decodedDef.frames.size)
        val decodedFrame = decodedDef.frames.single()
        assertEquals("app", decodedFrame.classLoaderIdentity)
        assertEquals("com.example.Foo", decodedFrame.className)
        assertEquals("bar", decodedFrame.methodName)
        assertEquals("(I)V", decodedFrame.descriptor)
        assertEquals(42L, decodedFrame.bytecodeLocation)
        assertEquals(ManagedFrameKind.JAVA, decodedFrame.kind)
    }

    @Test
    fun `drops oldest entries when pending queue exceeds bound`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 2)
        val frame = ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)

        val def1 = StackDefinition(StackTraceId(1L), listOf(frame), StackCaptureQuality.COMPLETE)
        val def2 = StackDefinition(StackTraceId(2L), listOf(frame), StackCaptureQuality.COMPLETE)
        val def3 = StackDefinition(StackTraceId(3L), listOf(frame), StackCaptureQuality.COMPLETE)

        encoder.enqueue(def1)
        encoder.enqueue(def2)
        encoder.enqueue(def3)

        assertEquals(2, encoder.pendingCount)
        assertEquals(1L, encoder.droppedCount)

        val encoded = encoder.drainEncoded()
        assertEquals(3L, encoder.watermark)
        assertEquals(0, encoder.pendingCount)
    }

    @Test
    fun `ignores entries at or below watermark`() {
        val encoder = StackDictionaryStreamEncoder()
        val frame = ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)
        val def1 = StackDefinition(StackTraceId(5L), listOf(frame), StackCaptureQuality.COMPLETE)

        encoder.enqueue(def1)
        encoder.drainEncoded()
        assertEquals(5L, encoder.watermark)

        // Attempt re-enqueuing at watermark
        encoder.enqueue(def1)
        assertEquals(0, encoder.pendingCount)

        // Attempt enqueuing below watermark
        val defOlder = StackDefinition(StackTraceId(4L), listOf(frame), StackCaptureQuality.COMPLETE)
        encoder.enqueue(defOlder)
        assertEquals(0, encoder.pendingCount)
    }

    @Test
    fun `prepared batch can be rolled back and retried byte-for-byte before commit`() {
        val encoder = StackDictionaryStreamEncoder(maxPendingEntries = 4)
        val frame = ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)
        val first = StackDefinition(StackTraceId(10L), listOf(frame), StackCaptureQuality.COMPLETE)
        val second = StackDefinition(StackTraceId(11L), listOf(frame), StackCaptureQuality.COMPLETE)
        encoder.enqueue(first)
        encoder.enqueue(second)

        val prepared = requireNotNull(encoder.prepareBatch(maxCount = 2))
        assertEquals(2, encoder.pendingCount)
        assertEquals(0L, encoder.watermark)
        encoder.rollback(prepared)

        val retry = requireNotNull(encoder.prepareBatch(maxCount = 2))
        assertContentEquals(prepared.bytes, retry.bytes)
        assertEquals(prepared.proposedWatermark, retry.proposedWatermark)
        encoder.commit(retry)
        assertEquals(0, encoder.pendingCount)
        assertEquals(11L, encoder.watermark)
    }

    @Test
    fun `encoder permits only one outstanding prepared batch`() {
        val encoder = StackDictionaryStreamEncoder()
        val frame = ManagedFrame("app", "Foo", "bar", "()V", 1L, ManagedFrameKind.JAVA)
        encoder.enqueue(StackDefinition(StackTraceId(10L), listOf(frame), StackCaptureQuality.COMPLETE))

        val batch = requireNotNull(encoder.prepareBatch())
        assertFailsWith<IllegalStateException> { encoder.prepareBatch() }
        encoder.rollback(batch)
    }
}
