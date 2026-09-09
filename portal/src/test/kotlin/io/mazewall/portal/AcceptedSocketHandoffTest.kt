package io.mazewall.portal

import io.mazewall.LinuxNative
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.RealSocketManager
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.readInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AcceptedSocketHandoffTest {
    @Test
    fun `pre-native retirement failure retains descriptor for one later retry`() {
        withSocketPair { accepted, peer ->
            var attempts = 0
            val handoff = AcceptedSocketHandoff { descriptor ->
                if (++attempts == 1) throw java.io.IOException("before close")
                RealSocketManager.close(descriptor)
            }
            handoff.publish(accepted)
            handoff.completeProducer()

            assertThrows(java.io.IOException::class.java) { handoff.cancel() }
            assertTrue(accepted.isLiveForIo())
            assertFalse(handoff.fullyRetired)

            assertTrue(handoff.cancel())
            assertFalse(accepted.isLiveForIo())
            assertEquals(2, attempts)
            if (peer.isLiveForIo()) RealSocketManager.close(peer)
        }
    }

    @Test
    fun `descriptor published after cancellation is closed instead of abandoned`() {
        withSocketPair { accepted, peer ->
            val mayPublish = CountDownLatch(1)
            val published = CountDownLatch(1)
            val handoff = AcceptedSocketHandoff(RealSocketManager::close)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val producer = executor.submit {
                    mayPublish.await()
                    handoff.publish(accepted)
                    handoff.completeProducer()
                    published.countDown()
                }

                handoff.cancel()
                mayPublish.countDown()
                producer.get(2, TimeUnit.SECONDS)

                assertNull(handoff.claim())
                assertFalse(accepted.isLiveForIo(), "late accept result must be retired")
            } finally {
                executor.shutdownNow()
                if (peer.isLiveForIo()) RealSocketManager.close(peer)
            }
        }
    }

    private fun withSocketPair(
        action: (
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
            FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open, FdOwnership.Owned>,
        ) -> Unit,
    ) {
        NativeArena.ofConfined().use { arena ->
            val pair = arena.allocate(8)
            LinuxNative.networking.socketpair(1, 1 or NativeConstants.SOCK_CLOEXEC, 0, pair).getOrThrow("socketpair")
            val accepted = FileDescriptor.adopt(pair.readInt(0), FileDescriptorRole.UnixSocket)
            val peer = FileDescriptor.adopt(pair.readInt(4), FileDescriptorRole.UnixSocket)
            try {
                action(accepted, peer)
            } finally {
                if (accepted.isLiveForIo()) RealSocketManager.close(accepted)
                if (peer.isLiveForIo()) RealSocketManager.close(peer)
            }
        }
    }
}
