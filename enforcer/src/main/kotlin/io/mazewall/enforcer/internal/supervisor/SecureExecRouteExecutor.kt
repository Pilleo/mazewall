package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.lifecycle.OpenFlags
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.syscall.Arch

internal data class SecureExecRequest(
    val tid: Tid,
    val argv: Long,
    val envp: Long,
    val execveat: Long,
)

/** Opens only a JVM-authorized exec route. Session spawn authority stays in the JVM listener. */
internal class SecureExecRouteExecutor(
    private val engine: NativeEngine,
) {
    context(arena: NativeArena) fun execute(
        context: SupervisorRouteContext,
        jvmPath: String?,
    ): SupervisorReplyEffect {
        val header = context.request.header
        val args = header.args
        val exec = SecureExecRequest(
            header.tid,
            args[if (header.nr == header.arch.execve) 1 else 2],
            args[if (header.nr == header.arch.execve) 2 else 3],
            header.arch.execveat.toLong(),
        )
        val target = when (val plan = planExecRewrite(header.arch, context.request.path, jvmPath)) {
            is ExecRewritePlan.MissingPath, is ExecRewritePlan.UnsupportedArch ->
                SupervisorDescriptorResult.Denied(NativeConstants.EPERM)
            is ExecRewritePlan.Ready ->
                SupervisorDescriptorResult.from(
                    engine.fileSystem.open(arena.allocateFrom(plan.path), OpenFlags(NativeConstants.O_PATH or NativeConstants.O_CLOEXEC)),
                    engine,
                )
        }
        return SupervisorReplyEffect.SecureExecAndReply(exec, target)
    }
}
