package io.mazewall.profiler.api

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.readByte
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeMemory
import io.mazewall.platform.syscall.Syscall
import io.mazewall.profiler.internal.ProfilerDaemonManager
import io.mazewall.profiler.internal.ProfilerTraceListener
import io.mazewall.profiler.internal.engine.PASS_THROUGH_COMMAND_BYTE
import io.mazewall.profiler.internal.engine.RealProfilerInstaller
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@NeedsFreshJvm
class ProfilerPassThroughIntegrationTest : BaseIntegrationTest() {
    @Test
    fun `closing during capture keeps the real syscall blocked until ACK`() {
        val native = LinuxNative.snapshot()
        val manager = ProfilerDaemonManager(native)
        val captureStarted = CountDownLatch(1)
        val allowCapture = CountDownLatch(1)
        val passThroughWritten = CountDownLatch(1)
        val firstConnect = CountDownLatch(1)
        val allowSecondConnect = CountDownLatch(1)
        val listener = CompletableFuture<ProfilerTraceListener>()
        val logs = CopyOnWriteArrayList<TraceEvent>()
        val executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory())
        val listenerNative = observePassThroughWrite(native, passThroughWritten)
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        try {
            ServerSocket(0, 2, loopback).use { server ->
                val destination = InetSocketAddress(loopback, server.localPort)
                val daemon = manager.getOrSpawnSharedDaemon()
                val policy = WorkerPolicyBuilder
                    .denyList(RuntimeProfile.HOTSPOT_JIT)
                    .block(Syscall.CONNECT)
                    .build()
                    .definition
                val tracee = executor.submit {
                    val traceeThread = Thread.currentThread()
                    Socket(Proxy.NO_PROXY).use { first ->
                        Socket(Proxy.NO_PROXY).use { second ->
                            RealProfilerInstaller(native).installProfilingFilterForThread(
                                daemon.socketPath,
                                policy,
                                logs,
                                null,
                                mutableMapOf(),
                            ) { socket, _, _, _, reportStartup ->
                                val reader = ProfilerTraceListener(socket, logs, mutableMapOf(), mutableMapOf(), {
                                    captureStarted.countDown()
                                    check(allowCapture.await(10, TimeUnit.SECONDS)) { "Capture gate timed out" }
                                    traceeThread
                                }, listenerNative)
                                reader.start(reportStartup)
                                listener.complete(reader)
                            }
                            first.connect(destination, 5_000)
                            firstConnect.countDown()
                            check(allowSecondConnect.await(10, TimeUnit.SECONDS))
                            second.connect(destination, 5_000)
                        }
                    }
                }
                val reader = listener.get(10, TimeUnit.SECONDS)
                assertTrue(captureStarted.await(10, TimeUnit.SECONDS), "The fixture must reach stack capture with an event in flight")
                val closing = executor.submit { reader.close() }
                assertTrue(passThroughWritten.await(2, TimeUnit.SECONDS), "The listener must send pass-through while capture is blocked")
                assertFalse(firstConnect.await(500, TimeUnit.MILLISECONDS), "CONNECT resumed while its event still awaited ACK")
                allowCapture.countDown()
                closing.get(5, TimeUnit.SECONDS)
                assertTrue(reader.drainComplete, "The reader must receive a normal terminal record")
                allowSecondConnect.countDown()
                tracee.get(5, TimeUnit.SECONDS)
                assertEquals(listOf("CONNECT"), logs.map { it.syscallName })
            }
        } finally {
            allowCapture.countDown()
            allowSecondConnect.countDown()
            try {
                listener.getNow(null)?.close()
            } finally {
                executor.shutdownNow()
                manager.stop()
            }
        }
    }

    private fun observePassThroughWrite(
        native: NativeEngine,
        written: CountDownLatch,
    ): NativeEngine =
        object : NativeEngine by native {
            override val memory = object : NativeMemory by native.memory {
                override fun write(
                    fd: FileDescriptor<*, FdState.Open, FdOwnership>,
                    buf: ManagedSegment,
                    count: Long,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val result = native.memory.write(fd, buf, count)
                    if (count == 1L && result is LinuxNative.SyscallResult.Success && result.value == 1L && buf.readByte(0) == PASS_THROUGH_COMMAND_BYTE) {
                        written.countDown()
                    }
                    return result
                }
            }
        }
}
