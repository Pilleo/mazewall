package io.mazewall.core

import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SeccompActionTest {
    @Test
    fun `default ACT_ERRNO is a data-class instance`() {
        val deny = SeccompAction.ACT_ERRNO()
        assertIs<SeccompAction.ACT_ERRNO>(deny)
        assertEquals(NativeConstants.EPERM, deny.errno)
    }

    @Test
    fun `stricter keeps ERRNO over TRACE in both orders`() {
        val errno = SeccompAction.ACT_ERRNO()
        val trace = SeccompAction.ACT_TRACE(1)
        assertIs<SeccompAction.ACT_ERRNO>(errno.stricter(trace))
        assertIs<SeccompAction.ACT_ERRNO>(trace.stricter(errno))
    }

    @Test
    fun `stricter keeps custom errno over TRACE`() {
        val errno = SeccompAction.ACT_ERRNO(NativeConstants.EACCES)
        val trace = SeccompAction.ACT_TRACE(1)
        assertEquals(errno, trace.stricter(errno))
    }

    @Test
    fun `stricter does not use nativeCode`() {
        val errno = SeccompAction.ACT_ERRNO()
        val trace = SeccompAction.ACT_TRACE(1)
        assertTrue(errno.nativeCode < trace.nativeCode || errno.nativeCode > trace.nativeCode)
        assertTrue(errno.restrictivenessRank() > trace.restrictivenessRank())
        assertEquals(errno, SeccompAction.stricterOf(errno, trace))
        assertEquals(errno, SeccompAction.stricterOf(trace, errno))
    }
}
