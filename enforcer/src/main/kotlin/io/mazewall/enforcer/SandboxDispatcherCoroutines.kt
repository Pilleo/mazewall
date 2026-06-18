package io.mazewall.enforcer

import io.mazewall.Policy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Extension method to execute a suspending [block] under the given [policy].
 * 
 * Under the hood, this routes the execution to the [SandboxDispatcher]'s elastic 
 * thread pool specific to this policy, preventing thread poisoning while allowing
 * non-blocking context switching.
 */
suspend fun <T> SandboxDispatcher.executeSuspend(policy: Policy<*, *>, block: suspend () -> T): T {
    val dispatcher = policy.asDispatcher()
    return withContext(dispatcher) {
        block()
    }
}

/**
 * Converts a [Policy] into a Kotlin [CoroutineDispatcher] that is backed by 
 * the [SandboxDispatcher]'s elastic thread pool for this specific policy.
 */
fun Policy<*, *>.asDispatcher(): CoroutineDispatcher {
    val executor = SandboxDispatcher.getOrCreateElasticPool(this.definition)
    return executor.asCoroutineDispatcher()
}
