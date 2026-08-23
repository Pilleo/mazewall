package io.mazewall.portal

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.core.PrivateUnixEndpoint
import io.mazewall.core.ProcessLauncher
import io.mazewall.core.RealProcessLauncher
import io.mazewall.core.RealSocketManager
import io.mazewall.core.SocketManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns a pool of portal worker JVMs and Unix RPC sockets.
 * Spawn workers before the broker installs process-wide seccomp.
 */
public class ProcessBroker(
    private val poolSize: Int = 1,
    private val callTimeoutMs: Long = 30_000L,
    private val sockets: SocketManager = RealSocketManager,
    private val launcher: ProcessLauncher = RealProcessLauncher,
) : AutoCloseable {
    init {
        require(poolSize >= 1) { "poolSize must be >= 1" }
    }

    private val nextId = AtomicInteger(1)
    private val idle = ArrayBlockingQueue<WorkerSlot>(poolSize)
    private val started = AtomicInteger(0)

    public fun start() {
        check(started.compareAndSet(0, 1)) { "broker already started" }
        repeat(poolSize) {
            idle.put(spawnWorker())
        }
    }

    public fun echo(text: String): String {
        val payload = call(PortalMethods.ECHO, text.toByteArray(StandardCharsets.UTF_8), emptyList())
        return payload.toString(StandardCharsets.UTF_8)
    }

    public fun invoke(
        methodId: Int,
        payload: ByteArray,
        vararg granted: Capability.ReadFd,
    ): ByteArray = call(methodId, payload, granted.map { it.fd })

    public fun checksum(fd: Capability.ReadFd): Int {
        val payload = call(PortalMethods.CHECKSUM, ByteArray(0), listOf(fd.fd))
        require(payload.size == 4) { "checksum must be 4 bytes" }
        return ((payload[0].toInt() and 0xff) shl 24) or
            ((payload[1].toInt() and 0xff) shl 16) or
            ((payload[2].toInt() and 0xff) shl 8) or
            (payload[3].toInt() and 0xff)
    }

    public fun openReadOnly(rootDir: java.nio.file.Path, relative: String): Capability.ReadFd =
        openGrantedRead(rootDir, relative)

    internal fun sleep(millis: Int) {
        val buf = ByteArray(4)
        buf[0] = (millis ushr 24).toByte()
        buf[1] = (millis ushr 16).toByte()
        buf[2] = (millis ushr 8).toByte()
        buf[3] = millis.toByte()
        call(PortalMethods.SLEEP, buf, emptyList())
    }

    internal fun tryOpenHostPasswd() {
        call(PortalMethods.TRY_OPEN_HOST_PASSWD, ByteArray(0), emptyList())
    }

    internal fun crashIdleWorkerProcess() {
        val slot =
            idle.poll(callTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw PortalCallException("no idle worker to crash")
        slot.process.destroyForcibly()
        idle.put(slot)
    }

    internal fun call(
        methodId: Int,
        payload: ByteArray,
        fds: List<FileDescriptor<*, FdState.Open>>,
    ): ByteArray {
        check(started.get() == 1) { "broker not started" }
        val slot =
            idle.poll(callTimeoutMs, TimeUnit.MILLISECONDS)
                ?: throw PortalCallException("timed out waiting for an idle portal worker")
        return try {
            val id = nextId.getAndIncrement()
            slot.channel.send(PortalFrame(PortalKind.REQUEST, id, methodId, payload, fds.size), fds)
            val (reply, extra) = slot.channel.receive(callTimeoutMs)
            extra.forEach { sockets.close(it) }
            check(reply.requestId == id) { "request id mismatch" }
            if (reply.kind == PortalKind.ERROR) {
                throw PortalCallException(reply.payload.toString(StandardCharsets.UTF_8))
            }
            check(reply.kind == PortalKind.RESPONSE) { "unexpected kind ${reply.kind}" }
            idle.put(slot)
            reply.payload
        } catch (e: PortalCallException) {
            recycleDeadWorker(slot)
            throw e
        } catch (e: Exception) {
            recycleDeadWorker(slot)
            throw PortalCallException("portal RPC failed", e)
        }
    }

    override fun close() {
        val leftover = mutableListOf<WorkerSlot>()
        idle.drainTo(leftover)
        leftover.forEach { destroySlot(it) }
        started.set(0)
    }

    private fun spawnWorker(): WorkerSlot {
        val ep = PrivateUnixEndpoint.create(launcher, "mazewall-portal-", "portal.sock")
        val listen = sockets.createUnixServer(ep.path)
        val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs = listOf(ep.path),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.None,
            )
        val proc = JvmChildProcess.start(launcher, spec)
        val pump =
            JvmChildProcess.startStdoutPump(
                proc,
                "MAZEWALL_PORTAL_WORKER_READY",
                { line -> System.err.println("[PORTAL-WORKER] $line") },
                "portal-worker-stdout",
            )
        val peer = sockets.accept(listen)
        val channel = PortalChannel(peer, sockets)
        if (!JvmChildProcess.awaitReady(pump, 30)) {
            sockets.close(peer)
            sockets.close(listen)
            proc.destroyForcibly()
            ep.close()
            error("portal worker failed to become ready")
        }
        return WorkerSlot(proc, channel, ep, listen)
    }

    private fun recycleDeadWorker(dead: WorkerSlot) {
        destroySlot(dead)
        idle.put(spawnWorker())
    }

    private fun destroySlot(slot: WorkerSlot) {
        try {
            slot.channel.close()
        } catch (_: Exception) {
        }
        try {
            sockets.close(slot.server)
        } catch (_: Exception) {
        }
        slot.process.destroyForcibly()
        try {
            slot.endpoint.close()
        } catch (_: Exception) {
        }
    }

    private class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    )
}
