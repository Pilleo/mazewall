package io.mazewall.tierE.daemon

import io.mazewall.tierE.ffi.PosixFfi
import io.mazewall.tierE.shim.LibbpfShim
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Tier E WP-04 control-plane daemon (Kotlin implementation).
 *
 * Wire contract: strict request→reply.
 *   ATTACH <pid> <uprobe|usdt> <marker.so> | DETACH | STATUS | SHUTDOWN
 *
 * Trust model per design doc §5:
 * - peercred uid==0 (SO_PEERCRED)
 * - one session at a time; additional controllers receive ERR BUSY immediately
 * - marker hygiene via [SessionEngine.DefaultMarkerVerifier]
 * - one fresh BPF object per epoch, no pins, DEAD is terminal
 */
public class TierEDaemon(
    private val socketPath: String,
    private val shimPath: String,
    private val bpfObjectPath: String,
    private val printEvents: Boolean = false,
) {
    private val posix = PosixFfi()
    private val shim = LibbpfShim(shimPath)
    private val epochCounter = AtomicLong(0)
    private val sessionActive = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var listenFd: Int = -1

    public fun serve() {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { Files.deleteIfExists(Path.of(socketPath)) }
            System.out.flush()
        })
        if (!runningAsRoot()) {
            System.err.println("[wp04kt] refusing: requires initial-userns root")
            exitProcess(1)
        }
        try {
            serveInternal()
        } catch (t: Throwable) {
            System.err.println("[wp04kt] fatal: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
            exitProcess(1)
        }
    }

    private fun serveInternal() {
        Files.createDirectories(Path.of(socketPath).parent)
        Files.deleteIfExists(Path.of(socketPath))
        listenFd = posix.listenUnix(socketPath)
        check(listenFd >= 0) { "listen failed rc=$listenFd" }
        try {
            Files.setPosixFilePermissions(
                Path.of(socketPath),
                PosixFilePermissions.fromString("rw-rw----"),
            )
        } catch (_: Exception) { }
        System.err.println("[wp04kt] listening on $socketPath")

        while (!stopRequested.get()) {
            val cfd = posix.accept(listenFd)
            if (cfd < 0 || stopRequested.get()) break

            // Bound recv blocking so an idle client can't hold the slot forever.
            posix.setRecvTimeout(cfd, RECV_TIMEOUT_MS)

            val cred = posix.peerCredentials(cfd)
            when (val decision = decideAccept(sessionActive.get(), cred)) {
                is AcceptDecision.Accept -> {
                    val epoch = epochCounter.incrementAndGet()
                    thread(name = "tier-e-session-$epoch", isDaemon = true) {
                        try {
                            handleSession(epoch, cfd)
                        } finally {
                            sessionActive.set(false)
                        }
                    }
                }
                is AcceptDecision.Reject -> {
                    posix.sendAll(cfd, decision.reply.toByteArray())
                    posix.close(cfd)
                }
            }
        }

        Files.deleteIfExists(Path.of(socketPath))
        System.err.println("[wp04kt] clean exit")
    }

    private fun handleSession(epoch: Long, cfd: Int) {
        val shimRef = shim
        val engine = SessionEngine(epoch, shimRef, bpfObjectPath).also { eng ->
            eng.verifier = SessionEngine.defaultMarkerVerifier { pid ->
                runCatching {
                    Files.newBufferedReader(Path.of("/proc", pid.toString(), "maps")).lineSequence()
                }.getOrNull()
            }
        }
        var boundHandle = -1L
        var eventCount = 0

        val stopRing = AtomicBoolean(false)
        var ringThread: Thread? = null

        fun startRingPoller(handle: Long) {
            try {
                boundHandle = handle
                val rbHandle = shimRef.ringNew(handle)
                stopRing.set(false)
                ringThread = thread(name = "tier-e-ring-$epoch", isDaemon = false) {
                    System.err.println("[dbg-ring] started epoch=$epoch")
                    while (!stopRequested.get() && !stopRing.get()) {
                        try {
                            shimRef.ringPoll(rbHandle, 50)
                        } catch (t: Throwable) {
                            System.err.println("[wp04kt] ring error: $t")
                            break
                        }
                        Thread.sleep(5)
                    }
                    shimRef.ringDestroy(rbHandle)
                }
            } catch (t: Throwable) {
                System.err.println(
                    "[wp04kt] ring setup failed (session continues): ${t::class.simpleName}: ${t.message}",
                )
            }
        }

        fun teardownRing() {
            stopRing.set(true)
            ringThread?.join(200)
            ringThread = null
        }

        fun reply(text: String) {
            if (!posix.sendAll(cfd, text.toByteArray())) {
                throw java.io.IOException("send failed")
            }
        }

        System.err.println("[dbg-kt] epoch=$epoch accepted")

        val buffer = ByteArray(512)
        var buffered = 0

        try {
            while (!stopRequested.get()) {
                val n = posix.recv(cfd, buffer, buffered, buffer.size - buffered)
                if (n <= 0) break
                buffered += n

                var scanStart = 0
                while (scanStart < buffered) {
                    var nl = -1
                    for (idx in scanStart until buffered) {
                        if (buffer[idx] == '\n'.code.toByte()) { nl = idx; break }
                    }
                    if (nl < 0) break
                    val line = String(buffer, scanStart, nl - scanStart).trim()
                    scanStart = nl + 1

                    when (val parsed = parseControlCommand(line)) {
                        is Either.Right -> reply(parsed.value.render())
                        is Either.Left -> when (val cmd = parsed.value) {
                            is ControlCommand.Attach -> try {
                                val r = engine.onAttach(cmd)
                                reply(r.render())
                                if (r.ok) startRingPoller(engine.activeHandle() ?: return)
                            } catch (t: Throwable) {
                                System.err.println(
                                    "[wp04kt] attach crash: ${t::class.simpleName}: ${t.message}",
                                )
                                t.printStackTrace()
                                engine.close()
                                reply(ControlReply.err("ATTACH_CRASH ${t.message}").render())
                            }
                            ControlCommand.Detach -> reply(engine.onDetach().render())
                            ControlCommand.Status -> reply("OK ${engine.statusText()}\n")
                            ControlCommand.Shutdown -> {
                                reply(ControlReply.ok("BYE").render())
                                stopRequested.set(true)
                                // Close BOTH fds: listener wakes accept loop,
                                // session fd wakes recv so finally block runs
                                // (DEAD + NOISE_PROFILE printed before exit).
                                posix.close(listenFd)
                                posix.close(cfd)
                            }
                        }
                    }
                    if (stopRequested.get()) break
                }

                if (scanStart > 0) {
                    System.arraycopy(buffer, scanStart, buffer, 0, buffered - scanStart)
                    buffered -= scanStart
                }
                if (buffered == buffer.size) {
                    reply(ControlReply.err("COMMAND_TOO_LONG").render())
                    break
                }
            }
        } catch (_: java.io.IOException) {
            // peer vanished mid-command: session dies (invariant 7)
        } finally {
            teardownRing()
            engine.close()
            posix.close(cfd)
            val dropped = if (boundHandle >= 0) runCatching {
                shim.droppedTotal(boundHandle).toLong()
            }.getOrDefault(-1L) else -1L
            if (boundHandle >= 0) {
                val counts = runCatching { shim.unknownCounts(boundHandle) }.getOrNull()
                if (counts != null) {
                    val top = counts.withIndex()
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .take(10)
                    System.err.println(
                        "[wp04kt] epoch=$epoch NOISE_PROFILE " +
                            top.joinToString(" ") { "${it.index}:${it.value}" },
                    )
                }
            }
            System.err.println(
                "[wp04kt] epoch=$epoch DEAD events=$eventCount dropped=$dropped",
            )
        }

        // Suppress unused warnings for variables captured by closures above.
        @Suppress("UNUSED_EXPRESSION") printEvents
        @Suppress("UNUSED_EXPRESSION") shimRef
    }

    private companion object {
        const val RECV_TIMEOUT_MS = 30_000
    }

    private fun runningAsRoot(): Boolean =
        runCatching {
            Files.readAllLines(Path.of("/proc/self/status")).any {
                it.startsWith("Uid:") && it.split(Regex("\\s+")).getOrNull(1) == "0"
            }
        }.getOrDefault(false)
}

public fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--probe-stdin") {
        ProbeMain.probeStdin(args)
        return
    }
    if (args.isNotEmpty() && args[0] == "--probe-cmdfile") {
        ProbeMain.probeCmdfile(args)
        return
    }
    if (args.isNotEmpty() && args[0] == "--probe") {
        ProbeMain.probeSingle(args)
        return
    }

    var sock = "/run/mazewall/wp04kt.sock"
    var shimPath = "build/libtier_e_bpf.so"
    var objPath = "build/context_probe.bpf.o"
    var verbose = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--sock" -> { require(i + 1 < args.size); sock = args[i + 1]; i += 2 }
            "--shim" -> { require(i + 1 < args.size); shimPath = args[i + 1]; i += 2 }
            "--bpf" -> { require(i + 1 < args.size); objPath = args[i + 1]; i += 2 }
            "--verbose" -> { verbose = true; i += 1 }
            else -> {
                System.err.println("usage: wp04kt [--sock p] [--shim so] [--bpf o] [--verbose]")
                exitProcess(2)
            }
        }
    }
    TierEDaemon(sock, shimPath, objPath, printEvents = verbose).serve()
}
