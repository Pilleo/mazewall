package io.mazewall.profiler.tierE.daemon

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/** Selector-backed stream adapters preserve bounded framing over Unix-domain socket channels. */
internal class TierEChannelInputStream(
    private val channel: SocketChannel,
    private val timeoutMillis: Int,
) : InputStream() {
    private val selector = Selector.open()
    private val singleByte = ByteBuffer.allocate(1)

    init {
        channel.configureBlocking(false)
        channel.register(selector, SelectionKey.OP_READ)
    }

    override fun read(): Int {
        while (true) {
            if (selector.select(timeoutMillis.toLong()) == 0) throw SocketTimeoutException("Tier E control socket idle")
            selector.selectedKeys().clear()
            singleByte.clear()
            when (channel.read(singleByte)) {
                -1 -> return -1
                0 -> continue
                else -> return singleByte.get(0).toInt() and 0xff
            }
        }
    }

    override fun close() {
        selector.close()
    }
}

internal class TierEChannelOutputStream(
    private val channel: SocketChannel,
) : OutputStream() {
    private val selector = Selector.open()

    init {
        channel.register(selector, SelectionKey.OP_WRITE)
    }

    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) > 0) continue
            check(selector.select(TIER_E_CLIENT_RECEIVE_TIMEOUT_MS.toLong()) > 0) { "Tier E control socket write timed out" }
            selector.selectedKeys().clear()
        }
    }

    override fun close() {
        selector.close()
    }
}
