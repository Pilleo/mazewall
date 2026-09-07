package io.mazewall.enforcer.supervisor

import io.mazewall.ffi.NativeConstants

/** Pure ABI arguments for opening a resource on behalf of a supervised tracee. */
internal data class SupervisorOpenPlan(
    val path: String,
    val flags: Long,
    val mode: Long,
    val resolve: Long,
    val traceeDirfd: TraceeDirFd,
    val useCurrentWorkingDirectory: Boolean,
) {
    companion object {
        fun create(request: SupervisedOpen): SupervisorOpenPlan {
            val (flags, mode, resolve) =
                when (request) {
                    is SupervisedOpen.Open -> Triple(request.flags.value.toLong(), request.mode.toLong(), 0L)
                    is SupervisedOpen.OpenAt -> Triple(request.flags.value.toLong(), request.mode.toLong(), 0L)
                    is SupervisedOpen.OpenAt2 -> Triple(
                        request.how.flags.value
                            .toLong(),
                        request.how.mode,
                        request.how.resolve or NativeConstants.RESOLVE_BENEATH.toLong(),
                    )
                }
            val dirfd =
                when (request) {
                    is SupervisedOpen.Open -> TraceeDirFd.CurrentWorkingDirectory
                    is SupervisedOpen.OpenAt -> request.dirfd
                    is SupervisedOpen.OpenAt2 -> request.dirfd
                }
            return SupervisorOpenPlan(
                path = request.path,
                flags = flags,
                mode = mode,
                resolve = resolve,
                traceeDirfd = dirfd,
                useCurrentWorkingDirectory = request is SupervisedOpen.Open || request.path.startsWith("/") || dirfd == TraceeDirFd.CurrentWorkingDirectory,
            )
        }
    }
}
