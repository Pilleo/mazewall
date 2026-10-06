package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.supervisor.SupervisorDaemonManager
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@NeedsFreshJvm
class SupervisorConnectIntegrationTest : BaseIntegrationTest() {
    @Test
    @EnabledIfLinuxAndSupported
    fun `supervised connect retains original bound TCP and UDP sockets and options`() {
        val observed = AtomicInteger()
        val scope = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.CONNECT to { _, _, _ ->
                observed.incrementAndGet()
                true
            },
            )
        }
        try {
            Executors.newSingleThreadExecutor().use { raw ->
                val contained = ContainedExecutors.wrap(raw, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowMmapExec().build(), scope)
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                    server.soTimeout = 10000
                    Socket().use { socket ->
                        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                        socket.tcpNoDelay = true
                        val port = socket.localPort
                        val connected = contained.submit<Boolean> {
                            socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 10000)
                            socket.getOutputStream().write(61)
                            socket.tcpNoDelay
                        }
                        server.accept().use { peer ->
                            peer.soTimeout = 10000
                            assertEquals(port, peer.port)
                            assertEquals(61, peer.getInputStream().read())
                        }
                        assertTrue(connected.get(15, TimeUnit.SECONDS))
                    }
                }
                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
                    receiver.soTimeout = 10000
                    DatagramSocket(0, InetAddress.getLoopbackAddress()).use { sender ->
                        val port = sender.localPort
                        contained
                            .submit {
                            sender.connect(InetSocketAddress("127.0.0.1", receiver.localPort))
                            sender.send(DatagramPacket(byteArrayOf(62), 1))
                        }.get(15, TimeUnit.SECONDS)
                        val packet = DatagramPacket(ByteArray(1), 1)
                        receiver.receive(packet)
                        assertEquals(port, packet.port)
                        assertEquals(62, packet.data[0].toInt())
                    }
                }
                assertTrue(observed.get() >= 2, "both socket types must traverse USER_NOTIF authorization")
            }
        } finally {
            SupervisorDaemonManager.getInstance().stop()
        }
    }
}
