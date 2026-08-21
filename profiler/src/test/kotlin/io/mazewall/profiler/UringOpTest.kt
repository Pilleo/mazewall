package io.mazewall.profiler

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UringOpTest {
    @Test
    fun `OPENAT is Open not a filesystem mutation`() {
        val op = UringOp.parse("IORING_OP_OPENAT")
        assertIs<UringOp.Open>(op)
        assertTrue(!op.isFilesystemMutation())
        assertIs<FsEffect.UnknownOpenMode>(FsEffect.ofUring(op, listOf("/tmp/a")))
    }

    @Test
    fun `OPENAT2 and OPEN parse as Open`() {
        assertIs<UringOp.Open>(UringOp.parse("IORING_OP_OPENAT2"))
        assertIs<UringOp.Open>(UringOp.parse("open"))
    }

    @Test
    fun `WRITE and LINKAT are writes`() {
        assertIs<FsEffect.Write>(FsEffect.ofUring(UringOp.parse("IORING_OP_WRITE"), listOf("/t")))
        assertIs<FsEffect.Write>(FsEffect.ofUring(UringOp.parse("IORING_OP_LINKAT"), listOf("/a")))
        assertIs<FsEffect.Write>(FsEffect.ofUring(UringOp.parse("IORING_OP_SYMLINKAT"), listOf("/a")))
    }

    @Test
    fun `CONNECT is unenforceable not a path grant`() {
        val effect = FsEffect.ofUring(UringOp.parse("IORING_OP_CONNECT"), listOf("/run/sock"))
        assertIs<FsEffect.Unenforceable>(effect)
    }
}
