package io.mazewall.profiler.tierE.daemon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal const val TIER_E_CLIENT_RECEIVE_TIMEOUT_MS: Int = 30_000

/** Framed controller session with a bounded idle receive and explicit shutdown polling. */
internal object TierEClientSession {
    fun run(
        client: Socket,
        stopRequested: AtomicBoolean,
        executeCommand: (String) -> String,
    ) {
        client.soTimeout = TIER_E_CLIENT_RECEIVE_TIMEOUT_MS
        try {
            DataInputStream(client.getInputStream()).use { input ->
                DataOutputStream(client.getOutputStream()).use { output ->
                    while (!stopRequested.get()) {
                        val line = try {
                            input.readUTF()
                        } catch (_: SocketTimeoutException) {
                            // DataInputStream.readUTF() cannot resume safely after a timeout
                            // halfway through its length-prefixed frame. Retire this client
                            // rather than interpreting the remaining bytes as a new frame.
                            return
                        } catch (_: EOFException) {
                            return
                        }
                        output.writeUTF(executeCommand(line))
                        output.flush()
                    }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
