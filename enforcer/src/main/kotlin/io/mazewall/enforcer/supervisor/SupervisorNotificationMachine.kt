package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind

public sealed interface JvmVerdict {
    public data class Deny(val errorNr: Int) : JvmVerdict
    public data object Allow : JvmVerdict
    public data object InjectFd : JvmVerdict
}

public sealed interface SupervisorRoute {
    public data object Continue : SupervisorRoute
    public data object AskJvm : SupervisorRoute
    public data object InjectFd : SupervisorRoute
    public data object SecureExec : SupervisorRoute
    public data class Abort(val errno: Int, val reason: String) : SupervisorRoute
}

public object SupervisorNotificationMachine {
    public fun classify(nr: Int, arch: Arch): SupervisedKind = SupervisedKind.classify(nr, arch)

    public fun parseJvmVerdict(decision: Int, errorNr: Int): JvmVerdict? {
        return when (decision) {
            0 -> JvmVerdict.Deny(errorNr)
            1 -> JvmVerdict.Allow
            2 -> JvmVerdict.InjectFd
            else -> null
        }
    }

    public fun evaluateFastPath(
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

    public fun evaluateJvm(kind: SupervisedKind, verdict: JvmVerdict): SupervisorRoute {
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
