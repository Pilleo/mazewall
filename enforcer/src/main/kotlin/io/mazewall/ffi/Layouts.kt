package io.mazewall.ffi

import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout

/**
 * Centralized registry for all Foreign Function & Memory (FFM) API memory layouts.
 *
 * This registry eliminates hardcoded byte offsets in the codebase, significantly
 * improving safety for coding agents and developers. Always use the `byteOffset()`
 * method on these layouts to resolve member positions.
 */
object Layouts {
    /**
     * Layout for capturing errno after a native call.
     */
    val ERRNO: StructLayout = Linker.Option.captureStateLayout()
    val ERRNO_OFFSET: Long = ERRNO.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    /**
     * Corresponds to `struct sock_filter` in `<linux/filter.h>`.
     * Used for BPF filter instructions.
     */
    val SOCK_FILTER: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("code"),
        ValueLayout.JAVA_BYTE.withName("jt"),
        ValueLayout.JAVA_BYTE.withName("jf"),
        ValueLayout.JAVA_INT.withName("k"),
    )
    val SOCK_FILTER_SIZE: Long = SOCK_FILTER.byteSize()
    val SOCK_FILTER_CODE_OFFSET: Long = SOCK_FILTER.byteOffset(MemoryLayout.PathElement.groupElement("code"))
    val SOCK_FILTER_JT_OFFSET: Long = SOCK_FILTER.byteOffset(MemoryLayout.PathElement.groupElement("jt"))
    val SOCK_FILTER_JF_OFFSET: Long = SOCK_FILTER.byteOffset(MemoryLayout.PathElement.groupElement("jf"))
    val SOCK_FILTER_K_OFFSET: Long = SOCK_FILTER.byteOffset(MemoryLayout.PathElement.groupElement("k"))

    /**
     * Corresponds to `struct sock_fprog` in `<linux/filter.h>`.
     * Used to pass the BPF program to the kernel.
     */
    val SOCK_FPROG: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("len"),
        MemoryLayout.paddingLayout(6), // Align pointer to 8 bytes
        ValueLayout.ADDRESS.withName("filter"),
    )
    val SOCK_FPROG_LEN_OFFSET: Long = SOCK_FPROG.byteOffset(MemoryLayout.PathElement.groupElement("len"))
    val SOCK_FPROG_FILTER_OFFSET: Long = SOCK_FPROG.byteOffset(MemoryLayout.PathElement.groupElement("filter"))

    /**
     * Corresponds to `struct seccomp_data` in `<linux/seccomp.h>`.
     * Contains the syscall number and arguments for BPF inspection.
     */
    val SECCOMP_DATA: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("nr"),
        ValueLayout.JAVA_INT.withName("arch"),
        ValueLayout.JAVA_LONG.withName("instruction_pointer"),
        MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_LONG).withName("args"),
    )

    /**
     * Corresponds to `struct seccomp_notif` in `<linux/seccomp.h>`.
     * Used by the Profiler to receive syscall notification events.
     */
    val SECCOMP_NOTIF: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("id"),
        ValueLayout.JAVA_INT.withName("pid"),
        ValueLayout.JAVA_INT.withName("flags"),
        SECCOMP_DATA.withName("data"),
    )

    /**
     * Corresponds to `struct seccomp_notif_resp` in `<linux/seccomp.h>`.
     * Used by the Profiler to ACK syscall notifications.
     */
    val SECCOMP_NOTIF_RESP: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("id"),
        ValueLayout.JAVA_LONG.withName("val"),
        ValueLayout.JAVA_INT.withName("error"),
        ValueLayout.JAVA_INT.withName("flags"),
    )

    /**
     * Corresponds to `struct seccomp_notif_addfd` in `<linux/seccomp.h>`.
     * Used to inject a file descriptor into a restricted process.
     */
    val SECCOMP_NOTIF_ADDFD: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("id"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("srcfd"),
        ValueLayout.JAVA_INT.withName("newfd"),
        ValueLayout.JAVA_INT.withName("newfd_flags"),
    )

    /**
     * Corresponds to `struct iovec` in `<sys/uio.h>`.
     */
    val IOVEC: StructLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("iov_base"),
        ValueLayout.JAVA_LONG.withName("iov_len"),
    )

    /**
     * Corresponds to `struct msghdr` in `<sys/socket.h>`.
     */
    val MSGHDR: StructLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("msg_name"),
        ValueLayout.JAVA_INT.withName("msg_namelen"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("msg_iov"),
        ValueLayout.JAVA_LONG.withName("msg_iovlen"),
        ValueLayout.ADDRESS.withName("msg_control"),
        ValueLayout.JAVA_LONG.withName("msg_controllen"),
        ValueLayout.JAVA_INT.withName("msg_flags"),
        MemoryLayout.paddingLayout(4),
    )

    /**
     * Corresponds to `struct cmsghdr` in `<sys/socket.h>`.
     */
    val CMSGHDR: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("cmsg_len"),
        ValueLayout.JAVA_INT.withName("cmsg_level"),
        ValueLayout.JAVA_INT.withName("cmsg_type"),
    )

    /**
     * Corresponds to `struct sockaddr_un` in `<sys/un.h>`.
     */
    val SOCKADDR_UN: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("sun_family"),
        MemoryLayout.sequenceLayout(108, ValueLayout.JAVA_BYTE).withName("sun_path"),
    )

    /**
     * Corresponds to `struct pollfd` in `<poll.h>`.
     */
    val POLLFD: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("fd"),
        ValueLayout.JAVA_SHORT.withName("events"),
        ValueLayout.JAVA_SHORT.withName("revents"),
    )
    val POLLFD_FD_OFFSET: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("fd"))
    val POLLFD_EVENTS_OFFSET: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("events"))
    val POLLFD_REVENTS_OFFSET: Long = POLLFD.byteOffset(MemoryLayout.PathElement.groupElement("revents"))

    /**
     * Corresponds to `struct landlock_ruleset_attr` in `<linux/landlock.h>`.
     */
    val LANDLOCK_RULESET_ATTR: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("handled_access_fs"),
        ValueLayout.JAVA_LONG.withName("handled_access_net"),
    )
    const val LANDLOCK_RULESET_ATTR_V1_SIZE: Long = 8L
    val LANDLOCK_RULESET_ATTR_FS_OFFSET: Long =
        LANDLOCK_RULESET_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("handled_access_fs"))
    val LANDLOCK_RULESET_ATTR_NET_OFFSET: Long =
        LANDLOCK_RULESET_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("handled_access_net"))

    /**
     * Corresponds to `struct landlock_path_beneath_attr` in `<linux/landlock.h>`.
     */
    val LANDLOCK_PATH_BENEATH_ATTR: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withByteAlignment(1).withName("allowed_access"),
        ValueLayout.JAVA_INT.withByteAlignment(1).withName("parent_fd"),
    )
    val LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET: Long =
        LANDLOCK_PATH_BENEATH_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("allowed_access"))
    val LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET: Long =
        LANDLOCK_PATH_BENEATH_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("parent_fd"))

    // Offsets for struct seccomp_notif_addfd
    val SECCOMP_NOTIF_ADDFD_ID_OFFSET: Long = SECCOMP_NOTIF_ADDFD.byteOffset(MemoryLayout.PathElement.groupElement("id"))
    val SECCOMP_NOTIF_ADDFD_FLAGS_OFFSET: Long = SECCOMP_NOTIF_ADDFD.byteOffset(MemoryLayout.PathElement.groupElement("flags"))
    val SECCOMP_NOTIF_ADDFD_SRCFD_OFFSET: Long = SECCOMP_NOTIF_ADDFD.byteOffset(MemoryLayout.PathElement.groupElement("srcfd"))
    val SECCOMP_NOTIF_ADDFD_NEWFD_OFFSET: Long = SECCOMP_NOTIF_ADDFD.byteOffset(MemoryLayout.PathElement.groupElement("newfd"))
    val SECCOMP_NOTIF_ADDFD_NEWFD_FLAGS_OFFSET: Long = SECCOMP_NOTIF_ADDFD.byteOffset(MemoryLayout.PathElement.groupElement("newfd_flags"))

    // Offsets for struct iovec
    val IOVEC_BASE_OFFSET: Long = IOVEC.byteOffset(MemoryLayout.PathElement.groupElement("iov_base"))
    val IOVEC_LEN_OFFSET: Long = IOVEC.byteOffset(MemoryLayout.PathElement.groupElement("iov_len"))

    // Offsets for struct msghdr
    val MSGHDR_NAME_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_name"))
    val MSGHDR_NAMELEN_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_namelen"))
    val MSGHDR_IOV_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_iov"))
    val MSGHDR_IOVLEN_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_iovlen"))
    val MSGHDR_CONTROL_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_control"))
    val MSGHDR_CONTROLLEN_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_controllen"))
    val MSGHDR_FLAGS_OFFSET: Long = MSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("msg_flags"))

    // Offsets for struct cmsghdr
    val CMSGHDR_LEN_OFFSET: Long = CMSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("cmsg_len"))
    val CMSGHDR_LEVEL_OFFSET: Long = CMSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("cmsg_level"))
    val CMSGHDR_TYPE_OFFSET: Long = CMSGHDR.byteOffset(MemoryLayout.PathElement.groupElement("cmsg_type"))

    // Offsets for struct sockaddr_un
    val SOCKADDR_UN_FAMILY_OFFSET: Long = SOCKADDR_UN.byteOffset(MemoryLayout.PathElement.groupElement("sun_family"))
    val SOCKADDR_UN_PATH_OFFSET: Long = SOCKADDR_UN.byteOffset(MemoryLayout.PathElement.groupElement("sun_path"))
    const val SOCKADDR_UN_PATH_SIZE: Long = 108L

    const val CMSGHDR_DATA_OFFSET: Long = 16L

    const val SUPERVISOR_RESPONSE_ID_OFFSET: Long = 0L
    const val SUPERVISOR_RESPONSE_DECISION_OFFSET: Long = 8L
    const val SUPERVISOR_RESPONSE_ERROR_OFFSET: Long = 12L
    const val SUPERVISOR_RESPONSE_SIZE: Long = 16L
}
