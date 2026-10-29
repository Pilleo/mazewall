package demo.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import demo.documents.DocumentOutlineService
import demo.documents.MarkdownOutline
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalServiceId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded application measurements, not a generic benchmark or a production load qualification. */
object ApplicationMeasurements {
    private enum class Mode(
        val id: String,
    ) {
        READER_DIRECT("reader-direct"),
        READER_THREAD("reader-thread"),
        OUTLINE_DIRECT("outline-direct"),
        OUTLINE_PORTAL("outline-portal"),
    }

    private const val TEXT = "# Launch é🙂\n## Plan\nUseful synthetic document."
    private const val OUTLINE = "Launch é🙂\nPlan"

    @JvmStatic fun main(args: Array<String>) =
        runBlocking {
        require(args.size == 1 && !Thread.currentThread().isVirtual)
        val mode = requireNotNull(Mode.entries.singleOrNull { it.id == args[0] })
        val start = System.nanoTime()
        val root = Files.createTempDirectory("mazewall-app-measurement-")
        try {
            val documents = Files.createDirectory(root.resolve("documents"))
            Files.writeString(documents.resolve("note.md"), TEXT)
            val readArgs = Json.encodeToString(ReadDocumentArguments("note.md"))
            val outlineArgs = Json.encodeToString(OutlineArguments(TEXT))
            lateinit var measured: JsonObject
            var pressure: JsonElement = JsonNull
            var drainStart = 0L
            var drainNs = 0L
            when (mode) {
                Mode.READER_DIRECT -> {
                    ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT)
                    val tool = object : Tool<ReadDocumentArguments, String>(typeToken<ReadDocumentArguments>(), typeToken<String>(), "read_document", "Ordinary reader baseline") {
                        override suspend fun execute(args: ReadDocumentArguments): String {
                            validateReadArguments(args)
                            return DocumentReader.readBoundedDocument(documents.resolve(args.path))
                        }
                    }
                    measured = samples(mode.id, start, tool, readArgs, TEXT)
                }
                Mode.READER_THREAD -> {
                    lateinit var worker: Thread
                    val budget = Duration.ofSeconds(10)
                    ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, Policy.NO_EXEC_HOTSPOT, budget, budget).use { runtime ->
                        val domain = runtime.prepare(
                            WorkerPolicyBuilder
                            .denyList(RuntimeProfile.HOTSPOT_JIT)
                            .allowJvmClasspath()
                            .allowFsRead(documents)
                            .block(Syscall.SOCKET)
                            .build(),
                                DomainLimits(1, 4),
                        )
                        worker = runtime
                            .submit(domain, Callable { Thread.currentThread() })
                            .result()
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS)
                        measured = samples(mode.id, start, documentReadTool(runtime, domain, documents), readArgs, TEXT)
                        val gate = Gate()
                        pressure = pressure(gate, TEXT) {
                            runtime.submit(
                                domain,
                                Callable {
                            gate.enter()
                            DocumentReader.readBoundedDocument(documents.resolve("note.md"))
                        },
                            )
                        }
                        drainStart = System.nanoTime()
                    }
                    worker.join(5000)
                    check(!worker.isAlive && !worker.isVirtual)
                    drainNs = System.nanoTime() - drainStart
                }
                Mode.OUTLINE_DIRECT -> {
                    installPortalHostBaseline()
                    val ordinary = MarkdownOutline()
                    val tool = object : Tool<OutlineArguments, String>(typeToken<OutlineArguments>(), typeToken<String>(), "outline_document", "Ordinary outline baseline") {
                        override suspend fun execute(args: OutlineArguments): String = ordinary.outline(args.markdown)
                    }
                    measured = samples(mode.id, start, tool, outlineArgs, OUTLINE)
                }
                Mode.OUTLINE_PORTAL -> {
                    installPortalHostBaseline()
                    lateinit var worker: ProcessHandle
                    PortalRuntime.open(outlineConfiguration(root.resolve("worker-cache"))).use { runtime ->
                        val tool = outlineTool(runtime)
                        val pid = runtime
                            .health()
                            .single { it.isolationGroup == "document-outline" }
                            .workerPids
                            .single()
                        worker = ProcessHandle.of(pid).orElseThrow()
                        measured = samples(mode.id, start, tool, outlineArgs, OUTLINE, pid)
                        val gate = Gate()
                        val operation = runtime.prepare(
                            DocumentOutlineService::class.java,
                            PortalServiceId("document-outline"),
                            PortalOperationCall<DocumentOutlineService, String, String> { service, text ->
                                gate.enter()
                                service.outline(text)
                            },
                        )
                        pressure = pressure(gate, OUTLINE) { operation.submit(TEXT) }
                        drainStart = System.nanoTime()
                    }
                    check(!worker.isAlive)
                    drainNs = System.nanoTime() - drainStart
                }
            }
            println(
                "PITCH_METRICS " +
                buildJsonObject {
                measured.forEach { (key, value) -> put(key, value) }
                put("pressure", pressure)
                put("drainNs", drainNs)
                put("retirement", if (mode == Mode.READER_DIRECT || mode == Mode.OUTLINE_DIRECT) "not-applicable" else "physical")
            },
            )
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private suspend fun samples(
        mode: String,
        start: Long,
        tool: Tool<*, *>,
        arguments: String,
        expected: String,
        childPid: Long? = null,
    ): JsonObject {
        val setupNs = System.nanoTime() - start
        repeat(20) { runScriptedAgent(listOf(tool), listOf(ScriptStep(MessagePart.Tool.Call("warmup-$it", tool.name, arguments), expected))) }
        val before = resources(ProcessHandle.current().pid())
        val child = childPid?.let(::resources) ?: JsonNull
        val samples = LongArray(100) { index ->
            val began = System.nanoTime()
            runScriptedAgent(listOf(tool), listOf(ScriptStep(MessagePart.Tool.Call("sample-$index", tool.name, arguments), expected)))
            System.nanoTime() - began
        }
        check(samples.all { it > 0 })
        val sorted = samples.sorted()
        return buildJsonObject {
            put("schema", 1)
            put("mode", mode)
            put("pid", ProcessHandle.current().pid())
            put("java", System.getProperty("java.runtime.version"))
            put("arch", System.getProperty("os.arch"))
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            put("warmups", 20)
            put("samples", Json.parseToJsonElement(samples.joinToString(prefix = "[", postfix = "]")))
            put("setupNs", setupNs)
            put("medianNs", (sorted[49] + sorted[50]) / 2)
            put("p95Ns", sorted[94])
            put("resourcesBefore", before)
            put("resourcesAfter", resources(ProcessHandle.current().pid()))
            put("childResources", child)
            put("scope", "full offline AIAgent construction/run/close per operation; successful verified outputs only; no live provider")
        }
    }

    private fun resources(pid: Long): JsonObject {
        val status = Files.readString(Path.of("/proc/$pid/status"))
        return buildJsonObject {
            put("pid", pid)
            put("rssKiB", Regex("VmRSS:\\s+(\\d+)").find(status)!!.groupValues[1].toLong())
            put("threads", Regex("Threads:\\s+(\\d+)").find(status)!!.groupValues[1].toInt())
            if (pid == ProcessHandle.current().pid()) put("hostHeapBytes", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
        }
    }

    private class Gate {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun enter() {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun pressure(
        gate: Gate,
        expected: String,
        submit: () -> InvocationHandle<String>,
    ): JsonObject {
        val accepted = mutableListOf<InvocationHandle<String>>()
        try {
            accepted.add(submit())
            check(gate.entered.await(5, TimeUnit.SECONDS))
            repeat(3) { accepted.add(submit()) }
            val began = System.nanoTime()
            try {
                val rejected = submit()
                try {
                    rejected.result().toCompletableFuture().get(5, TimeUnit.SECONDS)
                    error("Overload unexpectedly succeeded")
                } catch (
                    _: ExecutionException,
                ) {
                    check(rejected.executionEnd().toCompletableFuture().get(5, TimeUnit.SECONDS) is ExecutionEnd.NotStarted)
                }
            } catch (_: RejectedExecutionException) {
                // Canonical thread admission rejects synchronously.
            }
            val rejectionNs = System.nanoTime() - began
            gate.release.countDown()
            accepted.forEach {
                check(it.result().toCompletableFuture().get(10, TimeUnit.SECONDS) == expected)
                it.executionEnd().toCompletableFuture().get(10, TimeUnit.SECONDS)
            }
            check(gate.calls.get() == 4)
            return buildJsonObject {
                put("accepted", 4)
                put("rejected", 1)
                put("rejectionNs", rejectionNs)
                put("scope", "separate gated admission control; Portal gate is host mapping, not guest compute")
            }
        } finally {
            gate.release.countDown()
        }
    }
}
