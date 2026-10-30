package demo.koog

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OllamaLLMProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real packaged transport against an acquired loopback stub, never an inference server. */
internal object LiveClientDeadlineProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            received.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            exchange.close()
        }
        server.start()
        val failure = CompletableFuture<Throwable?>()
        val caller = Thread.ofPlatform().start {
            try {
                val executor = MultiLLMPromptExecutor(OllamaClient(baseUrl = "http://127.0.0.1:${server.address.port}"))
                val model = LLModel(OllamaLLMProvider, "loopback-stub-no-model", listOf(LLMCapability.Tools), 16_384, 1024)
                runBlocking { runObservedAgent(emptyList(), executor, model, "Synthetic local deadline test only", 24) }
                failure.complete(null)
            } catch (original: Throwable) {
                failure.complete(original)
            }
        }
        try {
            check(received.await(5, TimeUnit.SECONDS))
            check(failure.get(12, TimeUnit.SECONDS) is TimeoutCancellationException)
            check(received.count == 0L && release.count == 1L)
            println("LIVE_HTTP_CANCELLED before server release; no model or external provider")
        } finally {
            release.countDown()
            server.stop(0)
            caller.join(5000)
            check(!caller.isAlive)
        }
    }
}
