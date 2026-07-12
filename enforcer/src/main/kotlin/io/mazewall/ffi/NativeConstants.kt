package io.mazewall.ffi

/**
 * Centralized registry for all Linux kernel constants and system call numbers.
 */
object NativeConstants {
    // Poll events
    const val POLLIN: Short = 1
    const val POLLERR: Short = 8
    const val POLLHUP: Short = 16
    const val POLLNVAL: Short = 32

    // Landlock
    const val LANDLOCK_CREATE_RULESET_NR = 444L
    const val LANDLOCK_ADD_RULE_NR = 445L
    const val LANDLOCK_RESTRICT_SELF_NR = 446L
    const val LANDLOCK_RULE_PATH_BENEATH = 1
    const val LANDLOCK_CREATE_RULESET_VERSION = (1L shl 0)

    // prctl options
    const val PR_SET_PDEATHSIG = 1
    const val PR_SET_NAME = 15
    const val PR_GET_NAME = 16
    const val PR_SET_MM = 25
    const val PR_GET_SECCOMP = 21
    const val PR_SET_SECCOMP = 22
    const val PR_SET_NO_NEW_PRIVS = 38
    const val PR_GET_NO_NEW_PRIVS = 39
    const val PR_CAP_AMBIENT = 47
    const val PR_SET_PTRACER = 0x59616d61

    // prctl sub-options
    const val PR_CAP_AMBIENT_RAISE = 2

    // Seccomp
    const val SECCOMP_SET_MODE_FILTER = 1
    const val SECCOMP_MODE_FILTER = 2
    const val SECCOMP_FILTER_FLAG_TSYNC = 1
    const val SECCOMP_FILTER_FLAG_NEW_LISTENER = (1L shl 3)
    const val SECCOMP_USER_NOTIF_FLAG_CONTINUE = (1L shl 0)

    // Seccomp IOCTLs
    const val SECCOMP_IOCTL_NOTIF_RECV = 0xc0502100L
    const val SECCOMP_IOCTL_NOTIF_SEND = 0xc0182101L
    const val SECCOMP_IOCTL_NOTIF_ID_VALID = 0x40082102L
    const val SECCOMP_IOCTL_NOTIF_ADDFD = 0x40182103L

    // Seccomp ADDFD Flags
    const val SECCOMP_ADDFD_FLAG_SETFD = 1L shl 0
    const val SECCOMP_ADDFD_FLAG_SEND = 1L shl 1

    // Seccomp Return Actions
    const val SECCOMP_RET_KILL_PROCESS = 0x80000000.toInt()
    const val SECCOMP_RET_KILL_THREAD = 0x00000000
    const val SECCOMP_RET_TRAP = 0x00030000
    const val SECCOMP_RET_ERRNO = 0x00050000
    const val SECCOMP_RET_USER_NOTIF = 0x7fc00000
    const val SECCOMP_RET_LOG = 0x7ffc0000
    const val SECCOMP_RET_ALLOW = 0x7fff0000

    // Ioctl
    const val FIONBIO = 0x5421
    const val FIONREAD = 0x541B
    const val UFFDIO_API = 0xc018aa3fL
    const val UFFDIO_REGISTER = 0xc020aa00L
    const val UFFDIO_UNREGISTER = 0x8010aa01L
    const val UFFDIO_COPY = 0xc028aa03L
    const val UFFDIO_ZEROPAGE = 0xc020aa04L

    // Errors
    const val EPERM = 1
    const val EINTR = 4
    const val ENOSYS = 38

    // File options
    const val O_PATH = 0x01000000
    const val O_CLOEXEC = 0x00080000
    const val O_NOFOLLOW = 0x00020000
}
