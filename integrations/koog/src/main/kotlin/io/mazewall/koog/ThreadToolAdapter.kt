package io.mazewall.koog

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.TypeToken
import io.mazewall.enforcer.api.execution.ExecutionDiagnostics
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource
import io.mazewall.enforcer.api.execution.InProcessDomain
import io.mazewall.enforcer.api.execution.ManagedRuntime
import java.util.concurrent.Callable

/**
 * A host-selected Koog tool bound to one application-prepared in-process domain.
 *
 * Only the trusted synchronous action runs on the dedicated restricted platform
 * worker. Validation and coroutine delivery remain in the host; waiting occupies
 * one host IO thread per admitted call. Submission stays on the original caller
 * so ownership and nested-dispatch checks cannot be bypassed by a context hop.
 * Cancellation requests the original invocation, not physical termination. The
 * application retains runtime preparation, drain and close; no fallback or replay
 * is performed. This is not heap isolation or an arbitrary-code boundary.
 */
public class ThreadToolAdapter<A, R>(
    private val runtime: ManagedRuntime,
    private val domain: InProcessDomain,
    argsType: TypeToken,
    resultType: TypeToken,
    name: String,
    description: String,
    private val validateArguments: (A) -> Unit,
    private val validateResult: (R) -> Unit,
    private val action: (A) -> R,
) : Tool<A, R>(argsType, resultType, name, description),
    ExecutionDiagnosticsSource {
    override fun operationId(): String = name

    override fun diagnostics(): ExecutionDiagnostics = domain.diagnostics()

    override suspend fun execute(args: A): R {
        validateArguments(args)
        val result = awaitThreadInvocation { runtime.submit(domain, Callable { action(args) }) }
        validateResult(result)
        return result
    }
}
