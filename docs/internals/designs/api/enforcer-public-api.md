---
title: "Enforcer Public API Target"
scope: "enforcer"
critical_syscalls: ["seccomp", "prctl", "landlock_restrict_self"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
keywords: ["public-api", "policy-dsl", "executor-ownership", "installation"]
---

# Enforcer Public API Target

## Current Strengths

The current Kotlin happy path is compact, uses the familiar `ExecutorService` abstraction, keeps policies immutable, distinguishes `ProcessWideSafe` from `ThreadLocalOnly`, rejects virtual-thread installation, and fails closed by default.

## Confirmed Usability and Safety Gaps

### Executor Ownership

`ContainedExecutors.wrap(delegate, policy)` accepts an arbitrary executor while the caller retains the raw delegate. Seccomp and Landlock remain on worker threads after a task completes. Reusing the delegate elsewhere can therefore expose unrelated work to permanent restrictions.

The primary API should create and own dedicated workers and return a marker type:

```kotlin
val parserPool: ContainedExecutorService = mazewall.executors.fixed(
    threads = 4,
    policy = parserPolicy,
)
```

If adoption of an existing executor remains supported, the operation must be named `adoptDedicatedExecutor`, transfer lifecycle ownership, and prominently reject shared/common pools.

### Irreversible Installation

`Policy.install()` currently returns `AutoCloseable`, which makes containment appear reversible through `use {}`. Closing may release supervisor resources but cannot remove kernel restrictions. Installation should instead return a non-closeable `InstallationReceipt`. Any closeable supervisor resource must have a separate, precise type.

### Runtime-Compatible Process Baselines

Preset names do not reveal implicit argument filters. Application-facing APIs should select a runtime profile and explicit capabilities:

```kotlin
val baseline = ProcessPolicy {
    runtime = RuntimeProfile.HOTSPOT_JIT
    denyProcessCreation()
}
```

`RuntimeProfile.NATIVE_IMAGE` may permit a stricter executable-memory policy when verified compatible. Raw switches such as `allowMmapExec()` belong in an advanced API.

### Policy Semantics

Normal users should choose an explicit allow-list or deny-list DSL instead of manipulating `defaultAction`, `allow`, `block`, and `unblock` together. Capability-level methods should cover process creation, networking, executable memory and filesystem access. Raw syscalls remain available under `advanced`.

Policy composition must use names such as `restrictFurtherWith` or `intersection`. Generic `combine` and `+` conceal the different Seccomp and Landlock composition rules and must not imply that installed permissions can expand.

### Preflight and Installation Results

Before an irreversible operation, callers need:

```kotlin
val assessment = mazewall.assess(ProcessScope, baseline)
assessment.requireInstallable()
val receipt = mazewall.installProcessBaseline(baseline)
```

The assessment records platform, kernel features, outer Seccomp constraints, Landlock ABI, runtime compatibility, virtual-thread restrictions, policy warnings and the expected effective policy. The receipt records what was actually installed. Failures use typed exceptions carrying the failed stage and native cause.

### Configuration Visibility

Failure behavior should be explicit at Mazewall initialization. Bypass modes remain opt-in and visibly unsafe; the primary API is fail closed. Global system properties may configure bootstrapping, but the effective configuration must be queryable and included in every assessment and receipt.

### Proposed Kotlin Shape

```kotlin
val mazewall = Mazewall.start {
    failureMode = FailureMode.FAIL_CLOSED
    processBaseline {
        runtime = RuntimeProfile.HOTSPOT_JIT
        denyProcessCreation()
    }
}

val parserPolicy = ThreadPolicy.denyList {
    denyNetwork()
    denyProcessCreation()
    filesystem {
        readOnly("/app/schemas")
        includeJvmClasspath()
    }
}

mazewall.executors.fixed(4, parserPolicy).use { pool ->
    pool.submit { parse(input) }
}
```

Closing the executor terminates its dedicated restricted workers; it does not claim to reverse containment.

## Public Surface Boundary

The supported application API should be small: `Mazewall`, process/thread policy DSLs, contained executors, assessment/receipt types, diagnostics and typed violations. Raw FFM arenas, native arguments, BPF builders, daemon engines, socket managers and installation internals should be `internal` or moved to an explicitly unstable low-level/SPI module.

## Java API

Java requires static factories, Java functional interfaces and conventional builders. Java callers must not need Kotlin `object.INSTANCE`, extension functions, star projections or policy state internals.
