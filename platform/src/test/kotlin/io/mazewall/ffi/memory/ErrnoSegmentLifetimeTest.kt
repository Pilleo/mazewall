package io.mazewall.ffi.memory

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ErrnoSegmentLifetimeTest {
    @Test
    fun `virtual-thread errno state becomes inaccessible after callback`() {
        var leaked: MemorySegment? = null
        var failure: Throwable? = null
        val worker = Thread.ofVirtual().start {
            leaked = ErrnoSegment.withCapturedState { it.segment }
            failure = runCatching { requireNotNull(leaked).get(ValueLayout.JAVA_BYTE, 0) }.exceptionOrNull()
        }
        worker.join()

        assertFailsWith<IllegalStateException> { throw checkNotNull(failure) }
    }

    @Test
    fun `carrier-thread errno state is reused`() {
        val first = ErrnoSegment.withCapturedState { it.segment }
        val second = ErrnoSegment.withCapturedState { it.segment }

        assertSame(first, second)
    }
}
