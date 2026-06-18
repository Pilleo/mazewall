# Implementation Plan: Functional Sandbox Dispatcher

## Objective
To provide a framework-agnostic, functional API for `mazewall` that allows developers to execute code blocks (lambdas) under specific security policies without manually managing thread pools or dealing with the "thread explosion" problem.

## Architecture

The solution will introduce a `SandboxDispatcher` object in the `:enforcer` module. It acts as a central execution router that:
1. Takes a `Policy` and a lambda.
2. Extracts the underlying immutable `PolicyDefinition`.
3. Looks up (or creates) a `ContainedExecutor` dedicated to that exact policy definition.
4. Submits the lambda to that executor and blocks for the result.

This ensures that multiple methods or components requesting the exact same policy (e.g., `Policy.NO_NETWORK`) share the same underlying OS thread pool, preventing thread explosion while maintaining strict, immutable thread-level containment.

## Proposed API

```kotlin
package io.mazewall.enforcer

object SandboxDispatcher {
    /**
     * Executes the given [block] on a thread pool strictly constrained by the given [policy].
     * Executors are cached based on the policy definition to prevent thread explosion.
     */
    fun <T> execute(policy: Policy<*, *>, block: () -> T): T

    /**
     * Shuts down all internally cached executors.
     */
    fun shutdownAll()
}
```

## Implementation Steps

### Phase 1: Core Implementation
1.  **Create `SandboxDispatcher.kt`:**
    *   Location: `enforcer/src/main/kotlin/io/mazewall/enforcer/SandboxDispatcher.kt`
    *   Implementation details:
        *   Internal state: `ConcurrentHashMap<PolicyDefinition<*>, ExecutorService>`
        *   Method `execute(Policy, block)` implementation.
        *   The default thread pool creation should probably use a cached thread pool or a dynamically sizing pool with a sensible maximum to handle varied blocking workloads. *Design decision needed: `Executors.newCachedThreadPool()` vs `Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())`*. A cached thread pool wrapped with containment is likely better for generic blocking I/O tasks typically submitted this way.
        *   Method `shutdownAll()` to iterate and call `shutdown()` on all cached executors.

### Phase 2: Refactoring `ContainedExecutors`
1.  **Expose `PolicyDefinition` mapping:**
    *   Ensure `ContainedExecutors.wrap` (or an internal equivalent) can be reliably called using the `PolicyDefinition` extracted from the `Policy` object passed to `SandboxDispatcher`.
    *   *Self-Correction*: `ContainedExecutors.wrap` currently accepts `vararg policies: Policy<*, Uncompiled>`. We need to ensure we can pass the combined policy cleanly.

### Phase 3: Coroutines Support (Optional but Recommended)
1.  **Coroutine Dispatcher Bridge:**
    *   To fully embrace Kotlin's functional ecosystem, provide a way to convert a Policy into a CoroutineDispatcher using the same cached underlying executor.
    *   API: `fun Policy.asDispatcher(): CoroutineDispatcher`
    *   This allows: `withContext(Policy.NO_NETWORK.asDispatcher()) { ... }`

### Phase 4: Testing
1.  **Unit Tests:**
    *   Verify that executing the same policy multiple times reuses the same executor instance (using mock executors or thread name inspection).
    *   Verify that executing different policies results in different executor instances.
    *   Verify `shutdownAll` logic.
2.  **Integration Tests:**
    *   Create a test that runs a system call (e.g., executing a process) via `SandboxDispatcher.execute(Policy.NO_EXEC) { ... }` and asserts that `ContainmentViolationException` is thrown.
    *   Run tests under the existing podman orchestration (`./scripts/run_tests.sh`).

### Phase 5: Documentation
1.  **Update `README.md`:**
    *   Introduce `SandboxDispatcher` as the recommended "Quick Start" approach for method-level isolation, deprecating the manual `ExecutorService` wrapping for average users.
2.  **Update `GETTING_STARTED.md`:**
    *   Provide recipes using `SandboxDispatcher.execute`.
3.  **Update Demos:**
    *   Modify `demos/vulnerable-web-app` to use the new `SandboxDispatcher` pattern instead of manually defining Spring `@Bean` proxies, demonstrating how much cleaner the integration becomes.

## Open Questions / Design Decisions
1.  **Executor Sizing:** What is the safest default executor to spawn per unique policy? `newCachedThreadPool` allows scaling for I/O bound tasks, but could lead to high OS thread counts if abused. `newFixedThreadPool` is safer but might bottleneck I/O operations. We may need to allow developers to configure the factory for the underlying pool.