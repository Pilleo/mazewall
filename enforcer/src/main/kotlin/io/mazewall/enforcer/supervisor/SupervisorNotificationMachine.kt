package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.core.SyscallNumber
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind

internal sealed interface JvmVerdict {
    data class Deny(
        val errorNr: Int,
    ) : JvmVerdict

    data object Allow : JvmVerdict

    data object InjectFd : JvmVerdict

    fun toWire(): Int =
        when (this) {
        is Deny -> 0
        is Allow -> 1
        is InjectFd -> 2
    }
}

/** Raw wire decoding remains open-ended; security routing receives a known verdict only. */
internal sealed interface JvmVerdictParse {
    data class Known(
        val verdict: JvmVerdict,
    ) : JvmVerdictParse

    data class Unknown(
        val rawDecision: Int,
    ) : JvmVerdictParse
}

internal sealed interface SupervisorRoute {
    public data object Continue : SupervisorRoute

    public data object AskJvm : SupervisorRoute

    public data object InjectFd : SupervisorRoute

    public data object SecureExec : SupervisorRoute

    public data class Abort(
        val errno: Int,
        val reason: String,
    ) : SupervisorRoute
}

internal object SupervisorNotificationMachine {
    fun classify(
        nr: SyscallNumber,
        arch: Arch,
    ): SupervisedKind = SupervisedKind.classify(nr, arch)

    fun decodeJvmVerdict(
        decision: Int,
        errorNr: Int,
    ): JvmVerdictParse =
        when (decision) {
            JVM_DECISION_DENY -> JvmVerdictParse.Known(JvmVerdict.Deny(errorNr))
            JVM_DECISION_ALLOW -> JvmVerdictParse.Known(JvmVerdict.Allow)
            JVM_DECISION_INJECT_FD -> JvmVerdictParse.Known(JvmVerdict.InjectFd)
            else -> JvmVerdictParse.Unknown(decision)
        }

    /** Compatibility boundary: unknown peer data is rejected with EPERM. */
    fun parseJvmVerdict(
        decision: Int,
        errorNr: Int,
    ): JvmVerdict =
        when (val parsed = decodeJvmVerdict(decision, errorNr)) {
            is JvmVerdictParse.Known -> parsed.verdict
            is JvmVerdictParse.Unknown -> JvmVerdict.Deny(NativeConstants.EPERM)
        }

    private const val JVM_DECISION_DENY = 0
    private const val JVM_DECISION_ALLOW = 1
    private const val JVM_DECISION_INJECT_FD = 2

    fun evaluateFastPath(
        kind: SupervisedKind,
        resolvedPath: java.nio.file.Path?,
        rawPath: String?,
    ): SupervisorRoute {
        return when (kind) {
            is SupervisedKind.Open -> evaluateOpenFastPath(resolvedPath, rawPath)
            is SupervisedKind.Connect,
            is SupervisedKind.Accept,
            is SupervisedKind.Exec,
            is SupervisedKind.Spawn,
            -> SupervisorRoute.AskJvm
            is SupervisedKind.Unknown -> unsupportedRoute()
        }
    }

    fun evaluateJvm(
        kind: SupervisedKind,
        verdict: JvmVerdict,
    ): SupervisorRoute {
        return when (verdict) {
            is JvmVerdict.Deny ->
                SupervisorRoute.Abort(verdict.errorNr, "jvm deny")
            is JvmVerdict.InjectFd -> when (kind) {
                is SupervisedKind.Open,
                is SupervisedKind.Connect,
                -> SupervisorRoute.InjectFd
                // accept(2) writes its peer address into caller-owned memory.  The
                // supervisor cannot reproduce that write safely because its pidfd
                // identity proof does not authorize remote-memory mutation.
                is SupervisedKind.Accept -> SupervisorRoute.Continue
                is SupervisedKind.Exec,
                is SupervisedKind.Spawn,
                is SupervisedKind.Unknown,
                -> unsupportedRoute()
            }
            is JvmVerdict.Allow -> when (kind) {
                is SupervisedKind.Open,
                is SupervisedKind.Connect,
                -> SupervisorRoute.InjectFd
                // Keep peer-address writes in the tracee's kernel-mediated accept
                // path; this preserves sockaddr/addrlen and EFAULT semantics.
                is SupervisedKind.Accept -> SupervisorRoute.Continue
                is SupervisedKind.Exec -> SupervisorRoute.SecureExec
                // The spawn authorization depends on register flags and parent identity,
                // never on clone's child stack/TID pointer contents. CLONE_THREAD remains
                // unconditionally allowed by the BPF argument inspector.
                is SupervisedKind.Spawn -> SupervisorRoute.Continue
                is SupervisedKind.Unknown -> unsupportedRoute()
            }
        }
    }

    private fun evaluateOpenFastPath(
        resolvedPath: java.nio.file.Path?,
        rawPath: String?,
    ): SupervisorRoute {
        if (resolvedPath == null || rawPath == null) {
            return SupervisorRoute.Abort(NativeConstants.EPERM, "unresolved open path")
        }
        if (BypassPaths.isBypassPath(resolvedPath)) {
            // Even a trusted path must execute the copied request: CONTINUE would
            // let a sibling mutate the tracee pathname after authorization.
            return SupervisorRoute.InjectFd
        }
        return SupervisorRoute.AskJvm
    }

    private fun unsupportedRoute(): SupervisorRoute.Abort = SupervisorRoute.Abort(NativeConstants.EPERM, "unsupported supervised operation")
}

internal sealed interface InjectTarget {
    data object Open : InjectTarget

    data object Connect : InjectTarget

    data object Accept : InjectTarget

    data object Unsupported : InjectTarget
}

internal fun injectTarget(kind: SupervisedKind): InjectTarget =
    when (kind) {
    is SupervisedKind.Open -> InjectTarget.Open
    is SupervisedKind.Connect -> InjectTarget.Connect
    is SupervisedKind.Accept -> InjectTarget.Accept
    is SupervisedKind.Exec, is SupervisedKind.Spawn, is SupervisedKind.Unknown -> InjectTarget.Unsupported
}

internal sealed interface ExecRewritePlan {
    data class Ready(
        val path: String,
    ) : ExecRewritePlan

    data object UnsupportedArch : ExecRewritePlan

    data object MissingPath : ExecRewritePlan
}

internal fun planExecRewrite(
    arch: Arch,
    pathStr: String?,
    jvmPath: String?,
): ExecRewritePlan {
    if (arch.audit != Arch.AUDIT_ARCH_X86_64) return ExecRewritePlan.UnsupportedArch
    val openPath = when {
        pathStr != null && !pathStr.startsWith("<YAMA_ERROR") -> pathStr
        !jvmPath.isNullOrEmpty() && !jvmPath.startsWith("<YAMA_ERROR") -> jvmPath
        else -> return ExecRewritePlan.MissingPath
    }
    return ExecRewritePlan.Ready(openPath)
}
