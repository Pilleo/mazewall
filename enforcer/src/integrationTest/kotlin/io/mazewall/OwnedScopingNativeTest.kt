package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Supervision
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedScopingNativeTest : BaseIntegrationTest() {
    @Test
    fun `quality first scoping phase awaits execution end before denial`() {
        val type = OwnedScopingNativeTest::class.java
        val resource = "/" + type.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(type.getResourceAsStream(resource)).use { it.readAllBytes() }
        val model = java.lang.classfile.ClassFile
            .of()
            .parse(bytes)
        val captured = model.methods().filter {
            it.methodName().stringValue() == "verifyCapturedScoping" && it.methodType().stringValue() == "()V"
        }
        assertEquals(1, captured.size, "The actual captured-scoping entry must be inspected")
        val calls = captured
            .single()
            .code()
            .orElseThrow()
            .elementList()
            .filterIsInstance<java.lang.classfile.instruction.InvokeInstruction>()
        val handleOwner = "io/mazewall/enforcer/api/execution/InvocationHandle"
        val stageType = "()Ljava/util/concurrent/CompletionStage;"
        val resultCall = calls.indexOfFirst {
            it.owner().asInternalName() == handleOwner && it.name().stringValue() == "result" && it.type().stringValue() == stageType
        }
        val endCalls = calls.withIndex().filter { (_, call) ->
            call.owner().asInternalName() == handleOwner && call.name().stringValue() == "executionEnd" && call.type().stringValue() == stageType
        }
        assertEquals(1, endCalls.size, "The first invocation must observe execution end before denial submission")
        val endCall = endCalls.single().index
        val timedEndGet = calls
            .withIndex()
            .firstOrNull { (index, call) ->
                index > endCall &&
                    call.owner().asInternalName() == "java/util/concurrent/CompletableFuture" &&
                    call.name().stringValue() == "get" &&
                    call.type().stringValue() == "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;"
            }?.index ?: -1
        val denialPhase = calls.indexOfFirst {
            it.owner().asInternalName() == "io/mazewall/enforcer/api/execution/OwnedScopingNativeTest" &&
                it.name().stringValue() == "verifyDenialAndFatal"
        }
        assertTrue(resultCall >= 0 && resultCall < endCall, "Result delivery must precede the original invocation's end wait")
        assertTrue(endCall < timedEndGet && timedEndGet < denialPhase, "The bounded end wait must precede denial submission")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `captured custom authority permits exact stack and denies lookalike on real owned worker`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "verifyCapturedScoping")
    }

    fun verifyCapturedScoping() {
        Supervision.prepareSupervisor()
        val timeout = Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).block(Syscall.PTRACE).build()
        val caller = Thread.currentThread()
        val reads = AtomicInteger()
        val allowed = AtomicInteger()
        val denied = AtomicInteger()
        val handlers = mutableMapOf<Syscall, ScopingHandler>(
            Syscall.CONNECT to { _, _, stack ->
            val authorized = stack.any { it.className == javaClass.name && it.methodName == "connectAuthorized" }
            if (authorized) allowed.incrementAndGet() else denied.incrementAndGet()
            authorized
        },
        )
        val scoping = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler>
                get() {
                    assertTrue(Thread.currentThread() === caller)
                    reads.incrementAndGet()
                    return handlers
                }
        }
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
            val domain = runtime.prepare(policy, DomainLimits(1, 1), scoping)
            handlers[Syscall.CONNECT] = { _, _, _ -> error("caller mutation must never replace captured authority") }
            assertEquals(1, reads.get())
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 2000
                val first = runtime.submit(
                    domain,
                    Callable {
                        connectAuthorized(server.localPort)
                        Thread.currentThread()
                    },
                )
                val worker = first.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertTrue(worker !== caller && !worker.isVirtual)
                server.accept().use { assertEquals(42, it.getInputStream().read()) }
                assertEquals(1, allowed.get())
                // Result delivery alone does not release this domain's single admission.
                assertNull(assertIs<ExecutionEnd.Finished>(first.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure)
                verifyDenialAndFatal(runtime, domain, server.localPort, denied)
                assertEquals(DrainReport.Complete, runtime.drain(timeout))
                assertFalse(worker.isAlive)
            }
        }
    }

    private fun verifyDenialAndFatal(
        runtime: ManagedRuntime,
        domain: InProcessDomain,
        port: Int,
        denied: AtomicInteger,
    ) {
        val nativeCause = AtomicReference<IOException>()
        val refused = runtime.submit(
            domain,
            Callable {
            try {
                connectAuthorizedLookalike(port)
            } catch (failure: IOException) {
                nativeCause.set(failure)
                throw failure
            }
        },
        )
        val delivery = assertFailsWith<ExecutionException> { refused.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause
        assertTrue(assertIs<ContainmentViolationException>(delivery).cause === nativeCause.get())
        assertEquals(1, denied.get())
        assertTrue(assertIs<ExecutionEnd.Finished>(refused.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === delivery)
        val fatal = LinkageError("supervised owned fatal")
        val failed = runtime.submit(domain, Callable<Int> { throw fatal })
        assertTrue(assertFailsWith<ExecutionException> { failed.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }.cause === fatal)
        assertTrue(assertIs<ExecutionEnd.Finished>(failed.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)).failure === fatal)
        assertFailsWith<java.util.concurrent.RejectedExecutionException> { runtime.submit(domain, Callable { error("retired body entered") }) }
    }

    private fun connectAuthorized(port: Int) = connect(port)

    private fun connectAuthorizedLookalike(port: Int) = connect(port)

    private fun connect(port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000)
            socket.getOutputStream().write(42)
        }
    }
}
