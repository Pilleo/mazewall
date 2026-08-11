package io.mazewall.seccomp

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.ScopingHandler
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupervisorAcceptIntegrationTest : BaseIntegrationTest() {

    companion object {
        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDownAll() {
            io.mazewall.enforcer.supervisor.SupervisorDaemonManager.getInstance().stop()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `test supervised accept accepts connection and populates peer address`() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val allowedCalls = mutableListOf<String>()
        val deniedCalls = mutableListOf<String>()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.ACCEPT to { tid, args, stack -> authorize(stack) },
                Syscall.ACCEPT4 to { tid, args, stack -> authorize(stack) }
            )

            private fun authorize(stack: List<StackTraceElement>): Boolean {
                val isLegit = stack.any { it.className.contains("LegitContext") }
                if (isLegit) {
                    allowedCalls.add("accept")
                } else {
                    deniedCalls.add("accept")
                }
                return isLegit
            }
        }

        // We block socket actions in the thread filter, forcing them to be supervised
        val policy = Policy.builder()
            .base(Policy.PURE_COMPUTE_UNSAFE)
            .allowJvmClasspath()
            .block(Syscall.ACCEPT, Syscall.ACCEPT4)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy, scopingPolicy)

        try {
            val acceptFuture = containedExecutor.submit<String> {
                System.err.println("[TEST-TRACEE] Lambda started. Creating LegitContext...")
                class LegitContext(val ss: ServerSocket) {
                    fun run(): String {
                        System.err.println("[TEST-TRACEE] LegitContext.run() started. Calling ss.accept()...")
                        val client = ss.accept()
                        System.err.println("[TEST-TRACEE] ss.accept() returned socket: $client")
                        client.use {
                            val input = client.getInputStream().read()
                            client.getOutputStream().write(42)
                            client.getOutputStream().flush()
                            return client.remoteSocketAddress.toString()
                        }
                    }
                }
                LegitContext(serverSocket).run()
            }

            // Connect client from host thread
            Socket("127.0.0.1", port).use { clientSocket ->
                clientSocket.getOutputStream().write(24)
                clientSocket.getOutputStream().flush()
                val response = clientSocket.getInputStream().read()
                assertEquals(42, response)
            }

            val peerAddressStr = acceptFuture.get()
            assertTrue(peerAddressStr.isNotEmpty(), "Peer address should be populated")
            assertTrue(allowedCalls.isNotEmpty(), "Legit accept should be allowed by scoping policy")
            assertTrue(deniedCalls.isEmpty(), "No evil accepts should have occurred")
        } finally {
            serverSocket.close()
            rawExecutor.shutdown()
            System.err.println("--- DAEMON LOGS ---")
            try {
                val logFile = java.io.File("/tmp/supervisor_daemon.log")
                if (logFile.exists()) {
                    System.err.println(logFile.readText())
                } else {
                    System.err.println("Daemon log file does not exist")
                }
            } catch (e: Exception) {
                System.err.println("Failed to read daemon log file: ${e.message}")
            }
            System.err.println("-------------------")
        }
    }
}
