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
 * Wire contract identical to the C protocol oracle:
 *   ATTACH <pid> <uprobe|usdt> <marker.so> | DETACH | STATUS | SHUTDOWN
 * Trust model per design doc §5: peercred uid==0, ONE session at a time
 * (additional controllers receive ERR BUSY immediately — the accept loop is
 * never blocked by an active session), marker hygiene via
 * [SessionEngine.DefaultMarkerVerifier], one fresh BPF object per epoch,
 * no pins, DEAD is terminal.
 */
public class TierEDaemon(
    private val socketPath: String,
    private val shimPath: String,
    private val bpfObjectPath: String,
    private val ringDataLength: Long = 1L shl 20,
    private val printEvents: Boolean = false,
) {
    private val posix = PosixFfi()
    private val shim = LibbpfShim(shimPath)
    private val epochCounter = AtomicLong(0)
    private val sessionActive = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var listenFd: Int = -1
    @Volatile private var eventsSeen: ULong = 0UL

    public fun serve() {
        Runtime.getRuntime().addShutdownHook(Thread {
            // Best-effort cleanup: unlink socket, flush stdout.
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
        } catch (_: Exception) {
        }
        System.err.println("[wp04kt] listening on $socketPath")

        while (!stopRequested.get()) {
            val cfd = posix.accept(listenFd)
            System.err.println("[dbg-kt] accept returned cfd=$cfd stop=${stopRequested.get()}")
            if (cfd < 0 || stopRequested.get()) break
            if (!sessionActive.compareAndSet(false, true)) {
                posix.sendAll(cfd, ControlReply.err("BUSY").render().toByteArray())
                // Graceful reject: drain the peer's pending line so its write
                // never races our close (RST would hide the ERR BUSY reply).
                val drain = ByteArray(256)
                runCatching { posix.recv(cfd, drain) }
                posix.close(cfd)
                continue
            }
            val cred = posix.peerCredentials(cfd)
            if (cred == null || cred.uid != 0) {
                posix.sendAll(cfd, "ERR PEER_UID ${cred?.uid ?: -1}\n".toByteArray())
                val drain = ByteArray(64)
                runCatching { posix.recv(cfd, drain) }
                posix.close(cfd)
                continue
            }
            val epoch = epochCounter.incrementAndGet()
            thread(name = "tier-e-session-$epoch", isDaemon = true) {
                try {
                    sessionLoop(epoch, cfd)
                } finally {
                    sessionActive.set(false)
                }
            }
        }
        Files.deleteIfExists(Path.of(socketPath))
        System.err.println("[wp04kt] clean exit")
    }

    /** One client connection = one session epoch. Runs on its own thread. */
    private fun sessionLoop(epoch: Long, cfd: Int) {
        val shimRef = shim
        val engine = SessionEngine(epoch, shimRef, bpfObjectPath).also { eng ->
            eng.verifier = SessionEngine.defaultMarkerVerifier { pid ->
                runCatching {
                    Files.newBufferedReader(Path.of("/proc", pid.toString(), "maps")).lineSequence()
                }.getOrNull()
            }
        }
        var ringThread: Thread? = null
        var boundHandle: Long = -1L
        var events = 0UL
        val stopRing = AtomicBoolean(false) // per-session, NOT shared across epochs

        fun startRingPoller(handle: Long) {
            try {
                val rbHandle = shimRef.ringNew(handle)
                stopRing.set(false)
                ringThread?.join(100)
                ringThread = thread(name = "tier-e-ring-$epoch", isDaemon = false) {
                    System.err.println("[dbg-ring] thread started epoch=$epoch")
                    while (!stopRequested.get() && !stopRing.get()) {
                        try {
                            shimRef.ringPoll(rbHandle, 50)
                        } catch (t: Throwable) {
                            System.err.println("[wp04kt] ring poll error: $t")
                            break
                        }
                        Thread.sleep(5)
                    }
                    shimRef.ringDestroy(rbHandle)
                    System.err.println("[dbg-ring] thread ended")
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

        System.err.println("[dbg-kt] session thread entered epoch=$epoch")
        System.err.println("[wp04kt] epoch=$epoch accepted")

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
                        if (buffer[idx] == '\n'.code.toByte()) {
                            nl = idx
                            break
                        }
                    }
                    if (nl < 0) break
                    val line = String(buffer, scanStart, nl - scanStart).trim()
                    scanStart = nl + 1

                    when (val parsed = parseControlCommand(line)) {
                        is Either.Right -> {
                            if (!posix.sendAll(cfd, parsed.value.render().toByteArray())) {
                                throw java.io.IOException("send failed")
                            }
                        }
                        is Either.Left -> when (val cmd = parsed.value) {
                            is ControlCommand.Attach -> try {
                                val r = engine.onAttach(cmd)
                                if (!posix.sendAll(cfd, r.render().toByteArray())) {
                                    throw java.io.IOException("send failed")
                                }
                                if (r.ok) {
                                    val h = engine.activeHandle()
                                    if (h != null) startRingPoller(h)
                                }
                            } catch (t: Throwable) {
                                // Fail-closed diagnostics: control-plane defects
                                // surface to the operator, never kill the daemon.
                                System.err.println(
                                    "[wp04kt] attach crash: ${t::class.simpleName}: ${t.message}",
                                )
                                t.printStackTrace()
                                engine.close()
                                posix.sendAll(
                                    cfd,
                                    ControlReply
                                        .err(
                                            "ATTACH_CRASH ${t::class.simpleName}: ${t.message}",
                                        )
                                        .render()
                                        .toByteArray(),
                                )
                            }
                            ControlCommand.Detach ->
                                if (!posix.sendAll(cfd, engine.onDetach().render().toByteArray())) {
                                    throw java.io.IOException("send failed")
                                }
                            ControlCommand.Status ->
                                if (!posix.sendAll(cfd, "OK ${engine.statusText()}\n".toByteArray())) {
                                    throw java.io.IOException("send failed")
                                }
                            ControlCommand.Shutdown -> {
                                posix.sendAll(cfd, ControlReply.ok("BYE").render().toByteArray())
                                stopRequested.set(true)
                                // Closing the listener would NOT wake a thread
                                // blocked in accept(); a local connect does.
                                runCatching {
                                    val w = posix.connectUnix(socketPath)
                                    if (w >= 0) posix.close(w)
                                }
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
                    posix.sendAll(cfd, ControlReply.err("COMMAND_TOO_LONG").render().toByteArray())
                    break
                }
            }
        } catch (_: java.io.IOException) {
            // peer vanished mid-command: session dies (invariant 7)
        } finally {
            teardownRing()
            engine.close()
            eventsSeen = events.toULong()
            posix.close(cfd)
            val dropped = if (boundHandle >= 0) runCatching {
                shim.droppedTotal(boundHandle).toLong()
            }.getOrDefault(-1L) else -1L
            System.err.println(
                "[wp04kt] epoch=$epoch DEAD (peer EOF) events=${eventsSeen} dropped=$dropped",
            )
        }
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
        // Suite harness mode: reads one command per stdin line, prints one
        // reply per line. Single connection for the process lifetime.
        val posix = PosixFfi()
        val fd = posix.connectUnix(args[1])
        if (fd < 0) {
            println("ERR CONNECT")
            exitProcess(1)
        }
        val buf = ByteArray(512)
        while (true) {
            val line = readLine() ?: break
            if (line.isBlank()) continue
            if (!posix.sendAll(fd, (line + "\n").toByteArray())) {
                println("ERR SEND")
                break
            }
            val n = posix.recv(fd, buf)
            if (n <= 0) {
                println("ERR RECV")
                break
            }
            println(String(buf, 0, n).trimEnd('\n'))
        }
        posix.close(fd)
        exitProcess(0)
    }
    if (args.isNotEmpty() && args[0] == "--probe-cmdfile") {
        // Suite harness mode: long-lived connection driven by a command file.
        //   args: --probe-cmdfile <sock> <cmdfile> <outfile>
        // Appends one reply per command line; immune to fd-inheritance races.
        // Connect retries for up to ~10 s: the daemon JVM may still be
        // booting when this probe starts.
        val posix = PosixFfi()
        var fd = -1
        var waited = 0
        while (fd < 0 && waited < 10_000) {
            fd = posix.connectUnix(args[1])
            if (fd < 0) {
                println("WAIT")
                System.out.flush()
                Thread.sleep(100)
                waited += 100
            }
        }
        if (fd < 0) {
            println("ERR CONNECT_TIMEOUT")
            exitProcess(1)
        }
        val cmdFile = Path.of(args[2])
        val outFile = Path.of(args[3])
        val buf = ByteArray(512)
        var consumed = 0
        while (true) {
            val lines = runCatching {
                if (Files.exists(cmdFile)) Files.readAllLines(cmdFile) else emptyList()
            }.getOrDefault(emptyList())
            while (consumed < lines.size) {
                val line = lines[consumed++].trim()
                if (line.isEmpty()) continue
                if (!posix.sendAll(fd, (line + "\n").toByteArray())) {
                    println("ERR SEND")
                    exitProcess(1)
                }
                val n = posix.recv(fd, buf)
                val reply = if (n <= 0) "ERR RECV" else String(buf, 0, n).trimEnd('\n')
                Files.writeString(
                    outFile,
                    reply + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
                if (reply.startsWith("ERR RECV") || reply == "OK BYE") exitProcess(if (reply == "OK BYE") 0 else 1)
            }
            Thread.sleep(25)
        }
    }
    if (args.isNotEmpty() && args[0] == "--probe") {
        // Harness helper: single command over AF_UNIX, prints reply, rc=OK?0:1.
        val posix = PosixFfi()
        val fd = posix.connectUnix(args[1])
        if (fd < 0) {
            System.err.println("ERR CONNECT")
            exitProcess(1)
        }
        val line = args.drop(2).joinToString(" ") + "\n"
        check(posix.sendAll(fd, line.toByteArray())) { "send failed" }
        val buf = ByteArray(512)
        val n = posix.recv(fd, buf)
        val reply = if (n <= 0) "ERR RECV" else String(buf, 0, n).trimEnd('\n')
        println(reply)
        posix.close(fd)
        exitProcess(if (reply.startsWith("OK")) 0 else 1)
    }
    var sock = "/run/mazewall/wp04kt.sock"
    var shimPath = "build/libtier_e_bpf.so"
    var objPath = "build/context_probe.bpf.o"
    var printEvents = false
    var i = 0 // Kotlin main(args) has NO program-name element (unlike C argv)
    while (i < args.size) {
        when (args[i]) {
            "--sock" -> {
                require(i + 1 < args.size); sock = args[i + 1]; i += 2
            }
            "--shim" -> {
                require(i + 1 < args.size); shimPath = args[i + 1]; i += 2
            }
            "--bpf" -> {
                require(i + 1 < args.size); objPath = args[i + 1]; i += 2
            }
            "--verbose" -> {
                printEvents = true; i += 1
            }
            else -> {
                System.err.println("usage: wp04kt [--sock p] [--shim so] [--bpf o] [--verbose]")
                exitProcess(2)
            }
        }
    }
    TierEDaemon(sock, shimPath, objPath, printEvents = printEvents).serve()
}
