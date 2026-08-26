package io.mazewall.tierE.daemon

import io.mazewall.tierE.shim.ShimException
import io.mazewall.tierE.shim.TierEBpfShim
import java.nio.file.Files
import java.nio.file.Path

/** Outcome of marker hygiene checks; every failure reason is loud and distinct. */
public sealed interface VerifyResult {
    public data class Ok(public val resolvedPath: String, public val buildIdHex: String) : VerifyResult
    public data class Failure(public val reason: String) : VerifyResult
}

/** Verifiable marker facts; replaceable in tests. */
public fun interface MarkerVerifier {
    public fun verify(pid: Int, markerPath: String): VerifyResult
}

/**
 * Session state machine (WP-04). One instance serves ONE client connection —
 * the "session". Sessions are created per accepted connection and are
 * terminal in DEAD; a subsequent connection starts a NEW epoch with freshly
 * loaded BPF objects (invariants 5 and 7). Re-binding within a live epoch is
 * refused: one target binding per epoch keeps attribution unambiguous.
 *
 * All decisions flow through injected ports ([TierEBpfShim], [verifier],
 * [mapsLineProvider]) so trust logic is unit-testable without a kernel.
 */
public class SessionEngine(
    public val epoch: Long,
    private val shim: TierEBpfShim,
    private val bpfObjectPath: String = "build/context_probe.bpf.o",
    private val mapsLineProvider: (pid: Int) -> Sequence<String>? =
        { pid ->
            runCatching {
                Files.newBufferedReader(Path.of("/proc", pid.toString(), "maps")).lineSequence()
            }.getOrNull()
        },
) : AutoCloseable {

    public sealed interface State {
        public data object Accepted : State
        public data class Running(public val tgid: Int, public val mode: AttachMode) : State
        public data object Detached : State // same epoch, unbound; no re-bind
        public data object Dead : State
    }

    public var state: State = State.Accepted
        private set

    private var handle: Long = INVALID_HANDLE

    /** Current epoch object handle, or null when nothing is loaded. */
    public fun activeHandle(): Long? = if (handle == INVALID_HANDLE) null else handle

    /** Verifiable marker facts; replaceable in tests. */
    public var verifier: MarkerVerifier = defaultMarkerVerifier(mapsLineProvider)

    public fun onAttach(cmd: ControlCommand.Attach): ControlReply {
        if (state !is State.Accepted) {
            return when (val st = state) {
                is State.Running -> ControlReply.err("ALREADY_BOUND tgid=${st.tgid}")
                else -> ControlReply.err("STATE")
            }
        }
        val result = verifier.verify(cmd.pid, cmd.markerPath)
        if (result !is VerifyResult.Ok) {
            // Strict parity with the C oracle: hygiene failures are loud AND
            // terminal for the session; the operator reconnects for a new epoch.
            val reason = (result as? VerifyResult.Failure)?.reason ?: "VERIFY_FAILED"
            destroyEpoch()
            state = State.Dead
            return ControlReply.err("MARKER_$reason")
        }
        val marker = result

        handle = try {
            shim.loadObject(bpfObjectPath)
        } catch (e: ShimException) {
            state = State.Dead
            return ControlReply.err("LOAD_BPF ${e.message}")
        }
        return try {
            shim.setTargetTgid(handle, cmd.pid)
            shim.attachSysEnter(handle)
            if (cmd.mode == AttachMode.UPROBE) {
                shim.attachMarkerUprobe(handle, cmd.pid, marker.resolvedPath)
            } else {
                shim.attachMarkerUsdt(handle, cmd.pid, marker.resolvedPath)
            }
            state = State.Running(cmd.pid, cmd.mode)
            ControlReply.ok("ATTACHED epoch=$epoch buildid=${marker.buildIdHex}")
        } catch (e: ShimException) {
            shim.destroy(handle)
            handle = INVALID_HANDLE
            state = State.Dead
            ControlReply.err("ATTACH_FAILED ${e.operation} ${e.message}")
        }
    }

    public fun onDetach(): ControlReply {
        if (state !is State.Running) return ControlReply.err("NOT_BOUND")
        destroyEpoch()
        state = State.Detached
        return ControlReply.ok("DETACHED")
    }

    public fun statusText(): String = when (val st = state) {
        is State.Running -> "RUNNING epoch=$epoch tgid=${st.tgid}"
        State.Accepted -> "ACCEPTED epoch=$epoch tgid=-1"
        State.Detached -> "DETACHED epoch=$epoch tgid=-1"
        State.Dead -> "DEAD epoch=$epoch tgid=-1"
    }

    override fun close() {
        destroyEpoch()
        state = State.Dead
    }

    private fun destroyEpoch() {
        if (handle != INVALID_HANDLE) {
            shim.destroy(handle)
            handle = INVALID_HANDLE
        }
    }

    public companion object {
        private const val INVALID_HANDLE = 0L

        /**
         * Marker hygiene (design doc §5, §11 risk 4): resolve the path, require
         * a regular file whose inode appears in the TARGET's /proc/<pid>/maps,
         * then read NT_GNU_BUILD_ID from the exact bytes we will probe. Every
         * miss yields a distinct loud refusal reason.
         */
        public fun defaultMarkerVerifier(
            mapsLineProvider: (pid: Int) -> Sequence<String>?,
        ): MarkerVerifier = MarkerVerifier { pid, markerPath ->
            val real = try {
                Path.of(markerPath).toRealPath()
            } catch (_: java.io.IOException) {
                return@MarkerVerifier VerifyResult.Failure("PATH")
            }
            val attr = try {
                @Suppress("UNCHECKED_CAST")
                Files.readAttributes(
                    real,
                    "unix:ino",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ) as Map<String, Any>
            } catch (_: Exception) {
                return@MarkerVerifier VerifyResult.Failure("STAT")
            }
            val inode = (attr["ino"] as? Number)?.toLong()
                ?: return@MarkerVerifier VerifyResult.Failure("STAT")

            // 1) ELF identity FIRST: a garbage file reports its own defect
            //    even when never mapped by the target (C-oracle ordering).
            val bytes = try {
                Files.readAllBytes(real)
            } catch (_: java.io.IOException) {
                return@MarkerVerifier VerifyResult.Failure("READ")
            }
            val buildId = ElfBuildIdExtractor.extract(bytes)
                ?: return@MarkerVerifier VerifyResult.Failure("BUILD_ID_UNREADABLE")

            // 2) Target-maps binding: exact file must be resident in tracee.
            val lines = mapsLineProvider(pid)
                ?: return@MarkerVerifier VerifyResult.Failure("PROC_MAPS")
            val mapped = lines.any { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.size >= 6 && parts[4].toLongOrNull() == inode &&
                    parts[5] == real.toString()
            }
            if (!mapped) return@MarkerVerifier VerifyResult.Failure("NOT_MAPPED_IN_TARGET")

            VerifyResult.Ok(real.toString(), buildId)
        }

        /** Matches one /proc/<pid>/maps line by exact pathname + inode.
         *  Device fields are deliberately ignored: their encoding varies
         *  across container engines/mount layers (observed 00:22 vs 0x31 for
         *  the same file) and adds nothing once the path is pinned to the
         *  target's own maps view. */
        internal fun mapsEntryMatches(line: String, inode: Long, resolvedPath: String): Boolean {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return false
            val mappedInode = parts[4].toLongOrNull() ?: return false
            return mappedInode == inode && parts[5] == resolvedPath
        }
    }
}
