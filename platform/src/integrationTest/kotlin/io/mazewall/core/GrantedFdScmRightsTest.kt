package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.ffi.memory.nativeScope
import io.mazewall.getFdOrThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Kernel round-trip: broker sends a file FD over a Unix socket; worker receives it as [FileDescriptorRole.Granted].
 */
class GrantedFdScmRightsTest {
    @Test
    fun `sendmsg SCM_RIGHTS adopts the payload as Granted`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))

        val payload = Files.createTempFile("mazewall-granted-", ".txt")
        Files.writeString(payload, "granted-fd")
        val endpoint =
            PrivateUnixEndpoint.create(RealProcessLauncher, "mazewall-granted-ipc-", "ipc.sock")
        val server = RealSocketManager.createUnixServer(endpoint.path)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val acceptFuture =
                executor.submit<FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>> {
                    RealSocketManager.accept(server)
                }
            val client = RealSocketManager.connect(endpoint.path)
            val peer = acceptFuture.get(5, TimeUnit.SECONDS)

            val source =
                nativeScope {
                    LinuxNative.fileSystem
                        .open(allocateFrom(payload.toAbsolutePath().toString()), OpenFlags(0))
                        .getFdOrThrow("open granted payload")
                }

            assertTrue(RealSocketManager.sendDescriptor(client, source))
            val granted =
                RealSocketManager.recvDescriptor(peer, FileDescriptorRole.Granted)
                    ?: error("expected SCM_RIGHTS payload")
            assertEquals(FileDescriptorRole.Granted, granted.role)
            assertTrue(granted.isLiveForIo())

            LinuxNative.fileSystem.close(source)
            LinuxNative.fileSystem.close(granted)
            RealSocketManager.close(client)
            RealSocketManager.close(peer)
        } finally {
            RealSocketManager.close(server)
            endpoint.close()
            executor.shutdownNow()
            Files.deleteIfExists(payload)
        }
    }
}
