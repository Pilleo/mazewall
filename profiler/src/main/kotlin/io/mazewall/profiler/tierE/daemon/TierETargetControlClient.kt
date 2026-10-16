package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** Unprivileged target-side client constrained to an operator-registered target. */
internal class TierETargetControlClient(
    private val endpoint: Path,
) {
    fun attach(
        target: TierERegisteredTarget,
        emissionMode: TierEEmissionMode = TierEEmissionMode.FULL_STREAM,
    ): String =
        request(
        "ATTACH ${target.pid} auto ${target.markerPath} ${target.markerOffset.toString(16)} " +
            "${target.sessionTag.toString(16)} ${emissionMode.wireName()}",
    )

    fun detach(): String = request("DETACH")

    private fun request(command: String): String {
        require(endpoint.isAbsolute) { "Tier E control endpoint must be absolute" }
        return SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(endpoint))
            DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                output.writeUTF(command)
                output.flush()
                DataInputStream(Channels.newInputStream(channel)).use(DataInputStream::readUTF)
            }
        }
    }

    private fun TierEEmissionMode.wireName(): String =
        when (this) {
            TierEEmissionMode.FULL_STREAM -> "full-stream"
            TierEEmissionMode.UNIQUE_STACK_SYSCALL -> "unique-stack-syscall"
        }
}
