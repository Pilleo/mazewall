package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.Pid
import java.io.File
import java.io.IOException

private const val AUTHORIZATION_TTL_NANOS = 10_000_000_000L

/** A PID is reusable; Linux start-time ticks identify its particular process lifetime. */
internal data class ProcessIdentity(
    val pid: Pid,
    val startTimeTicks: Long,
)

internal fun interface ProcessIdentityResolver {
    fun resolve(pid: Pid): ProcessIdentity?
}

internal class ProcProcessIdentityResolver(
    private val readStat: (Pid) -> String = { File("/proc/${it.value}/stat").readText() },
) : ProcessIdentityResolver {
    override fun resolve(pid: Pid): ProcessIdentity? {
        if (pid.value <= 0) return null
        return try {
            parse(pid, readStat(pid))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun parse(
        pid: Pid,
        stat: String,
    ): ProcessIdentity? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 1 || close <= open || stat.substring(0, open).trim().toIntOrNull() != pid.value) return null
        // comm (field 2) can contain spaces and parentheses. Field 22 is index 19 after comm.
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        val startTime = fields.getOrNull(19)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcessIdentity(pid, startTime)
    }
}

internal enum class SpawnRegistrationResult { REGISTERED, CLOSED, IDENTITY_UNAVAILABLE }

internal enum class SpawnRejection { MISSING, EXPIRED, CLOSED, IDENTITY_UNAVAILABLE }

internal sealed interface SpawnAuthorizationResult {
    class Authorized(
        stack: List<StackTraceElement>,
    ) : SpawnAuthorizationResult {
        private val snapshot = copyStack(stack)
        val stack: List<StackTraceElement> get() = copyStack(snapshot)
    }

    data class Rejected(
        val reason: SpawnRejection,
    ) : SpawnAuthorizationResult
}

/** One outstanding spawn per process identity, owned exclusively by its supervisor session. */
internal class SpawnAuthorizationStore : AutoCloseable {
    private class Entry(
        val stack: List<StackTraceElement>,
        val registeredAtNanos: Long,
    ) {
        fun isExpired(nowNanos: Long): Boolean = nowNanos - registeredAtNanos > AUTHORIZATION_TTL_NANOS
    }

    private val entries = mutableMapOf<ProcessIdentity, Entry>()
    private var closed = false

    @Synchronized
    fun register(
        identity: ProcessIdentity,
        stack: List<StackTraceElement>,
        nowNanos: Long = System.nanoTime(),
    ): SpawnRegistrationResult {
        if (closed) return SpawnRegistrationResult.CLOSED
        entries.entries.removeIf { it.value.isExpired(nowNanos) }
        entries[identity] = Entry(copyStack(stack), nowNanos)
        return SpawnRegistrationResult.REGISTERED
    }

    @Synchronized
    fun consume(
        identity: ProcessIdentity,
        nowNanos: Long = System.nanoTime(),
    ): SpawnAuthorizationResult {
        if (closed) return SpawnAuthorizationResult.Rejected(SpawnRejection.CLOSED)
        val entry = entries.remove(identity) ?: return SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING)
        return if (entry.isExpired(nowNanos)) {
            SpawnAuthorizationResult.Rejected(SpawnRejection.EXPIRED)
        } else {
            SpawnAuthorizationResult.Authorized(entry.stack)
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    override fun close() {
        closed = true
        entries.clear()
    }
}

private fun copyStack(stack: List<StackTraceElement>): List<StackTraceElement> =
    stack.mapTo(ArrayList(stack.size)) {
    StackTraceElement(it.classLoaderName, it.moduleName, it.moduleVersion, it.className, it.methodName, it.fileName, it.lineNumber)
}
