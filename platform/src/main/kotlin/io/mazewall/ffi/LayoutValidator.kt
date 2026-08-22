package io.mazewall.ffi


import java.lang.foreign.MemoryLayout
import java.lang.foreign.StructLayout

/**
 * Validates FFM memory layout size and member offsets dynamically at runtime on initialization
 * to ensure they match target ABI expectations.
 */
@Suppress("MagicNumber")
object LayoutValidator {
    fun validate() {
        validateLayout(Layouts.SOCK_FILTER, expectedSize = 8, expectedAlignment = 4) {
            assertOffset("code", 0)
            assertOffset("jt", 2)
            assertOffset("jf", 3)
            assertOffset("k", 4)
        }

        validateLayout(Layouts.SOCK_FPROG, expectedSize = 16, expectedAlignment = 8) {
            assertOffset("len", 0)
            assertOffset("filter", 8)
        }

        validateLayout(Layouts.SECCOMP_DATA, expectedSize = 64, expectedAlignment = 8) {
            assertOffset("nr", 0)
            assertOffset("arch", 4)
            assertOffset("instruction_pointer", 8)
            assertOffset("args", 16)
        }

        validateLayout(Layouts.SECCOMP_NOTIF, expectedSize = 80, expectedAlignment = 8) {
            assertOffset("id", 0)
            assertOffset("pid", 8)
            assertOffset("flags", 12)
            assertOffset("data", 16)
        }

        validateLayout(Layouts.SECCOMP_NOTIF_RESP, expectedSize = 24, expectedAlignment = 8) {
            assertOffset("id", 0)
            assertOffset("val", 8)
            assertOffset("error", 16)
            assertOffset("flags", 20)
        }

        validateLayout(Layouts.SECCOMP_NOTIF_ADDFD, expectedSize = 24, expectedAlignment = 8) {
            assertOffset("id", 0)
            assertOffset("flags", 8)
            assertOffset("srcfd", 12)
            assertOffset("newfd", 16)
            assertOffset("newfd_flags", 20)
        }

        validateLayout(Layouts.IOVEC, expectedSize = 16, expectedAlignment = 8) {
            assertOffset("iov_base", 0)
            assertOffset("iov_len", 8)
        }

        validateLayout(Layouts.MSGHDR, expectedSize = 56, expectedAlignment = 8) {
            assertOffset("msg_name", 0)
            assertOffset("msg_namelen", 8)
            assertOffset("msg_iov", 16)
            assertOffset("msg_iovlen", 24)
            assertOffset("msg_control", 32)
            assertOffset("msg_controllen", 40)
            assertOffset("msg_flags", 48)
        }

        validateLayout(Layouts.CMSGHDR, expectedSize = 16, expectedAlignment = 8) {
            assertOffset("cmsg_len", 0)
            assertOffset("cmsg_level", 8)
            assertOffset("cmsg_type", 12)
        }

        validateLayout(Layouts.SOCKADDR_UN, expectedSize = 110, expectedAlignment = 2) {
            assertOffset("sun_family", 0)
            assertOffset("sun_path", 2)
        }

        validateLayout(Layouts.POLLFD, expectedSize = 8, expectedAlignment = 4) {
            assertOffset("fd", 0)
            assertOffset("events", 4)
            assertOffset("revents", 6)
        }

        validateLayout(Layouts.LANDLOCK_RULESET_ATTR, expectedSize = 16, expectedAlignment = 8) {
            assertOffset("handled_access_fs", 0)
            assertOffset("handled_access_net", 8)
        }

        validateLayout(Layouts.LANDLOCK_PATH_BENEATH_ATTR, expectedSize = 12, expectedAlignment = 1) {
            assertOffset("allowed_access", 0)
            assertOffset("parent_fd", 8)
        }

        validateLayout(Layouts.OPEN_HOW, expectedSize = 24, expectedAlignment = 8) {
            assertOffset("flags", 0)
            assertOffset("mode", 8)
            assertOffset("resolve", 16)
        }
    }

    internal fun validateLayout(
        layout: StructLayout,
        expectedSize: Long,
        expectedAlignment: Long,
        block: LayoutValidationScope.() -> Unit,
    ) {
        if (layout.byteSize() != expectedSize) {
            throw IllegalStateException("FFM StructLayout size mismatch for layout: expected size $expectedSize but got ${layout.byteSize()}")
        }
        if (layout.byteAlignment() != expectedAlignment) {
            throw IllegalStateException("FFM StructLayout alignment mismatch for layout: expected alignment $expectedAlignment but got ${layout.byteAlignment()}")
        }
        LayoutValidationScope(layout).block()
    }

    internal class LayoutValidationScope(private val layout: StructLayout) {
        @Suppress("TooGenericExceptionCaught")
        fun assertOffset(fieldName: String, expectedOffset: Long) {
            val actualOffset = try {
                layout.byteOffset(MemoryLayout.PathElement.groupElement(fieldName))
            } catch (e: Exception) {
                throw IllegalStateException("Field '$fieldName' not found in layout", e)
            }
            if (actualOffset != expectedOffset) {
                throw IllegalStateException("FFM StructLayout offset mismatch for field '$fieldName': expected offset $expectedOffset but got $actualOffset")
            }
        }
    }
}
