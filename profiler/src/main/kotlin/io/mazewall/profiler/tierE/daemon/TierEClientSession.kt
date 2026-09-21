package io.mazewall.profiler.tierE.daemon

import java.io.DataOutputStream
import java.net.Socket
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

internal const val TIER_E_CLIENT_RECEIVE_TIMEOUT_MS: Int = 30_000
internal const val TIER_E_CLIENT_FRAME_DEADLINE_MS: Int = 30_000

/** Framed controller session with a bounded idle receive and explicit shutdown polling. */
internal object TierEClientSession {
    fun run(
        client: Socket,
        stopRequested: AtomicBoolean,
        executeCommand: (String) -> String,
    ) {
        client.soTimeout = TIER_E_CLIENT_RECEIVE_TIMEOUT_MS
        try {
            TierEFrameDecoder(client.getInputStream()).let { input ->
                DataOutputStream(client.getOutputStream()).use { output ->
                    while (!stopRequested.get()) {
                        when (val frame = input.readFrame(System.nanoTime() + TIER_E_CLIENT_FRAME_DEADLINE_MS * 1_000_000L)) {
                            FrameReadResult.Idle -> continue
                            FrameReadResult.PartialTimeout,
                            FrameReadResult.DeadlineExceeded,
                            FrameReadResult.EndOfStream,
                            -> return
                            is FrameReadResult.Frame -> {
                                output.writeUTF(executeCommand(frame.value))
                                output.flush()
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    /** Unix-domain control path with the same modified-UTF framing and receive deadlines. */
    fun run(
        client: SocketChannel,
        stopRequested: AtomicBoolean,
        executeCommand: (String) -> String,
    ) {
        try {
            TierEChannelInputStream(client, TIER_E_CLIENT_RECEIVE_TIMEOUT_MS).use { stream ->
                DataOutputStream(TierEChannelOutputStream(client)).use { output ->
                    val input = TierEFrameDecoder(stream)
                    while (!stopRequested.get()) {
                        when (val frame = input.readFrame(System.nanoTime() + TIER_E_CLIENT_FRAME_DEADLINE_MS * 1_000_000L)) {
                            FrameReadResult.Idle -> continue
                            FrameReadResult.PartialTimeout,
                            FrameReadResult.DeadlineExceeded,
                            FrameReadResult.EndOfStream,
                            -> return
                            is FrameReadResult.Frame -> {
                                output.writeUTF(executeCommand(frame.value))
                                output.flush()
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
