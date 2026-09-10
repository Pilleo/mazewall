package io.mazewall.demo.agent

import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal enum class ExpectedAgentResult {
    ALLOWED,
    DENIED,
}

internal sealed interface AgentOutcome {
    data class Completed(val detail: String) : AgentOutcome

    data class Denied(val detail: String) : AgentOutcome

    data class Inconclusive(val detail: String) : AgentOutcome

    data class InfrastructureFailure(val cause: Throwable) : AgentOutcome
}

internal data class AgentScenarioResult(
    val scenario: String,
    val expected: ExpectedAgentResult,
    val outcome: AgentOutcome,
) {
    val passed: Boolean
        get() = when (expected) {
            ExpectedAgentResult.ALLOWED -> outcome is AgentOutcome.Completed
            ExpectedAgentResult.DENIED -> outcome is AgentOutcome.Denied
        }

    fun render(): String {
        val status = if (passed) "PASS" else "FAIL"
        val detail = when (outcome) {
            is AgentOutcome.Inconclusive -> "INFRASTRUCTURE FAILURE (INCONCLUSIVE): ${outcome.detail}"
            is AgentOutcome.InfrastructureFailure ->
                "INFRASTRUCTURE FAILURE: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}"
            is AgentOutcome.Completed -> when (expected) {
                ExpectedAgentResult.ALLOWED -> "LEGITIMATE WORKLOAD SUCCEEDED: ${outcome.detail}"
                ExpectedAgentResult.DENIED -> "UNEXPECTED SUCCESS: ${outcome.detail}"
            }
            is AgentOutcome.Denied -> when (expected) {
                ExpectedAgentResult.ALLOWED -> "LEGITIMATE WORKLOAD FAILURE: unexpected denial (${outcome.detail})"
                ExpectedAgentResult.DENIED -> "EXPECTED DENIAL: ${outcome.detail}"
            }
        }
        return "[$status] $scenario (expected ${expected.name}): $detail"
    }
}

internal fun classifyToolOutcome(result: Result<String>): AgentOutcome =
    result.fold(
        onSuccess = { AgentOutcome.Completed(it) },
        onFailure = { failure ->
            rethrowFatalOrRestoreInterruption(failure)
            val diagnostic = ContainmentViolationDetector.diagnose(failure)
            when (diagnostic?.evidence) {
                io.mazewall.enforcer.api.ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL ->
                    AgentOutcome.Denied(diagnostic.evidence.name)
                io.mazewall.enforcer.api.ContainmentViolationEvidence.INFERRED_PERMISSION_FAILURE,
                io.mazewall.enforcer.api.ContainmentViolationEvidence.UNKNOWN,
                -> AgentOutcome.Inconclusive(diagnostic.evidence.name)
                null -> AgentOutcome.InfrastructureFailure(failure)
            }
        },
    )

internal inline fun <T> captureRecoverable(action: () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (failure: Exception) {
        rethrowFatalOrRestoreInterruption(failure)
        Result.failure(failure)
    }

/** Executor/reflection transport wrappers do not change the failure's JVM semantics. */
internal fun rethrowFatalOrRestoreInterruption(failure: Throwable) {
    var current = failure
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (seen.add(current)) {
        when (current) {
            is Error -> throw current
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                return
            }
            is java.util.concurrent.ExecutionException,
            is java.util.concurrent.CompletionException,
            is java.lang.reflect.InvocationTargetException,
            -> current = current.cause ?: return
            else -> return
        }
    }
}

internal fun runScenarioBoundary(
    action: () -> AgentOutcome,
    cleanup: () -> Unit,
): AgentOutcome {
    var primary: Throwable? = null
    return try {
        action().also { if (it is AgentOutcome.InfrastructureFailure) primary = it.cause }
    } catch (failure: Exception) {
        primary = failure
        AgentOutcome.InfrastructureFailure(failure)
    } catch (fatal: Error) {
        primary = fatal
        throw fatal
    } finally {
        try {
            cleanup()
        } catch (failure: Throwable) {
            val original = primary
            if (original == null) throw failure
            if (failure is Error && original !is Error) {
                failure.addSuppressed(original)
                throw failure
            }
            if (failure !== original) original.addSuppressed(failure)
        }
    }
}

internal class AgentDemoFixture private constructor(
    val root: File,
    val workspace: File,
    val traversalTarget: File,
) : AutoCloseable {
    override fun close() {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    companion object {
        fun create(): AgentDemoFixture {
            val root = Files.createTempDirectory("mazewall-agent-demo-").toFile().canonicalFile
            root.setReadable(false, false)
            root.setWritable(false, false)
            root.setExecutable(false, false)
            root.setReadable(true, true)
            root.setWritable(true, true)
            root.setExecutable(true, true)
            val workspace = File(root, "workspace").apply {
                check(mkdir()) { "could not create private demo workspace" }
                setReadable(true, true)
                setWritable(true, true)
                setExecutable(true, true)
            }
            File(workspace, "secret.txt").writeText("SUPER_SECRET_WORKSPACE_TOKEN_12345")
            val target = File(root, "passwd").apply {
                writeText("root:x:0:0:root:/root:/bin/bash\n")
            }
            return AgentDemoFixture(root, workspace, target)
        }
    }
}
