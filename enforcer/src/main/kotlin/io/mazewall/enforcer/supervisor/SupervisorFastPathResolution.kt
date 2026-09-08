package io.mazewall.enforcer.supervisor

import io.mazewall.platform.seccomp.SupervisedKind

/**
 * Resolves the optional daemon-side path used to select a fast-path route.
 *
 * This deliberately performs no routing and emits no seccomp response: the session handler
 * remains the sole interpreter of the resulting route and its terminal effect.
 */
internal object SupervisorFastPathResolution {
    fun resolve(
        pid: Int,
        kind: SupervisedKind,
        arguments: SyscallArguments,
    ): ResolvedFastPath {
        if (kind !is SupervisedKind.Open || arguments.pathStr == null) {
            return ResolvedFastPath(null, arguments.pathStr)
        }
        val path = SupervisorFastPath.resolveAbsolutePath(pid, arguments.dirfd, arguments.pathStr)
        return ResolvedFastPath(path, path?.toAbsolutePath()?.toString() ?: arguments.pathStr)
    }
}
