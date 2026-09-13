package io.mazewall.profiler.tierE.daemon

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

class TierEFrameDecoderTest {
    @Test
    fun `timeout before a frame is idle`() {
        val decoder = TierEFrameDecoder(TimeoutInputStream()) { 0L }

        assertEquals(FrameReadResult.Idle, decoder.readFrame(30L))
    }

    @Test
    fun `end of stream is not treated as idle`() {
        val decoder = TierEFrameDecoder(SequenceInputStream(byteArrayOf(), timeoutAfter = null)) { 0L }

        assertEquals(FrameReadResult.EndOfStream, decoder.readFrame(30L))
    }

    @Test
    fun `partial header timeout retires framing`() {
        val decoder = TierEFrameDecoder(SequenceInputStream(byteArrayOf(0), timeoutAfter = 1)) { 0L }

        assertEquals(FrameReadResult.PartialTimeout, decoder.readFrame(30L))
    }

    @Test
    fun `partial payload timeout retires framing`() {
        val bytes = utfFrame("status")
        val decoder = TierEFrameDecoder(SequenceInputStream(bytes, timeoutAfter = 3)) { 0L }

        assertEquals(FrameReadResult.PartialTimeout, decoder.readFrame(30L))
    }

    @Test
    fun `periodic bytes cannot evade whole frame deadline`() {
        val decoder = TierEFrameDecoder(SequenceInputStream(utfFrame("status"), timeoutAfter = null)) {
            clock += 11L
            clock
        }

        assertEquals(FrameReadResult.DeadlineExceeded, decoder.readFrame(50L))
    }

    @Test
    fun `modified UTF frame is decoded exactly`() {
        val decoder = TierEFrameDecoder(SequenceInputStream(utfFrame("hello\u0000world"), timeoutAfter = null)) { 0L }

        assertEquals(FrameReadResult.Frame("hello\u0000world"), decoder.readFrame(Long.MAX_VALUE))
    }

    private var clock = 0L

    private fun utfFrame(value: String): ByteArray =
        ByteArrayOutputStream()
            .apply {
        java.io.DataOutputStream(this).use { it.writeUTF(value) }
    }.toByteArray()

    private class TimeoutInputStream : InputStream() {
        override fun read(): Int = throw SocketTimeoutException("idle")
    }

    private class SequenceInputStream(
        private val bytes: ByteArray,
        private val timeoutAfter: Int?,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (timeoutAfter != null && index >= timeoutAfter) throw SocketTimeoutException("timeout")
            if (index == bytes.size) return -1
            return bytes[index++].toInt() and 0xff
        }
    }
}
