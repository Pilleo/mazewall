package io.mazewall.tierE.daemon

import io.mazewall.tierE.ffi.PosixFfi
import io.mazewall.tierE.ringbuf.RingbufReader
import io.mazewall.tierE.shim.LibbpfShim
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Tier E WP-04 control-plane daemon (Kotlin implementation).
 *
 * Wire contract identical to the C protocol oracle:
 *   ATTACH <pid> <uprobe|usdt> <marker.so> | DETACH | STATUS | SHUTDOWN
 * Trust model per design doc §5: peercred uid==0, one session at a time,
 * marker hygiene via [SessionEngine.DefaultMarkerVerifier], one fresh BPF
 * object per epoch, no pins, DEAD is terminal.
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
    private var epoch = 0UL
    private var engine: SessionEngine? = null
    private var clientFd: Int = -1
    private var ringThread: Thread? = null
    private var ringReader: RingbufReader? = null

    @Volatile private var stopRequested = false
    public var eventsSeen: ULong = 0UL
        private set

    public fun serve() {
        if (!runningAsRoot()) {
            System.err.println("[wp04kt] refusing: requires initial-userns root")
            exitProcess(1)
        }
        Files.createDirectories(Path.of(socketPath).parent)
        Files.deleteIfExists(Path.of(socketPath))
        val lfd = posix.listenUnix(socketPath)
        check(lfd >= 0) { "listen failed rc=$lfd" }
        try {
            Files.setPosixFilePermissions(
                Path.of(socketPath),
                PosixFilePermissions.fromString("rw-rw----"),
            )
        } catch (_: Exception) {
        }
        System.err.println("[wp04kt] listening on $socketPath")

        while (!stopRequested) {
            val cfd = posix.accept(lfd)
            if (cfd < 0 || stopRequested) break
            if (clientFd != -1) {
                // One session at a time; duplicate controllers are rejected.
                posix.sendAll(cfd, "ERR BUSY\n".toByteArray())
                posix.close(cfd)
                continue
            }
            val cred = posix.peerCredentials(cfd)
            if (cred == null || cred.uid != 0) {
                posix.sendAll(cfd, "ERR PEER_UID ${cred?.uid ?: -1}\n".toByteArray())
                posix.close(cfd)
                continue
            }
            handleSession(cfd)
        }
        endSession("shutdown")
        posix.close(lfd)
        Files.deleteIfExists(Path.of(socketPath))
        System.err.println("[wp04kt] clean exit")
    }

    private fun runningAsRoot(): Boolean =
        runCatching {
            Files.readAllLines(Path.of("/proc/self/status")).any {
                it.startsWith("Uid:") && it.split(Regex("\\s+")).getOrNull(1) == "0"
            }
        }.getOrDefault(false)

    private fun reply(line: String) {
        posix.sendAll(clientFd, line.toByteArray())
    }

    private fun handleSession(cfd: Int) {
        clientFd = cfd
        epoch++
        engine = SessionEngine(epoch.toLong(), shim, bpfObjectPath).also { eng ->
            eng.verifier = SessionEngine.defaultMarkerVerifier { pid ->
                runCatching {
                    Files.newBufferedReader(Path.of("/proc", pid.toString(), "maps")).lineSequence()
                }.getOrNull()
            }
        }
        reply("OK HELLO epoch=$epoch\n")
        System.err.println("[wp04kt] epoch=$epoch accepted")

        val buffer = ByteArray(512)
        var buffered = 0
        try {
            while (!stopRequested) {
                val n = posix.recv(cfd, buffer, buffered, buffer.size - buffered)
                if (n <= 0) break
                buffered += n
                var scanStart = 0
                while (true) {
                    var nl = -1
                    for (idx in scanStart until buffered) {
                        if (buffer[idx] == '\n'.code.toByte()) { nl = idx; break }
                    }
                    if (nl < 0) break
                    dispatch(String(buffer, scanStart, nl - scanStart).trim())
                    scanStart = nl + 1
                    if (stopRequested || engine == null) break
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
        } finally {
            endSession(if (n_eof_last) "peer EOF" else "closed")
        }
    }

    @Volatile private var n_eof_last: Boolean = true

    private fun dispatch(line: String) {
        when (val parsed = parseControlCommand(line)) {
            is Either.Right -> reply(parsed.value.render())
            is Either.Left -> when (val cmd = parsed.value) {
                is ControlCommand.Attach -> {
                    val eng = requireNotNull(engine)
                    val r = eng.onAttach(cmd)
                    reply(r.render())
                    if (r.ok) startRingPoller(eng)
                }
                ControlCommand.Detach -> reply(requireNotNull(engine).onDetach().render())
                ControlCommand.Status -> reply("OK ${requireNotNull(engine).statusText()}\n")
                ControlCommand.Shutdown -> {
                    reply(ControlReply.ok("BYE").render())
                    stopRequested = true
                }
            }
        }
    }

    private fun startRingPoller(engineRef: SessionEngine) {
        val handle = engineRef.activeHandle() ?: return
        val fd = shim.ringFd(handle)
        ringReader?.close()
        ringReader = RingbufReader(fd, ringDataLength) { nr: Int, ctx: UInt ->
            eventsSeen++
            if (printEvents) {
                println("$nr ctx=$ctx")
                System.out.flush()
            }
        }
        ringThread?.join(100)
        stopRing = false
        ringThread = thread(name = "tier-e-ring", isDaemon = true) {
            while (!stopRequested && engine != null && !stopRing) {
                try {
                    ringReader?.pollOnce()
                } catch (t: Throwable) {
                    System.err.println("[wp04kt] ring error: $t")
                    break
                }
                Thread.sleep(5)
            }
        }
    }

    @Volatile private var stopRing = false

    private fun endSession(why: String) {
        stopRing = true
        ringThread?.join(200)
        ringThread = null
        ringReader?.close()
        ringReader = null
        engine?.close()
        System.err.println(
            "[wp04kt] epoch=$epoch DEAD ($why) events=$eventsSeen dropped=" +
                runCatching {
                    engine?.let { shim.droppedTotal(it.activeHandle() ?: 0L) } ?: 0UL
                }.getOrDefault(0UL),
        )
        engine = null
        if (clientFd != -1) {
            posix.close(clientFd)
            clientFd = -1
        }
    }
}

public fun main(args: Array<String>) {
    var sock = "/run/mazewall/wp04kt.sock"
    var shimPath = "build/libtier_e_bpf.so"
    var objPath = "build/context_probe.bpf.o"
    var printEvents = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--sock" -> sock = args[++i]
            "--shim" -> shimPath = args[++i]
            "--bpf" -> objPath = args[++i]
            "--verbose" -> printEvents = true
            else -> {
                System.err.println("usage: wp04kt [--sock p] [--shim so] [--bpf o] [--verbose]")
                exitProcess(2)
            }
        }
        i++
    }
    TierEDaemon(sock, shimPath, objPath, printEvents = printEvents).serve()
}
