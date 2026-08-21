package io.mazewall.platform.seccomp

import io.mazewall.MazewallInternal
import io.mazewall.core.Arch

/** USER_NOTIF kinds shared by supervisor policy and profiler noise filtering. */
@MazewallInternal
public sealed interface SupervisedKind {
    public data object Open : SupervisedKind
    public data object Connect : SupervisedKind
    public data object Accept : SupervisedKind
    public data object Exec : SupervisedKind
    public data object Spawn : SupervisedKind
    public data object Unknown : SupervisedKind

    public companion object {
        public fun classify(nr: Int, arch: Arch): SupervisedKind {
            return when (nr) {
                arch.open, arch.openat, arch.openat2 -> Open
                arch.connect -> Connect
                arch.accept, arch.accept4 -> Accept
                arch.execve, arch.execveat -> Exec
                arch.fork, arch.vfork, arch.clone -> Spawn
                else -> Unknown
            }
        }
    }
}
