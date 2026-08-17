package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind

internal sealed interface JvmVerdict {
    data class Deny(val errorNr: Int) : JvmVerdict
    data object Allow : JvmVerdict
    data object InjectFd : JvmVerdict

    fun toWire(): Int = when (this) {
        is Deny -> 0
        is Allow -> 1
        is InjectFd -> 2
    }
}

internal sealed interface SupervisorRoute {
    public data object Continue : SupervisorRoute
    public data object AskJvm : SupervisorRoute
    public data object InjectFd : SupervisorRoute
    public data object SecureExec : SupervisorRoute
    public data class Abort(val errno: Int, val reason: String) : SupervisorRoute
}

internal object SupervisorNotificationMachine {
    fun classify(nr: Int, arch: Arch): SupervisedKind = SupervisedKind.classify(nr, arch)

    fun parseJvmVerdict(decision: Int, errorNr: Int): JvmVerdict? {
        return when (decision) {
            0 -> JvmVerdict.Deny(errorNr)
            1 -> JvmVerdict.Allow
            2 -> JvmVerdict.InjectFd
            else -> null
        }
    }

    fun evaluateFastPath(
        kind: SupervisedKind,
        resolvedPath: java.nio.file.Path?,
        rawPath: String?,
    ): SupervisorRoute {
        return when (kind) {
            is SupervisedKind.Unknown ->
                SupervisorRoute.Abort(NativeConstants.EPERM, "unsupervised syscall number")
            is SupervisedKind.Open -> evaluateOpenFastPath(resolvedPath, rawPath)
            is SupervisedKind.Connect,
            is SupervisedKind.Accept,
            is SupervisedKind.Exec,
            is SupervisedKind.Spawn,
            -> SupervisorRoute.AskJvm
        }
    }

    fun evaluateJvm(kind: SupervisedKind, verdict: JvmVerdict): SupervisorRoute {
        return when (verdict) {
            is JvmVerdict.Deny ->
                SupervisorRoute.Abort(verdict.errorNr, "jvm deny")
            is JvmVerdict.InjectFd -> SupervisorRoute.InjectFd
            is JvmVerdict.Allow -> when (kind) {
                is SupervisedKind.Open,
                is SupervisedKind.Connect,
                is SupervisedKind.Accept,
                -> SupervisorRoute.InjectFd
                is SupervisedKind.Exec -> SupervisorRoute.SecureExec
                is SupervisedKind.Spawn -> SupervisorRoute.Continue
                is SupervisedKind.Unknown ->
                    SupervisorRoute.Abort(NativeConstants.EPERM, "jvm allow on unknown nr")
            }
        }
    }

    private fun evaluateOpenFastPath(
        resolvedPath: java.nio.file.Path?,
        rawPath: String?,
    ): SupervisorRoute {
        if (resolvedPath != null && BypassPaths.isBypassPath(resolvedPath)) {
            return SupervisorRoute.Continue
        }
        if (resolvedPath == null && rawPath != null && looksLikeClassloading(rawPath)) {
            return SupervisorRoute.Continue
        }
        return SupervisorRoute.AskJvm
    }

    internal fun looksLikeClassloading(path: String): Boolean =
        path.endsWith(".class") || path.contains("META-INF/") || path.endsWith(".jar")
}

internal sealed interface InjectTarget {
    data object Open : InjectTarget
    data object Connect : InjectTarget
    data object Accept : InjectTarget
    data object Unsupported : InjectTarget
}

internal fun injectTarget(kind: SupervisedKind): InjectTarget = when (kind) {
    is SupervisedKind.Open -> InjectTarget.Open
    is SupervisedKind.Connect -> InjectTarget.Connect
    is SupervisedKind.Accept -> InjectTarget.Accept
    is SupervisedKind.Exec, is SupervisedKind.Spawn, is SupervisedKind.Unknown -> InjectTarget.Unsupported
}

internal sealed interface ExecRewritePlan {
    data class Ready(val path: String) : ExecRewritePlan
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
