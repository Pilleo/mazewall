package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.tierE.ffi.PosixFfi
import io.mazewall.profiler.tierE.shim.TierEBpfShim
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Owns ALL per-epoch resources (engine, ring poller, socket fd) and
 * implements [AutoCloseable] so Kotlin's `use {}` guarantees cleanup even on
 * exceptions — replacing manual `finally` blocks.
 *
 * The ring consumer thread is a non-daemon thread managed internally;
 * [close] joins it before returning.
 */
public class SessionResource(
    public val epoch: Long,
    public val cfd: Int,
    bpfObjectPath: String,
    private val shim: TierEBpfShim,
    private val posix: PosixFfi,
    mapsLineProvider: (pid: Int) -> Sequence<String>?,
) : AutoCloseable {

    public val engine: SessionEngine = SessionEngine(epoch, shim, bpfObjectPath).also { eng ->
        eng.verifier = SessionEngine.defaultMarkerVerifier(mapsLineProvider)
    }

    private val stopRing = AtomicBoolean(false)
    private var ringThread: Thread? = null
    private var rbHandle: Long = -1L
    private var boundHandle: Long = -1L
    private var eventCount: Int = 0
    private var printEvents: Boolean = false

    public fun setPrintEvents(v: Boolean) {
        printEvents = v
    }

    public fun bindAndPoll(handle: Long) {
        boundHandle = handle
        val rb = shim.ringNew(handle)
        rbHandle = rb
        stopRing.set(false)
        thread(name = "tier-e-ring-$epoch", isDaemon = true) {
            while (!stopRing.get()) {
                try {
                    shim.ringPoll(rb, 50)
                } catch (t: Throwable) {
                    System.err.println("[wp04kt] ring error: $t")
                    break
                }
                Thread.sleep(5)
            }
            shim.ringDestroy(rb)
        }
    }

    public fun incrementEvents() {
        eventCount++
    }

    override fun close() {
        stopRing.set(true)
        ringThread?.join(200)
        ringThread = null
        engine.close()
        posix.close(cfd)
    }
}
