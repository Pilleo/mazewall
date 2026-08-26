package io.mazewall.tierE.daemon

/** Line commands accepted on the control socket (WP-04 wire contract). */
public sealed interface ControlCommand {
    public data class Attach(
        public val pid: Int,
        public val mode: AttachMode,
        public val markerPath: String,
    ) : ControlCommand

    public data object Detach : ControlCommand
    public data object Status : ControlCommand
    public data object Shutdown : ControlCommand
}

public enum class AttachMode { UPROBE, USDT }

/** Single-line replies; `ok` mirrors the OK/ERR prefix contract. */
public data class ControlReply(public val ok: Boolean, public val text: String) {
    public fun render(): String = "${if (ok) "OK" else "ERR"} $text\n"

    public companion object {
        public fun ok(text: String = ""): ControlReply = ControlReply(true, text)
        public fun err(text: String = ""): ControlReply = ControlReply(false, text)
    }
}

private val USAGE_ERR =
    "USAGE wp04: ATTACH <pid> <uprobe|usdt> <marker.so> | DETACH | STATUS | SHUTDOWN"

public fun parseControlCommand(line: String): Either<ControlCommand, ControlReply> {
    val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return Either.Right(ControlReply.err("EMPTY_COMMAND"))
    return when (tokens[0].uppercase()) {
        "ATTACH" -> {
            if (tokens.size != 4) return Either.Right(ControlReply.err(USAGE_ERR))
            val pid = tokens[1].toIntOrNull()
            if (pid == null || pid <= 0) return Either.Right(ControlReply.err("BAD_PID"))
            val mode = when (tokens[2].lowercase()) {
                "uprobe" -> AttachMode.UPROBE
                "usdt" -> AttachMode.USDT
                else -> return Either.Right(ControlReply.err("BAD_MODE"))
            }
            Either.Left(ControlCommand.Attach(pid, mode, tokens[3]))
        }
        "DETACH" -> if (tokens.size == 1) Either.Left(ControlCommand.Detach)
        else Either.Right(ControlReply.err(USAGE_ERR))
        "STATUS" -> if (tokens.size == 1) Either.Left(ControlCommand.Status)
        else Either.Right(ControlReply.err(USAGE_ERR))
        "SHUTDOWN" -> if (tokens.size == 1) Either.Left(ControlCommand.Shutdown)
        else Either.Right(ControlReply.err(USAGE_ERR))
        else -> Either.Right(ControlReply.err(USAGE_ERR))
    }
}

/** Minimal right/left to keep the parser dependency-free. */
public sealed interface Either<L, R> {
    public data class Left<L, R>(public val value: L) : Either<L, R>
    public data class Right<L, R>(public val value: R) : Either<L, R>
}
