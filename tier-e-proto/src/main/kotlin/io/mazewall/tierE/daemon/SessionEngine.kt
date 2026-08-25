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
            val reason = (result as? VerifyResult.Failure)?.reason ?: "VERIFY_FAILED"
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
                val map = Files.readAttributes(
                    real,
                    "unix:size,ino,dev",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ) as Map<String, Any>
                map
            } catch (_: Exception) {
                return@MarkerVerifier VerifyResult.Failure("STAT")
            }
            val inode = (attr["ino"] as? Number)?.toLong()
                ?: return@MarkerVerifier VerifyResult.Failure("STAT")
            val dev = (attr["dev"] as? Number)?.toLong()
                ?: return@MarkerVerifier VerifyResult.Failure("STAT")

            val lines = mapsLineProvider(pid)
                ?: return@MarkerVerifier VerifyResult.Failure("PROC_MAPS")
            val mapped = lines.any { line -> mapsEntryMatches(line, inode, dev) }
            if (!mapped) return@MarkerVerifier VerifyResult.Failure("NOT_MAPPED_IN_TARGET")

            val bytes = try {
                Files.readAllBytes(real)
            } catch (_: java.io.IOException) {
                return@MarkerVerifier VerifyResult.Failure("READ")
            }
            ElfBuildIdExtractor.extract(bytes)?.let { VerifyResult.Ok(real.toString(), it) }
                ?: VerifyResult.Failure("BUILD_ID_UNREADABLE")
        }

        /** Formats a raw dev_t the way /proc/<pid>/maps prints it ("MAJ:MIN"). */
        internal fun mapsDevString(dev: Long): String {
            val major = ((dev shr 8) and 0xFFF) or ((dev shr 32) and -0x1000L)
            val minor = (dev and 0xFF) or ((dev shr 12) and -0x100L)
            return "%02x:%02x".format(major, minor)
        }

        /** Matches one /proc/<pid>/maps line by pathname + inode + device. */
        internal fun mapsEntryMatches(line: String, inode: Long, dev: Long): Boolean {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) return false
            val devPart = parts.getOrNull(3) ?: return false
            val colon = devPart.indexOf(':')
            if (colon <= 0) return false
            val mappedMajor = devPart.substring(0, colon).toULongOrNull(16) ?: return false
            val mappedMinor = devPart.substring(colon + 1).toULongOrNull(16) ?: return false
            val mappedInode = parts[4].toLongOrNull() ?: return false
            val pathPart = parts.getOrNull(5) ?: return false
            // Linux new_encode_dev: major/minor printed from dev_t encoding.
            val major = ((dev shr 8) and 0xFFF) or ((dev shr 32) and -0x1000L)
            val minor = (dev and 0xFF) or ((dev shr 12) and -0x100L)
            return pathPart.isNotEmpty() && mappedInode == inode &&
                mappedMajor.toLong() == major && mappedMinor.toLong() == minor
        }
    }
}
