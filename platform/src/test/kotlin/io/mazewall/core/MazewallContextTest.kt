package io.mazewall.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class MazewallContextTest {

    private val http = ContextId(1u)
    private val pdfParse = ContextId(2u)
    private val yamlParse = ContextId(3u)

    @Test
    fun `fresh thread starts at UNKNOWN`() {
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    @Test
    fun `context is visible inside the scope and returned value passes through`() {
        val result = MazewallContext.withContext(pdfParse) {
            assertEquals(pdfParse, MazewallContext.current())
            "payload"
        }
        assertEquals("payload", result)
    }

    @Test
    fun `previous context is restored after normal return`() {
        MazewallContext.withContext(http) {
            MazewallContext.withContext(pdfParse) { }
            assertEquals(http, MazewallContext.current())
        }
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    @Test
    fun `exception inside scope still restores previous context`() {
        MazewallContext.withContext(http) {
            val thrown = assertThrows(ScopeException::class.java) {
                MazewallContext.withContext(yamlParse) {
                    throw ScopeException("boom")
                }
            }
            assertEquals("boom", thrown.message)
            assertEquals(http, MazewallContext.current())
        }
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    @Test
    fun `nested scopes restore innermost first`() {
        MazewallContext.withContext(http) {
            assertEquals(http, MazewallContext.current())
            MazewallContext.withContext(pdfParse) {
                assertEquals(pdfParse, MazewallContext.current())
                MazewallContext.withContext(yamlParse) {
                    assertEquals(yamlParse, MazewallContext.current())
                }
                assertEquals(pdfParse, MazewallContext.current())
            }
            assertEquals(http, MazewallContext.current())
        }
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    @Test
    fun `re-entering the same context is a valid nested no-op`() {
        MazewallContext.withContext(pdfParse) {
            MazewallContext.withContext(pdfParse) {
                assertEquals(pdfParse, MazewallContext.current())
            }
            assertEquals(pdfParse, MazewallContext.current())
        }
    }

    @Test
    fun `two platform threads never observe each other's context`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)

        val t1 = Thread {
            MazewallContext.withContext(pdfParse) {
                entered.countDown()
                release.await()
                if (MazewallContext.current() != pdfParse) {
                    throw AssertionError("t1 lost its context")
                }
            }
        }
        val t2 = Thread {
            MazewallContext.withContext(yamlParse) {
                entered.countDown()
                release.await()
                if (MazewallContext.current() != yamlParse) {
                    throw AssertionError("t2 saw a foreign context")
                }
            }
        }
        t1.start()
        t2.start()
        entered.await()
        release.countDown()
        t1.join()
        t2.join()

        assertFalse(t1.isAlive && t2.isAlive)
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    @Test
    fun `one hundred threads updating contexts concurrently remain isolated`() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

        repeat(100) { threadIndex ->
            val own = ContextId(((threadIndex % 253) + 1).toUInt())
            Thread {
                try {
                    start.await()
                    repeat(50) { iteration ->
                        MazewallContext.withContext(own) {
                            if (MazewallContext.current() != own) {
                                failures.add("thread=$threadIndex iteration=$iteration lost own context")
                            }
                            MazewallContext.withContext(pdfParse) {
                                if (MazewallContext.current() != pdfParse) {
                                    failures.add("thread=$threadIndex iteration=$iteration inner scope corrupted")
                                }
                            }
                            if (MazewallContext.current() != own) {
                                failures.add("thread=$threadIndex iteration=$iteration restore failed")
                            }
                        }
                        if (MazewallContext.current() != ContextId.UNKNOWN) {
                            failures.add("thread=$threadIndex leaked context after scope exit")
                        }
                    }
                } catch (t: Throwable) {
                    failures.add("thread=$threadIndex threw $t")
                } finally {
                    done.countDown()
                }
            }.apply { start() }
        }

        start.countDown()
        done.await()

        assertTrue(failures.isEmpty(), "isolation violations: ${failures.take(10)}")
    }

    @Test
    fun `virtual thread invocation fails closed without changing any state`() {
        val outcome = AtomicReference<Throwable?>()
        val observed = AtomicReference<ContextId?>()
        val finished = CountDownLatch(1)

        Thread.ofVirtual().name("tier-e-vprobe").start {
            try {
                MazewallContext.withContext(pdfParse) { }
            } catch (t: Throwable) {
                outcome.set(t)
            } finally {
                observed.set(MazewallContext.current())
                finished.countDown()
            }
        }
        finished.await()

        assertTrue(outcome.get() is IllegalStateException)
        assertEquals(ContextId.UNKNOWN, observed.get())
        assertEquals(ContextId.UNKNOWN, MazewallContext.current())
    }

    private class ScopeException(message: String) : RuntimeException(message)
}
