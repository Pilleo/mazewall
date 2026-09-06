package io.mazewall.ffi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.exists

/** Compares the FFM layouts with the C headers installed on the test host. */
class CAbiLayoutOracleTest {
    @Test
    fun `manual layouts match the host C ABI`() {
        val oracle = Path("build", "abi-oracle", "layout-oracle")
        assertTrue(oracle.exists(), "C ABI oracle was not built; run :platform:compileCAbiOracle")
        val process = ProcessBuilder(oracle.toAbsolutePath().toString()).start()
        val actual = process.inputStream.bufferedReader().readLines().associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key to value.toLong()
        }
        assertEquals(0, process.waitFor(), "C ABI oracle failed")

        expectedLayouts().forEach { (key, expected) ->
            assertEquals(expected, actual[key], "C ABI mismatch for $key")
        }
    }

    private fun expectedLayouts(): Map<String, Long> =
        mapOf(
        "size.sock_filter" to Layouts.SOCK_FILTER_SIZE,
        "offset.sock_filter.code" to Layouts.SOCK_FILTER_CODE_OFFSET,
        "offset.sock_filter.jt" to Layouts.SOCK_FILTER_JT_OFFSET,
        "offset.sock_filter.jf" to Layouts.SOCK_FILTER_JF_OFFSET,
        "offset.sock_filter.k" to Layouts.SOCK_FILTER_K_OFFSET,
        "size.sock_fprog" to Layouts.SOCK_FPROG.byteSize(),
        "offset.sock_fprog.len" to Layouts.SOCK_FPROG_LEN_OFFSET,
        "offset.sock_fprog.filter" to Layouts.SOCK_FPROG_FILTER_OFFSET,
        "size.seccomp_data" to Layouts.SECCOMP_DATA.byteSize(),
        "offset.seccomp_data.nr" to Layouts.SECCOMP_DATA_NR_OFFSET,
        "offset.seccomp_data.arch" to Layouts.SECCOMP_DATA_ARCH_OFFSET,
        "offset.seccomp_data.args" to Layouts.SECCOMP_DATA_ARGS_OFFSET,
        "size.seccomp_notif" to Layouts.SECCOMP_NOTIF_SIZE,
        "offset.seccomp_notif.id" to Layouts.SECCOMP_NOTIF_ID_OFFSET,
        "offset.seccomp_notif.pid" to Layouts.SECCOMP_NOTIF_PID_OFFSET,
        "offset.seccomp_notif.flags" to Layouts.SECCOMP_NOTIF_FLAGS_OFFSET,
        "offset.seccomp_notif.data" to Layouts.SECCOMP_NOTIF_DATA_OFFSET,
        "size.seccomp_notif_resp" to Layouts.SECCOMP_NOTIF_RESP_SIZE,
        "offset.seccomp_notif_resp.id" to Layouts.SECCOMP_NOTIF_RESP_ID_OFFSET,
        "offset.seccomp_notif_resp.val" to Layouts.SECCOMP_NOTIF_RESP_VAL_OFFSET,
        "offset.seccomp_notif_resp.error" to Layouts.SECCOMP_NOTIF_RESP_ERROR_OFFSET,
        "offset.seccomp_notif_resp.flags" to Layouts.SECCOMP_NOTIF_RESP_FLAGS_OFFSET,
        "size.seccomp_notif_addfd" to Layouts.SECCOMP_NOTIF_ADDFD.byteSize(),
        "offset.seccomp_notif_addfd.id" to Layouts.SECCOMP_NOTIF_ADDFD_ID_OFFSET,
        "offset.seccomp_notif_addfd.flags" to Layouts.SECCOMP_NOTIF_ADDFD_FLAGS_OFFSET,
        "offset.seccomp_notif_addfd.srcfd" to Layouts.SECCOMP_NOTIF_ADDFD_SRCFD_OFFSET,
        "offset.seccomp_notif_addfd.newfd" to Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_OFFSET,
        "offset.seccomp_notif_addfd.newfd_flags" to Layouts.SECCOMP_NOTIF_ADDFD_NEWFD_FLAGS_OFFSET,
        "size.iovec" to Layouts.IOVEC.byteSize(),
        "offset.iovec.iov_base" to Layouts.IOVEC_BASE_OFFSET,
        "offset.iovec.iov_len" to Layouts.IOVEC_LEN_OFFSET,
        "size.msghdr" to Layouts.MSGHDR.byteSize(),
        "offset.msghdr.msg_name" to Layouts.MSGHDR_NAME_OFFSET,
        "offset.msghdr.msg_namelen" to Layouts.MSGHDR_NAMELEN_OFFSET,
        "offset.msghdr.msg_iov" to Layouts.MSGHDR_IOV_OFFSET,
        "offset.msghdr.msg_iovlen" to Layouts.MSGHDR_IOVLEN_OFFSET,
        "offset.msghdr.msg_control" to Layouts.MSGHDR_CONTROL_OFFSET,
        "offset.msghdr.msg_controllen" to Layouts.MSGHDR_CONTROLLEN_OFFSET,
        "offset.msghdr.msg_flags" to Layouts.MSGHDR_FLAGS_OFFSET,
        "size.cmsghdr" to Layouts.CMSGHDR.byteSize(),
        "offset.cmsghdr.cmsg_len" to Layouts.CMSGHDR_LEN_OFFSET,
        "offset.cmsghdr.cmsg_level" to Layouts.CMSGHDR_LEVEL_OFFSET,
        "offset.cmsghdr.cmsg_type" to Layouts.CMSGHDR_TYPE_OFFSET,
        "size.sockaddr_un" to Layouts.SOCKADDR_UN.byteSize(),
        "offset.sockaddr_un.sun_family" to Layouts.SOCKADDR_UN_FAMILY_OFFSET,
        "offset.sockaddr_un.sun_path" to Layouts.SOCKADDR_UN_PATH_OFFSET,
        "size.pollfd" to Layouts.POLLFD_SIZE,
        "offset.pollfd.fd" to Layouts.POLLFD_FD_OFFSET,
        "offset.pollfd.events" to Layouts.POLLFD_EVENTS_OFFSET,
        "offset.pollfd.revents" to Layouts.POLLFD_REVENTS_OFFSET,
        "size.landlock_ruleset_attr" to Layouts.LANDLOCK_RULESET_ATTR_SIZE,
        "offset.landlock_ruleset_attr.handled_access_fs" to Layouts.LANDLOCK_RULESET_ATTR_FS_OFFSET,
        "offset.landlock_ruleset_attr.handled_access_net" to Layouts.LANDLOCK_RULESET_ATTR_NET_OFFSET,
        "offset.landlock_ruleset_attr.scoped" to Layouts.LANDLOCK_RULESET_ATTR_SCOPED_OFFSET,
        "size.landlock_path_beneath_attr" to Layouts.LANDLOCK_PATH_BENEATH_ATTR.byteSize(),
        "offset.landlock_path_beneath_attr.allowed_access" to Layouts.LANDLOCK_PATH_BENEATH_ATTR_ACCESS_OFFSET,
        "offset.landlock_path_beneath_attr.parent_fd" to Layouts.LANDLOCK_PATH_BENEATH_ATTR_FD_OFFSET,
        "size.open_how" to Layouts.OPEN_HOW_SIZE,
        "offset.open_how.flags" to Layouts.OPEN_HOW_FLAGS_OFFSET,
        "offset.open_how.mode" to Layouts.OPEN_HOW_MODE_OFFSET,
        "offset.open_how.resolve" to Layouts.OPEN_HOW_RESOLVE_OFFSET,
    )
}
