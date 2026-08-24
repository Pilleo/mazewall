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
    private val workerClasspath: String = "",
    /** Extra -D args for spawned worker JVMs (e.g. injectable idle deadline in tests). */
    private val workerExtraJvmArgs: List<String> = emptyList(),
) : AutoCloseable {
    init {
        require(poolSize >= 1) { "poolSize must be >= 1" }
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
        var returnedToPool = false
        return try {
            val id = nextId.getAndIncrement()
            slot.channel.send(PortalFrame(PortalKind.REQUEST, id, methodId, payload, fds.size), fds)
            val (reply, extra) = slot.channel.receive(callTimeoutMs)
            extra.forEach { sockets.close(it) }
            check(reply.requestId == id) { "request id mismatch" }
            if (reply.kind == PortalKind.ERROR) {
                returnToPoolOrDestroy(slot)
                returnedToPool = true
                throw PortalCallException(reply.payload.toString(StandardCharsets.UTF_8))
            }
            check(reply.kind == PortalKind.RESPONSE) { "unexpected kind ${reply.kind}" }
            returnToPoolOrDestroy(slot)
            returnedToPool = true
            reply.payload
        } catch (e: PortalCallException) {
            if (!returnedToPool) {
                recycleDeadWorker(slot)
            }
            throw e
        } catch (e: Exception) {
            if (!returnedToPool) {
                recycleDeadWorker(slot)
            }
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
            idle.put(slot)
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
        val ep = PrivateUnixEndpoint.create(launcher, "mazewall-portal-", "portal.sock")
        val listen = sockets.createUnixServer(ep.path)
        val spec =
            JvmChildSpec(
                mainClass = "io.mazewall.portal.worker.PortalWorkerMain",
                mainArgs = listOf(ep.path),
                maxHeap = "64m",
                javaAgents = JavaAgentSelection.None,
                classpath = resolveWorkerClasspath(),
                extraJvmArgs = workerExtraJvmArgs,
            )
        val proc = JvmChildProcess.start(launcher, spec)
        // CI diagnosability: a worker that dies before connecting must surface its
        // exit code immediately instead of leaving accept() blocked until the
        // test-level timeout. Also echo the command line once per spawn.
        proc.onExit().thenAccept {
            if (it.exitValue() != 0) {
                System.err.println(
                    "[PORTAL-WORKER-EXIT] code=${it.exitValue()} cmd=${JvmChildProcess.commandLine(spec).joinToString(" ")}",
                )
            }
        }.exceptionally { System.err.println("[PORTAL-WORKER-EXIT] onExit failed: ${it.message}"); null }
        val pump =
            JvmChildProcess.startStdoutPump(
                proc,
                "MAZEWALL_PORTAL_WORKER_READY",
                { line ->
                    System.err.println("[PORTAL-WORKER] $line")
                    runCatching {
                        java.io.File("/tmp/portalworker_err.log").appendText(line + "\n")
                    }
                },
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
        spawned.incrementAndGet()
        return register(WorkerSlot(proc, channel, ep, listen)) ?: run {
            // Closed between accept and registration: tear down this worker immediately.
            proc.destroyForcibly()
            ep.close()
            sockets.close(listen)
            error("broker closed during worker spawn")
        }
    }

    private fun recycleDeadWorker(dead: WorkerSlot) {
        // Pre-spawn BEFORE teardown: replacement boot overlaps destruction of the corpse,
        // bounding recycle latency instead of serializing a full JVM start (issue-011652).
        val fresh = try {
            spawnWorker()
        } finally {
            destroySlot(dead)
        }
        idle.put(fresh)
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

    private class WorkerSlot(
        val process: Process,
        val channel: PortalChannel,
        val endpoint: PrivateUnixEndpoint,
        val server: FileDescriptor<FileDescriptorRole.UnixSocket, FdState.Open>,
    )
}
