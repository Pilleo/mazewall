package io.mazewall.enforcer

import io.mazewall.enforcer.state.threadLocal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class ThreadLocalDelegateTest {

    private var stringValue by threadLocal { "default" }
    private var nullableValue: String? by threadLocal { null }

    @Test
    fun `test thread local delegation default value`() {
        assertEquals("default", stringValue)
        assertNull(nullableValue)
    }

    @Test
    fun `test thread local delegation local mutation`() {
        stringValue = "mutated"
        assertEquals("mutated", stringValue)

        nullableValue = "not-null"
        assertEquals("not-null", nullableValue)
    }

    @Test
    fun `test thread local delegation thread isolation`() {
        stringValue = "main-thread-value"
        nullableValue = "main-nullable"

        val latch = CountDownLatch(1)
        var childValue: String? = null
        var childNullable: String? = "uninitialized"

        thread {
            try {
                childValue = stringValue
                childNullable = nullableValue

                stringValue = "child-thread-value"
                nullableValue = "child-nullable"
            } finally {
                latch.countDown()
            }
        }

        latch.await()

        // Verify child thread read the defaults first
        assertEquals("default", childValue)
        assertNull(childNullable)

        // Verify main thread values are unaffected by child thread mutation
        assertEquals("main-thread-value", stringValue)
        assertEquals("main-nullable", nullableValue)
    }
}
