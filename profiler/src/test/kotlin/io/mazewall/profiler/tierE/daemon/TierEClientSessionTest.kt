package io.mazewall.profiler.tierE.daemon
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierEClientSessionTest {
    @Test
    fun `accepted client receives a bounded idle deadline`() {
        assertEquals(30_000, TIER_E_CLIENT_RECEIVE_TIMEOUT_MS)
    }

    @Test
    fun `socket access requires an explicit non-negative deployment group`() {
        assertEquals(null, TierEControlSocketAccess.fromEnvironment(emptyMap()).groupId)
        assertEquals(1234, TierEControlSocketAccess.fromEnvironment(mapOf("TIER_E_SOCKET_GID" to "1234")).groupId)
        assertFailsWith<IllegalStateException> {
            TierEControlSocketAccess.fromEnvironment(mapOf("TIER_E_SOCKET_GID" to "invalid"))
        }
    }

    @Test
    fun `target client sends only registered attach fields over a unix socket`() {
        val directory = Files.createTempDirectory("tier-e-control-")
        val endpoint = directory.resolve("control.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(endpoint))
            val worker = Thread {
                server.accept().use { peer ->
                    DataInputStream(Channels.newInputStream(peer)).use { input ->
                        DataOutputStream(Channels.newOutputStream(peer)).use { output ->
                            assertEquals("ATTACH 4242 auto /collector/agent.so 20 4d5a full-stream", input.readUTF())
                            output.writeUTF("OK ATTACHED")
                        }
                    }
                }
            }.apply { start() }
            val target = TierERegisteredTarget("orders", 4242, 0x4d5a, 9876, Path.of("/collector/agent.so"), 0x20, "orders.mzse")

            assertEquals("OK ATTACHED", TierETargetControlClient(endpoint).attach(target))
            worker.join(1_000)
        }
    }
}
