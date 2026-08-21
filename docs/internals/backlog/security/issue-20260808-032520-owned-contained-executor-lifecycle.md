---
title: "Make Contained Executors Own Dedicated Worker Threads"
severity: "ENHANCEMENT"
status: "deferred"
priority: low
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
effort: "large"
autonomy: "supervised"
---

# ⚪ [Severity: ENHANCEMENT, deferred]: Make Contained Executors Own Dedicated Worker Threads

**Do not implement without a new explicit maintainer decision.** This item is `priority: low` and `deferred`. An owned `ContainedExecutorService` looks like a cleaner API, but it is a high-maintenance, high-risk product: we would own thread factories, shutdown, interruption, Loom ineligibility, classloader leaks, and a second lifecycle next to seccomp (which cannot be uninstalled). The current `wrap` model is honest about installing on whoever the caller already owns.

**Context:** `ContainedExecutors.wrap(delegate, policy)` accepts an arbitrary `ExecutorService`, returns the same broad interface, and leaves the caller holding the unrestricted delegate reference. Seccomp and Landlock restrictions remain on a worker after the submitted task completes. If the delegate is shared or reused directly, unrelated work can execute on permanently restricted threads. The API neither communicates ownership transfer nor exposes a contained marker type. The target semantics are specified in `docs/internals/designs/api/enforcer-public-api.md`.

**Needed:** Not scheduled. If revisited, require a written threat-vs-maintenance review first. Then, and only then: introduce an owned `ContainedExecutorService` factory that creates dedicated platform threads, exposes its effective policy/status, and terminates those workers on close. Rename any retained wrapping operation to `adoptDedicatedExecutor`, document ownership transfer, and reject known common/shared or virtual-thread executors. Add integration tests proving that raw delegate reuse is unavailable through the primary API, all workers install containment before user code, shutdown terminates restricted workers, and installation failure prevents task execution.
