package io.mazewall.enforcer

import io.mazewall.LinuxNative
import io.mazewall.NeedsFreshJvm
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.enforcer.supervisor.SupervisorDaemonManager
import io.mazewall.ffi.networking.SupervisorSocketUtils
import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

@NeedsFreshJvm
class SupervisorDoSIntegrationTest {
    @Test
    fun testBoundedThreadCreation() {
        if (!System.getProperty("os.name").equals("Linux", ignoreCase = true)) return

        val context = SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()
        val pid = context.daemonProcess.pid()
        val socketPath = context.socketPath

        val baselineThreads = getThreadCount(pid)
        val baselineDescriptors = getDescriptorCount(pid)
        System.err.println("Baseline threads for supervisor ($pid): $baselineThreads")

        val connections = mutableListOf<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>>()
        val numConnections = 250 // More than MAX_CONNECTIONS (200)
        try {
            for (i in 1..numConnections) {
                val fd = SupervisorSocketUtils.connectDescriptorWithRetry(socketPath)
                connections.add(fd)
            }

            awaitCondition(description = "supervisor accepted its 200-connection capacity") {
                getDescriptorCount(pid) >= baselineDescriptors + 200
            }

            val currentThreads = getThreadCount(pid)
            System.err.println("Threads after $numConnections connections: $currentThreads")

            // MAX_CONNECTIONS is 200. Baseline is around 20. Total should be around 220.
            // We'll allow a margin.
            val maxExpected = 200 + baselineThreads + 10
            assertTrue(
                currentThreads <= maxExpected,
                "Thread count should be bounded. Expected at most $maxExpected, got $currentThreads",
            )
        } finally {
            connections.forEach {
                LinuxNative.fileSystem.close(it)
            }
            SupervisorDaemonManager.getInstance().stop()
        }
    }

    private fun getThreadCount(pid: Long): Int {
        val statusFile = File("/proc/$pid/status")
        if (!statusFile.exists()) return -1
        val line = statusFile.readLines().find { it.startsWith("Threads:") } ?: return -1
        return line.substringAfter("Threads:").trim().toInt()
    }

    private fun getDescriptorCount(pid: Long): Int = File("/proc/$pid/fd").list()?.size ?: -1
}
