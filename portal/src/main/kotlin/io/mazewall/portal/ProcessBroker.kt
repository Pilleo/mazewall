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
import io.mazewall.core.close
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a pool of portal worker JVMs and Unix RPC sockets.
 * Spawn workers before the broker installs process-wide seccomp.
 */
public class ProcessBroker(
    private val poolSize: Int = 1,
    private val callTimeoutMs: Long = 30_000L,
    private val sockets: SocketManager = RealSocketManager,
    private val launcher: ProcessLauncher = RealProcessLauncher,
    private val workerClasspath: String = "",
    private val workerMaxHeap: String = "64m",
    private val startupTimeoutMillis: Long = 30_000,
    /** Extra -D args for spawned worker JVMs (e.g. injectable idle deadline in tests). */
    private val workerExtraJvmArgs: List<String> = emptyList(),
) : PortalClient, AutoCloseable {
    init {
        require(poolSize >= 1) { "poolSize must be >= 1" }
        require(workerMaxHeap.isNotBlank()) { "portal worker max heap is required" }
        require(startupTimeoutMillis >= 1) { "portal worker startup timeout must be positive" }
    }

    public companion object {
        public const val WORKER_CLASSPATH_PROPERTY: String = "io.mazewall.portal.worker.classpath"
    }

    private val nextId = AtomicInteger(1)
    private val idle = ArrayBlockingQueue<WorkerSlot>(poolSize)
    private val started = AtomicInteger(0)
    private val spawned = AtomicInteger(0)

    /**
     * Every live slot (idle AND checked-out), so [close] can destroy workers that are
     * mid-call instead of orphaning them (issue-20260824-011652).
     */
    private val trackedSlots: MutableSet<WorkerSlot> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    public fun start() {
        check(started.compareAndSet(0, 1)) { "broker already started" }
        repeat(poolSize) {
            register(spawnWorker())?.let { idle.put(it) }
        }
    }

    /** Test/diagnostics view of live (tracked) worker slots. */
    internal fun trackedWorkers(): Int = trackedSlots.size
    internal fun idleSize(): Int = idle.size

    public fun echo(text: String): String {
        val payload = call(PortalMethods.ECHO, text.toByteArray(StandardCharsets.UTF_8), emptyList())
        return payload.toString(StandardCharsets.UTF_8)
    }

    override fun invoke(
        methodId: Int,
        payload: ByteArray,
        vararg granted: Capability.ReadFd,
    ): ByteArray {
        val transferred = mutableListOf<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>()
        try {
            granted.forEach { transferred += it.transferForPortalCall() }
            return call(methodId, payload, transferred)
        } catch (failure: Throwable) {
            transferred.forEach { it.close() }
            throw failure
        }
    }

    public fun checksum(fd: Capability.ReadFd): Int {
        val payload = invoke(PortalMethods.CHECKSUM, ByteArray(0), fd)
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

    internal fun spawnedWorkers(): Int = spawned.get()

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
            val replyFuture = slot.submit(id, PortalFrame(PortalKind.REQUEST, id, methodId, payload, fds.size), fds)
            // SCM_RIGHTS has copied the grant into the worker's receive queue; the
            // broker must not retain the original capability after transfer.
            fds.forEach { it.close() }
            // The connection has one reader and serialized writes, so it is available
            // again while this caller waits only for its own request id.
            returnToPoolOrDestroy(slot)
            val reply = replyFuture.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            if (reply.kind == PortalKind.ERROR) {
                throw PortalCallException(reply.payload.toString(StandardCharsets.UTF_8))
            }
            check(reply.kind == PortalKind.RESPONSE) { "unexpected kind ${reply.kind}" }
            reply.payload
        } catch (e: PortalCallException) {
            throw e
        } catch (e: Exception) {
            failConnection(slot, e)
            throw PortalCallException("portal RPC failed", e)
        }
    }

    override fun close() {
        closed.set(true)
        started.set(0)
        val leftover = mutableListOf<WorkerSlot>()
        idle.drainTo(leftover)
        // Destroy checked-out slots too: a worker mid-call belongs to a broker that is gone.
        synchronized(trackedSlots) { leftover.addAll(trackedSlots) }
        leftover.forEach { destroySlot(it) }
    }

    /** Pool the slot unless the broker closed meanwhile; destroy orphans either way. */
    private fun returnToPoolOrDestroy(slot: WorkerSlot) {
        if (closed.get()) {
            destroySlot(slot)
        } else {
            slot.offerToPool { idle.offer(slot) }
        }
    }

    private fun register(slot: WorkerSlot): WorkerSlot? {
        trackedSlots.add(slot)
        return if (closed.get()) {
            destroySlot(slot)
            null
        } else {
            slot
        }
    }

    private fun resolveWorkerClasspath(): String {
        val cp = workerClasspath.ifBlank { System.getProperty(WORKER_CLASSPATH_PROPERTY).orEmpty() }
        require(cp.isNotBlank()) {
            "portal worker classpath is required; set $WORKER_CLASSPATH_PROPERTY or pass workerClasspath"
        }
        return cp
    }

    private fun spawnWorker(): WorkerSlot {
        val startupDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMillis)
        val ep = PrivateUnixEndpoint.create(launcher, "mazewall-portal-", "portal.sock")
        val listen = sockets.createUnixServer(ep.path)
        val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs = listOf(ep.path),
                maxHeap = workerMaxHeap,
                javaAgents = JavaAgentSelection.None,
                classpath = resolveWorkerClasspath(),
                extraJvmArgs = workerExtraJvmArgs,
            )
        val proc = JvmChildProcess.start(launcher, spec)
        // CI diagnosability + hang prevention: a worker that dies before connecting
        // must (a) surface its exit code and command line, and (b) break the
        // blocking accept() below by closing the listener, so the cleanup path runs
        // instead of hanging until an external timeout.
        proc.onExit().thenAccept {
            if (it.exitValue() != 0) {
                System.err.println(
                    "[PORTAL-WORKER-EXIT] code=${it.exitValue()} cmd=${JvmChildProcess.commandLine(spec).joinToString(" ")}",
                )
            }
            // Wake order matters: close() alone does not interrupt a thread blocked
            // in accept4 on the same fd. A dummy connect forces the accept to return
            // (its peer is discarded by the !proc.isAlive guard below); closing the
            // listener afterwards prevents any further accepts.
            runCatching {
                java.nio.channels.SocketChannel.open(
                    java.net.UnixDomainSocketAddress.of(ep.path),
                ).use { ch -> ch.write(java.nio.ByteBuffer.wrap(ByteArray(1))) }
            }
            runCatching { sockets.close(listen) }
        }.exceptionally { System.err.println("[PORTAL-WORKER-EXIT] onExit failed: ${it.message}"); null }
        val pump =
            JvmChildProcess.startStdoutPump(
                proc,
                "MAZEWALL_PORTAL_WORKER_READY",
                { line ->
                    System.err.println("[PORTAL-WORKER] $line")
                },
                "portal-worker-stdout",
            )
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(startupDeadlineNanos - System.nanoTime()).coerceAtLeast(1)
        val accepted = CompletableFuture.supplyAsync { sockets.accept(listen) }
        val peer = try {
            accepted.get(remainingMillis, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            sockets.close(listen)
            accepted.whenComplete { latePeer, _ -> if (latePeer != null) sockets.close(latePeer) }
            proc.destroyForcibly()
            ep.close()
            error("portal worker timed out before connecting")
        } catch (e: Exception) {
            // Listener closed by the death watcher (or teardown racing accept).
            runCatching { proc.destroyForcibly() }
            ep.close()
            throw IllegalStateException("portal worker died before connecting: ${e.message}", e)
        }
        if (!proc.isAlive) {
            sockets.close(peer); sockets.close(listen); ep.close()
            proc.destroyForcibly()
            error("portal worker exited during handshake")
        }
        val channel = PortalChannel(peer, sockets)
        val readyMillis = TimeUnit.NANOSECONDS.toMillis(startupDeadlineNanos - System.nanoTime())
        if (readyMillis <= 0 || !JvmChildProcess.awaitReadyMillis(pump, readyMillis)) {
            sockets.close(peer)
            sockets.close(listen)
            proc.destroyForcibly()
            ep.close()
            error("portal worker failed to become ready")
        }
        spawned.incrementAndGet()
        val slot = WorkerSlot(proc, channel, ep, listen, sockets)
        val registered = register(slot) ?: run {
            // Closed between accept and registration: tear down this worker immediately.
            proc.destroyForcibly()
            ep.close()
            sockets.close(listen)
            error("broker closed during worker spawn")
        }
        registered.startReader()
        return registered
    }

    private fun failConnection(dead: WorkerSlot, cause: Throwable) {
        if (!dead.fail(cause)) return
        idle.remove(dead)
        if (closed.get()) {
            destroySlot(dead)
            return
        }
        // Pre-spawn BEFORE teardown: replacement boot overlaps destruction of the corpse,
        // bounding recycle latency instead of serializing a full JVM start (issue-011652).
        val fresh = try {
            spawnWorker()
        } finally {
            destroySlot(dead)
        }
        if (!closed.get()) idle.put(fresh) else destroySlot(fresh)
    }

    private fun destroySlot(slot: WorkerSlot) {
        trackedSlots.remove(slot)
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

    private inner class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
        private val sockets: SocketManager,
    ) {
        private val pending = ConcurrentHashMap<Int, CompletableFuture<PortalFrame>>()
        private val writeLock = Any()
        private val failed = AtomicBoolean(false)
        private val stateLock = Any()

        fun fail(cause: Throwable): Boolean {
            synchronized(stateLock) {
                if (!failed.compareAndSet(false, true)) return false
                pending.values.forEach { it.completeExceptionally(cause) }
                pending.clear()
                return true
            }
        }

        fun offerToPool(offer: () -> Boolean): Boolean = synchronized(stateLock) {
            !failed.get() && offer()
        }

        fun startReader() {
            Thread.ofPlatform().name("portal-response-reader").start {
                while (true) {
                    try {
                        val (frame, fds) = channel.receive()
                        fds.forEach { sockets.close(it) }
                        pending.remove(frame.requestId)?.complete(frame)
                    } catch (_: PortalReadTimeoutException) {
                        continue
                    } catch (failure: Exception) {
                        failConnection(this@WorkerSlot, failure)
                        break
                    }
                }
            }
        }

        fun submit(id: Int, frame: PortalFrame, fds: List<FileDescriptor<*, FdState.Open>>): CompletableFuture<PortalFrame> {
            synchronized(stateLock) {
                check(!failed.get()) { "portal worker connection is unavailable" }
                val reply = CompletableFuture<PortalFrame>()
                check(pending.putIfAbsent(id, reply) == null)
                try {
                    synchronized(writeLock) { channel.send(frame, fds) }
                } catch (failure: Exception) {
                    pending.remove(id)
                    reply.completeExceptionally(failure)
                }
                return reply
            }
        }
    }
}
