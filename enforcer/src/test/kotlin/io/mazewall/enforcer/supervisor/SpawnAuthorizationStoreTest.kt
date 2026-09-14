package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnAuthorizationStoreTest {
    private val identity = ProcessIdentity(Pid(73_002), 17L)
    private val frame = StackTraceElement("loader", "module", "1", "Example", "spawn", "Example.kt", 1)

    @Test
    fun `authorization cannot be replayed`() {
        val store = SpawnAuthorizationStore()
        assertEquals(SpawnRegistrationResult.REGISTERED, store.register(identity, listOf(frame), 100L))
        assertEquals(listOf(frame), assertIs<SpawnAuthorizationResult.Authorized>(store.consume(identity, 101L)).stack)
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING), store.consume(identity, 102L))
    }

    @Test
    fun `stack frames and containers are defensive on ingress and egress`() {
        val store = SpawnAuthorizationStore()
        val stack = mutableListOf(frame)
        store.register(identity, stack, 100L)
        stack.clear()
        val result = assertIs<SpawnAuthorizationResult.Authorized>(store.consume(identity, 101L))
        assertEquals(listOf(frame), result.stack)
        assertNotSame(frame, result.stack.single())
        val firstRead = result.stack
        assertNotSame(firstRead, result.stack)
        assertNotSame(firstRead.single(), result.stack.single())
        (firstRead as MutableList).clear()
        assertEquals(listOf(frame), result.stack)
        val input = mutableListOf(frame)
        val constructed = SpawnAuthorizationResult.Authorized(input)
        input.clear()
        assertEquals(listOf(frame), constructed.stack)
    }

    @Test
    fun `ttl boundary permits one consume and expired authorization is removed`() {
        val store = SpawnAuthorizationStore()
        store.register(identity, listOf(frame), 100L)
        assertIs<SpawnAuthorizationResult.Authorized>(store.consume(identity, 10_000_000_100L))
        store.register(identity, listOf(frame), 100L)
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.EXPIRED), store.consume(identity, 10_000_000_101L))
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING), store.consume(identity, 101L))
    }

    @Test
    fun `same pid with different process start time cannot consume authorization`() {
        val store = SpawnAuthorizationStore()
        store.register(identity, listOf(frame), 100L)
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING), store.consume(identity.copy(startTimeTicks = 18L), 101L))
        assertIs<SpawnAuthorizationResult.Authorized>(store.consume(identity, 101L))
    }

    @Test
    fun `installation close clears authorization and prevents new registrations`() {
        val first = SupervisorRuntimeContext(processIdentityResolver = { identity })
        val second = SupervisorRuntimeContext(processIdentityResolver = { identity })
        first.registerSpawn(identity.pid, listOf(frame), 100L)
        second.registerSpawn(identity.pid, listOf(frame), 100L)
        first.close()
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.CLOSED), first.consumeSpawn(identity.pid, 101L))
        assertEquals(SpawnRegistrationResult.CLOSED, first.registerSpawn(identity.pid, listOf(frame), 101L))
        assertIs<SpawnAuthorizationResult.Authorized>(second.consumeSpawn(identity.pid, 101L))
        second.close()
    }

    @Test
    fun `last task lease discards unconsumed spawn authority without closing runtime`() {
        val runtime = SupervisorRuntimeContext(processIdentityResolver = { identity })
        val tid = Tid(identity.pid.value)
        runtime.register(tid)
        runtime.registerSpawn(identity.pid, listOf(frame), 100L)
        SupervisorSession(tid, runtime).close()
        runtime.register(tid)
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING), runtime.consumeSpawn(identity.pid, 101L))
        assertEquals(SpawnRegistrationResult.REGISTERED, runtime.registerSpawn(identity.pid, listOf(frame), 102L))
        runtime.close()
    }

    @Test
    fun `new session cannot consume another sessions authorization`() {
        val first = SupervisorRuntimeContext(processIdentityResolver = { identity })
        val second = SupervisorRuntimeContext(processIdentityResolver = { identity })
        first.registerSpawn(identity.pid, listOf(frame), 100L)
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.MISSING), second.consumeSpawn(identity.pid, 101L))
        assertIs<SpawnAuthorizationResult.Authorized>(first.consumeSpawn(identity.pid, 101L))
    }

    @Test
    fun `unresolvable identity explicitly fails closed with no pid fallback`() {
        var available = true
        val runtime = SupervisorRuntimeContext(processIdentityResolver = { if (available) identity else null })
        runtime.registerSpawn(identity.pid, listOf(frame), 100L)
        available = false
        assertEquals(SpawnAuthorizationResult.Rejected(SpawnRejection.IDENTITY_UNAVAILABLE), runtime.consumeSpawn(identity.pid, 101L))
        assertEquals(SpawnRegistrationResult.IDENTITY_UNAVAILABLE, runtime.registerSpawn(identity.pid, listOf(frame), 101L))
    }

    @Test
    fun `concurrent consumers get exactly one authorization`() {
        val store = SpawnAuthorizationStore()
        store.register(identity, listOf(frame), 100L)
        Executors.newFixedThreadPool(4).use { pool ->
            val results = pool.invokeAll(List(20) { java.util.concurrent.Callable { store.consume(identity, 101L) } })
            assertEquals(1, results.count { it.get() is SpawnAuthorizationResult.Authorized })
        }
    }

    @Test
    fun `stat parser finds start time after comm containing spaces and parentheses`() {
        val resolver = ProcProcessIdentityResolver { "73002 (worker ) odd (name)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 98765 0" }
        assertEquals(ProcessIdentity(Pid(73_002), 98765L), resolver.resolve(Pid(73_002)))
    }

    @Test
    fun `malformed mismatched and unreadable stat cannot resolve identity`() {
        for (stat in listOf(
                "",
                "73002 no comm",
                "73002 (short) S 1",
                "73003 (wrong pid) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 99",
                "73002 (bad time) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 -1",
            )) {
            assertNull(ProcProcessIdentityResolver { stat }.resolve(identity.pid))
        }
        assertNull(ProcProcessIdentityResolver { throw IOException("Permission denied") }.resolve(identity.pid))
        assertNull(ProcProcessIdentityResolver { throw SecurityException("denied") }.resolve(identity.pid))
    }

    @Test
    fun `real proc identity is stable for this live process`() {
        val pid = Pid(ProcessHandle.current().pid().toInt())
        val resolver = ProcProcessIdentityResolver()
        val actual = resolver.resolve(pid)
        assertEquals(pid, actual?.pid)
        assertTrue(actual!!.startTimeTicks >= 0)
        assertEquals(actual, resolver.resolve(pid))
    }
}
