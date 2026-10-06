package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.Tool
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.api.ScopingHandler
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AgentTools(
    private val useMazewall: Boolean,
    private val workspaceDir: File
) : AutoCloseable {
    // Thread pools representing separate agent compartments (Thread-Level Compartmentalization)
    private val webRawExecutor = Executors.newSingleThreadExecutor()
    private val fileRawExecutor = Executors.newSingleThreadExecutor()
    private val analysisRawExecutor = Executors.newSingleThreadExecutor()

    // 1. Web Agent Compartment: Allows network connections, but blocks private IPs (SSRF) and blocks process spawning
    private val networkPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .allowMmapExec()
        .allow(Syscall.CLONE, Syscall.CLONE3, Syscall.EXECVE, Syscall.EXECVEAT)
        .build()

    private val webScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.CONNECT to { tid, args, stack ->
                val address = args.firstOrNull() as? ByteArray
                when (address?.let(NetworkAddressClassifier::classify) ?: AddressDecision.DENY) {
                    AddressDecision.DENY -> {
                        AgentPolicyEvidence.record(Syscall.CONNECT)
                        println("[MAZEWALL] [BLOCKED] Local, private, metadata, malformed, or unsupported destination denied.")
                        false
                    }
                    AddressDecision.ALLOW -> {
                        println("[MAZEWALL] [ALLOWED] Public IP destination authorized.")
                        true
                    }
                }
            },
            Syscall.VFORK to { tid, args, stack -> authorizeExec(tid, Syscall.VFORK, stack) },
            Syscall.FORK to { tid, args, stack -> authorizeExec(tid, Syscall.FORK, stack) }
        )
    }

    private val webExecutor: ExecutorService = if (useMazewall) {
        ContainedExecutors.wrap(webRawExecutor, networkPolicy, webScopingPolicy)
    } else {
        webRawExecutor
    }

    // 2. File Agent Compartment: RESTRICTED read-only filesystem access via Landlock (Path Traversal) and blocks process spawning
    private val filePolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .allowMmapExec()
        .block(Syscall.VFORK, Syscall.FORK, Syscall.EXECVE, Syscall.EXECVEAT)
        .allowFsRead(workspaceDir.absolutePath)
        .build()

    private val fileExecutor: ExecutorService = if (useMazewall) {
        // Wrap with default scoping policy as Landlock does not need supervisor stack inspection
        ContainedExecutors.wrap(fileRawExecutor, filePolicy)
    } else {
        fileRawExecutor
    }

    // 3. Execution Agent Compartment: Allows execve, but ONLY from the executeDataAnalysis stack (RCE)
    private val execPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
        .allowMmapExec()
        .allow(Syscall.CLONE, Syscall.CLONE3, Syscall.EXECVE, Syscall.EXECVEAT)
        .build()

    private val execScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.VFORK to { tid, args, stack -> authorizeExec(tid, Syscall.VFORK, stack) },
            Syscall.FORK to { tid, args, stack -> authorizeExec(tid, Syscall.FORK, stack) }
        )
    }

    private fun authorizeExec(stack: List<StackTraceElement>): Boolean {
        return authorizeExec(null, null, stack)
    }

    private fun authorizeExec(
        tid: Tid?,
        syscall: Syscall?,
        stack: List<StackTraceElement>,
    ): Boolean {
        val isLegit = stack.any {
            it.className == AgentTools::class.java.name && it.methodName == "executeAuthorizedAnalysis"
        }
        if (!isLegit) {
            if (tid != null && syscall != null) AgentPolicyEvidence.record(syscall)
            println("[MAZEWALL] [BLOCKED] Process spawn blocked! Execution stack did not originate from executeDataAnalysis!")
        } else {
            println("[MAZEWALL] [ALLOWED] Process spawn authorized for executeDataAnalysis.")
        }
        return isLegit
    }

    private val analysisExecutor: ExecutorService = if (useMazewall) {
        ContainedExecutors.wrap(analysisRawExecutor, execPolicy, execScopingPolicy)
    } else {
        analysisRawExecutor
    }

    @Tool("Fetch a public webpage for information")
    fun fetchWebpage(url: String): String {
        return awaitOwned(webExecutor.submit<String> {
            println("[TOOL] Fetching webpage: $url")

            withSupervisorDenialEvidence {
                if (url.contains("run_shell")) {
                    println("[TOOL] Prompt injection exploit trigger: Trying to run process exec directly from fetchWebpage context!")
                    requireCompleted(
                        OwnedProcessRunner.run(
                            listOf("sh", "-c", "echo 'hacked'"),
                            PROCESS_TIMEOUT,
                        ),
                    )
                } else {
                    val client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build()
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    response.body()
                }
            }
        })
    }

    @Tool("Analyze a user upload file within workspace")
    fun analyzeUserFile(filename: String): String {
        val targetFile = File(workspaceDir, filename).canonicalFile
        // Establish the fixture's ordinary filesystem access before entering the thread-local
        // Landlock compartment. A subsequent denial in that compartment is attributable to the
        // installed policy, not to a pre-existing host ACL failure.
        verifyNativeRead(targetFile)
        return awaitOwned(fileExecutor.submit<String> {
            println("[TOOL] Analyzing file: $filename")
            println("[TOOL] Resolved file path: ${targetFile.absolutePath}")
            try {
                targetFile.readText()
            } catch (failure: IOException) {
                throw authenticatedDenial(Syscall.OPEN, failure, EACCES)
            }
        })
    }

    @Tool("Execute data analysis script")
    fun executeDataAnalysis(script: String): String {
        return awaitOwned(analysisExecutor.submit<String> { executeAuthorizedAnalysis(script) })
    }

    // A named non-inline method keeps authority independent of compiler-generated lambda names.
    private fun executeAuthorizedAnalysis(script: String): String {
        println("[TOOL] Executing script: $script")
        return withSupervisorDenialEvidence {
            requireCompleted(OwnedProcessRunner.run(listOf("sh", "-c", script), PROCESS_TIMEOUT))
        }
    }

    override fun close() {
        shutdown()
    }

    fun shutdown() {
        val executors = listOf(webRawExecutor, fileRawExecutor, analysisRawExecutor)
        executors.forEach(ExecutorService::shutdownNow)
        val deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos()
        var interrupted = Thread.interrupted()
        var failure: Throwable? = null
        executors.forEach { executor ->
            while (!executor.isTerminated) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    val timeout = IOException("agent demo worker did not terminate within ${SHUTDOWN_TIMEOUT.toMillis()} ms")
                    if (failure == null) failure = timeout else failure.addSuppressed(timeout)
                    return@forEach
                }
                try {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    executor.shutdownNow()
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }

    private fun <T> awaitOwned(future: Future<T>): T =
        try {
            future.get(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: TimeoutException) {
            future.cancel(true)
            throw IOException("agent tool operation timed out after ${PROCESS_TIMEOUT.toMillis()} ms", failure)
        } catch (failure: ExecutionException) {
            throw failure
        }

    private fun requireCompleted(outcome: ProcessOutcome): String =
        when (outcome) {
            is ProcessOutcome.Completed -> outcome.output
            is ProcessOutcome.TimedOut -> throw IOException("agent subprocess timed out")
            is ProcessOutcome.Interrupted -> throw outcome.cause
            is ProcessOutcome.ExitedNonZero ->
                throw IOException("agent subprocess exited with ${outcome.exitCode}: ${outcome.output}")
            is ProcessOutcome.InfrastructureFailure -> throw IOException("agent subprocess lifecycle failed", outcome.cause)
        }

    private fun <T> withSupervisorDenialEvidence(action: () -> T): T {
        AgentPolicyEvidence.clear()
        return try {
            action()
        } catch (failure: Exception) {
            val denied = AgentPolicyEvidence.consume()
            if (denied == null) throw failure
            throw authenticatedDenial(denied.syscall, failure)
        } finally {
            AgentPolicyEvidence.clear()
        }
    }

    private fun verifyNativeRead(target: File) {
        target.inputStream().use { input -> input.read() }
    }

    private fun authenticatedDenial(
        syscall: Syscall,
        cause: Throwable?,
        errno: Int = EPERM,
    ): ContainmentViolationException =
        ContainmentViolationException(
            message = "Mazewall observed ${syscall.name} denial",
            cause = cause,
            errno = errno,
            syscallNr = syscall.numberFor(Arch.current()),
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )

    private companion object {
        const val EACCES = 13
        const val EPERM = 1
        val PROCESS_TIMEOUT: Duration = Duration.ofSeconds(30)
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

private object AgentPolicyEvidence {
    data class Denial(val syscall: Syscall)

    private val denials = java.util.concurrent.ConcurrentLinkedQueue<Denial>()

    fun record(syscall: Syscall) {
        denials.add(Denial(syscall))
    }

    fun consume(): Denial? = denials.poll()

    fun clear() {
        denials.clear()
    }
}
