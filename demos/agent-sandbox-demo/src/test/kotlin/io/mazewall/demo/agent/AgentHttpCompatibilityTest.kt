package io.mazewall.demo.agent

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.ScopingHandler
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.Supervision
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentHttpCompatibilityTest {
    @Test
    fun `installed former HttpClient establishes the same acquired response contract`() {
        HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build().use { client ->
            val fetch: (String) -> String = { url ->
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(2)).GET().build()
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofString()).body()
                } catch (failure: IOException) {
                    throw ExecutionException(failure)
                }
            }
            assertStatuses(fetch)
            assertCharsets(fetch)
            assertHeaderDeadline(fetch)
            assertDelayedBody(fetch)
        }
    }

    @Test
    fun `comparison returns error and redirect bodies without changing status semantics`() {
        comparison { fetch -> assertStatuses(fetch) }
    }

    @Test
    fun `comparison honors declared charset and falls back to UTF8`() {
        comparison { fetch -> assertCharsets(fetch) }
    }

    @Test
    fun `comparison response deadline rejects trickled headers within overall two second budget`() {
        comparison { fetch -> assertHeaderDeadline(fetch) }
    }

    @Test
    fun `comparison permits body delays after timely response headers`() {
        comparison { fetch -> assertDelayedBody(fetch) }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `protected synchronous HTTP preserves local response compatibility with actual custom CONNECT`() {
        IsolatedProcessTester.runIsolatedMethod(javaClass.name, "protectedHttpBody")
    }

    @Suppress("unused")
    fun protectedHttpBody() {
        Supervision.prepareSupervisor()
        val timeout = java.time.Duration.ofSeconds(10)
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
        val allowed = AtomicInteger()
        val denied = AtomicInteger()
        val scoping = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(Syscall.CONNECT to { _, _, stack ->
                val approved = stack.any { it.className == AgentHttp::class.java.name && it.methodName == "fetch" }
                if (approved) allowed.incrementAndGet() else denied.incrementAndGet()
                approved
            })
        }
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, timeout, timeout).use { runtime ->
            val domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), DomainLimits(1, 1), scoping)
            val worker = AtomicReference<Thread>()
            val fetch: (String) -> String = { url ->
                val invocation = runtime.submit(domain, Callable {
                    val current = Thread.currentThread()
                    assertTrue(worker.compareAndSet(null, current) || worker.get() === current)
                    AgentHttp.fetch(url)
                })
                try {
                    invocation.result().toCompletableFuture().get(10, TimeUnit.SECONDS)
                } finally {
                    assertIs<ExecutionEnd.Finished>(invocation.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS))
                }
            }
            assertStatuses(fetch)
            assertCharsets(fetch)
            assertHeaderDeadline(fetch)
            assertDelayedBody(fetch)
            assertEquals(10, allowed.get(), "every acquired HTTP connection must reach the actual custom callback")
            LocalHttpFixture().use { server ->
                val refusal = runtime.submit(domain, Callable { Socket(InetAddress.getLoopbackAddress(), server.port).use { error("unapproved connect succeeded") } })
                assertFailsWith<ExecutionException> { refusal.result().toCompletableFuture().get(5, TimeUnit.SECONDS) }
                refusal.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            assertEquals(1, denied.get())
            assertEquals(DrainReport.Complete, runtime.drain(timeout))
            assertFalse(worker.get().isAlive)
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == "agent-http-response-deadline" }, "deadline observers must physically retire")
        }
    }

    private fun comparison(action: ((String) -> String) -> Unit) {
        AgentDemoFixture.create().use { fixture ->
            AgentTools(null, fixture.workspace).use { tools -> action(tools::fetchWebpage) }
        }
    }

    private fun assertStatuses(fetch: (String) -> String) {
        for (status in listOf(200, 301, 404, 500)) {
            LocalHttpFixture().use { server ->
                server.serve { socket -> server.response(socket, status, "status-$status") }
                assertEquals("status-$status", fetch(server.url))
            }
        }
    }

    private fun assertCharsets(fetch: (String) -> String) {
        for ((type, encoding) in listOf(
            "text/plain; charset=ISO-8859-1" to Charset.forName("ISO-8859-1"),
            "text/plain; CHARSET=\"ISO-8859-1\"" to Charset.forName("ISO-8859-1"),
            "text/plain; charset=not-a-supported-charset" to StandardCharsets.UTF_8,
            "text/plain" to StandardCharsets.UTF_8,
        )) {
            LocalHttpFixture().use { server ->
                server.serve { socket -> server.response(socket, 200, "café", type, encoding) }
                assertEquals("café", fetch(server.url))
            }
        }
    }

    private fun assertHeaderDeadline(fetch: (String) -> String) {
        LocalHttpFixture(allowPeerClose = true).use { server ->
            server.serve { socket ->
                val output = socket.getOutputStream()
                for (part in listOf("HTTP/1.1 200 OK\r\n", "Content-Type: text/plain\r\n", "Content-Length: 2\r\n", "Connection: close\r\n", "\r\nok")) {
                    output.write(part.toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    server.delay(600)
                }
            }
            val start = System.nanoTime()
            val failure = assertFailsWith<ExecutionException> { fetch(server.url) }
            assertTrue(failure.cause is HttpTimeoutException, failure.toString())
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsed in 1500..3000, "response timeout took $elapsed ms")
        }
    }

    private fun assertDelayedBody(fetch: (String) -> String) {
        LocalHttpFixture().use { server ->
            server.serve { socket ->
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                server.delay(2500)
                output.write("slow-".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                server.delay(2500)
                output.write("body".toByteArray(StandardCharsets.US_ASCII))
            }
            assertEquals("slow-body", fetch(server.url))
        }
    }
}

private class LocalHttpFixture(private val allowPeerClose: Boolean = false) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
    private val accepted = AtomicReference<Socket>()
    private val stopped = CountDownLatch(1)
    private val failure = AtomicReference<Throwable>()
    private var worker: Thread? = null
    val port: Int get() = server.localPort
    val url = "http://127.0.0.1:${server.localPort}/fixture"

    fun serve(action: (Socket) -> Unit) {
        worker = Thread.ofPlatform().name("agent-local-http-fixture").start {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    assertTrue(input.readLine().startsWith("GET /fixture HTTP/"))
                    while (input.readLine().isNotEmpty()) { /* consume acquired request headers */ }
                    action(socket)
                }
            } catch (problem: IOException) {
                if (!allowPeerClose && stopped.count != 0L) failure.set(problem)
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
    }

    fun delay(millis: Long) {
        stopped.await(millis, TimeUnit.MILLISECONDS)
    }

    fun response(socket: Socket, status: Int, body: String, type: String = "text/plain; charset=UTF-8", encoding: Charset = StandardCharsets.UTF_8) {
        val bytes = body.toByteArray(encoding)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status Fixture\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
    }

    override fun close() {
        stopped.countDown()
        accepted.get()?.close()
        server.close()
        worker?.join(5000)
        assertFalse(worker?.isAlive == true, "acquired fixture thread must retire")
        failure.get()?.let { throw it }
    }
}
