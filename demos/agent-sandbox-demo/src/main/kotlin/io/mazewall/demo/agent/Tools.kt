package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.Tool
import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.Arch
import io.mazewall.core.OpenFlags
import io.mazewall.core.RealSocketManager
import io.mazewall.core.Syscall
import io.mazewall.core.Tid
import io.mazewall.enforcer.api.ContainmentViolationEvidence
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.ScopingHandler
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.openPath
import io.mazewall.getFdOrThrow
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
) {
    // Thread pools representing separate agent compartments (Thread-Level Compartmentalization)
    private val webRawExecutor = Executors.newSingleThreadExecutor()
    private val fileRawExecutor = Executors.newSingleThreadExecutor()
    private val analysisRawExecutor = Executors.newSingleThreadExecutor()

    // 1. Web Agent Compartment: Allows network connections, but blocks private IPs (SSRF) and blocks process spawning
    private val networkPolicy = Policy.builder()
        .allowMmapExec()
        .allow(Syscall.CLONE, Syscall.CLONE3, Syscall.EXECVE, Syscall.EXECVEAT)
        .build()

    private val webScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.CONNECT to { tid, args, stack ->
                val address = args.firstOrNull() as? ByteArray
                when (address?.let(NetworkAddressClassifier::classify) ?: AddressDecision.DENY) {
                    AddressDecision.DENY -> {
                        AgentPolicyEvidence.record(tid, Syscall.CONNECT)
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
    private val filePolicy = Policy.builder()
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
    private val execPolicy = Policy.builder()
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
            if (tid != null && syscall != null) AgentPolicyEvidence.record(tid, syscall)
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
        return awaitOwned(fileExecutor.submit<String> {
            println("[TOOL] Analyzing file: $filename")
            val targetFile = File(workspaceDir, filename).canonicalFile
            println("[TOOL] Resolved file path: ${targetFile.absolutePath}")
            verifyNativeRead(targetFile)
            targetFile.readText()
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
        val tid = LinuxNative.process.gettid()
        AgentPolicyEvidence.clear(tid)
        return try {
            action()
        } catch (failure: Exception) {
            val denied = AgentPolicyEvidence.consume(tid)
            if (denied == null) throw failure
            throw authenticatedDenial(denied.syscall, failure)
        } finally {
            AgentPolicyEvidence.clear(tid)
        }
    }

    private fun verifyNativeRead(target: File) {
        NativeArena.ofConfined().use { arena ->
            val result = with(arena) {
                openPath(target.absolutePath, OpenFlags(NativeConstants.O_RDONLY or NativeConstants.O_CLOEXEC))
            }
            when (result) {
                is LinuxNative.SyscallResult.Success -> {
                    val descriptor = result.getFdOrThrow("agent read probe")
                    RealSocketManager.close(descriptor)
                }
                is LinuxNative.SyscallResult.Error -> {
                    if (result.errno == NativeConstants.EPERM || result.errno == NativeConstants.EACCES) {
                        throw authenticatedDenial(Syscall.OPEN, cause = null, errno = result.errno)
                    }
                    result.throwErrno("agent read probe")
                }
            }
        }
    }

    private fun authenticatedDenial(
        syscall: Syscall,
        cause: Throwable?,
        errno: Int = NativeConstants.EPERM,
    ): ContainmentViolationException =
        ContainmentViolationException(
            message = "Mazewall observed ${syscall.name} denial",
            cause = cause,
            errno = errno,
            syscallNr = syscall.numberFor(Arch.current()),
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )

    private companion object {
        val PROCESS_TIMEOUT: Duration = Duration.ofSeconds(30)
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

private object AgentPolicyEvidence {
    data class Denial(val syscall: Syscall)

    private val denials = ConcurrentHashMap<Tid, Denial>()

    fun record(
        tid: Tid,
        syscall: Syscall,
    ) {
        denials[tid] = Denial(syscall)
    }

    fun consume(tid: Tid): Denial? = denials.remove(tid)

    fun clear(tid: Tid) {
        denials.remove(tid)
    }
}
