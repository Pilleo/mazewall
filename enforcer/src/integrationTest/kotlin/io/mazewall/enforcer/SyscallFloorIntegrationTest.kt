package io.mazewall.enforcer

import io.mazewall.BaseIntegrationTest
import io.mazewall.EnabledIfLinuxAndSupported
import io.mazewall.Policy
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.test.assertTrue

class SyscallFloorIntegrationTest : BaseIntegrationTest() {

    /**
     * Verifies that ioctl(FIONBIO) is allowed by default because it's in the critical floor's
     * inspection whitelist.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `ioctl FIONBIO is allowed by default`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .allow(Syscall.SOCKET)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy)

        try {
            val result = containedExecutor.submit<Boolean> {
                try {
                    val socket = java.nio.channels.SocketChannel.open()
                    socket.configureBlocking(false) // Triggers ioctl(FIONBIO)
                    socket.close()
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }.get()

            assertTrue(result, "ioctl(FIONBIO) should be allowed")
        } finally {
            rawExecutor.shutdown()
        }
    }

    /**
     * Verifies that Project Loom virtual threads can yield and reschedule successfully
     * under a restrictive policy, which requires eventfd2 and epoll_wait in the critical floor.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `loom virtual threads work under restrictive policy`() {
        val policy = Policy.builder()
            .defaultAction(SeccompAction.ACT_ERRNO)
            .build()

        val rawExecutor = Executors.newSingleThreadExecutor()
        val containedExecutor = ContainedExecutors.wrap(rawExecutor, policy)

        try {
            val result = containedExecutor.submit<Boolean> {
                try {
                    val loomExecutor = Executors.newVirtualThreadPerTaskExecutor()
                    val task = loomExecutor.submit {
                        Thread.sleep(10)
                        Thread.yield()
                        "done"
                    }
                    task.get() == "done"
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }.get()

            assertTrue(result, "Virtual threads should yield and complete successfully")
        } finally {
            rawExecutor.shutdown()
        }
    }
}
