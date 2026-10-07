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

**Deferred — lowest priority.** An owned `ContainedExecutorService` (issue 032520) is intentionally not scheduled. Owning the pool duplicates JVM thread lifecycle, shutdown, and Loom-rejection work on top of irreversible kernel filters. Prefer documenting `wrap` ownership over building a second executor product.

If that direction is ever approved again, the primary API would create dedicated workers and return a marker type:

```kotlin
val parserPool: ContainedExecutorService = mazewall.executors.fixed(
    threads = 4,
    policy = parserPolicy,
)
```

If adoption of an existing executor remains supported, the operation must be named `adoptDedicatedExecutor`, transfer lifecycle ownership, and prominently reject shared/common pools.

### Irreversible Installation

`Policy.install()` returns an `InstallationReceipt` that is `AutoCloseable` solely to own any supervisor-registration lease. `receipt.use { ... }` releases that registration deterministically; it never removes Seccomp or Landlock restrictions. The receipt does not expose the underlying lease, so callers cannot accidentally retain or duplicate it.

All installation entry points return this receipt. The former hidden `Unit` overloads were
deleted in the `0.1.0-SNAPSHOT` cutover, including binary-only compatibility declarations;
Kotlin code scopes a receipt with `use`, and Java code uses try-with-resources.

Contained executor task entry requires `receipt.requireInstalled()` before the
callback runs. The check rejects incomplete installation and missing requested
filesystem enforcement, independently of the configured manual-install fallback.
The wrapper stores the receipt before checking it so its `finally` block retains
registration cleanup ownership when validation rejects entry. Applied Landlock or
Seccomp state is never rewound by the wrapper.

An installation attempt captures fallback selection before native/capability
work and passes it through platform handling, CET setup, and the Landlock session.
Changing a property during a native callback cannot change the same attempt's
failure handling or receipt metadata. Standalone Landlock sessions capture their
own fallback at construction. Receipts retain a `progress` snapshot of newly
applied Seccomp/Landlock stages and a `failure`; required unpack rethrows that
failure rather than replacing its cause chain with a generic bypass error.
These deltas do not describe inherited restrictions or complete process-wide coverage.

CET requests now require `CetOutcome.VERIFIED_CURRENT_THREAD`: SHSTK already enabled
by a compatible runtime/loader, a native LOCK result exactly zero, and verified
enabled status. The library never late-enables SHSTK across a normal FFM call-return
boundary and never promotes lock `EPERM`/`EACCES` to proof. Process-wide requests
are rejected before irreversible work. Retained proof is bound to the exact native
backend and platform thread; it is not inherited from process registry state and
does not own a supervisor registration. A successful new lock sets
`InstallationProgress.cetLocked` before later verification/diagnostic stages.

Reported installation failures and bypasses latch the first failure in the immutable
thread/process registry. Future installation entry is rejected with that original
failure before probing, planning, or additional native work. Applied-policy cache
coverage and stronger engine-state evidence cannot clear quarantine. Late native
publication preserves both its newly applied state and the terminal failure.
Business-task failure after successful installation does not change this latch.
Thread-scoped failure does not block preparation on a fresh worker created from an
unaffected manager context; process-wide failure blocks every subsequent installation
scope in that process. The caller owns physical executor shutdown; owned generation
retirement/recreation remains part of the managed-runtime work.

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

Implemented construction (6 October 2026): `ProcessPolicyBuilder` and
`WorkerPolicyBuilder` expose `denyList(runtime)` and `allowList(runtime)` factories.
Both retain mutable configuration and freeze the definition in the returned policy.
Only the worker builder exposes filesystem grants and explicit `FileSystemPolicy`
intent (`Unrestricted`, `DenyAll`, `AllowResources`). Assigning Boolean argument
settings is genuine pre-install assignment. The Java-only builder is removed and
definition-level configuration is internal. The legacy nested `Policy.Builder`
and its `builder()`/`threadLocalBuilder()` factories are removed after migrating
production, demo, host-test, kernel-test, and compiler-fixture callers.
The former `PolicyLists`, `DenyListSpec`, `AllowListSpec`, and forwarding
`Policy.denyList`/`allowList`/`threadLocalDenyList` entry points are removed after
migrating their callers to fixed-scope construction. Capability recipes use
`ProcessPolicies` templates; direct syscall actions use the canonical builder.

Normal users should choose an explicit allow-list or deny-list DSL instead of manipulating `defaultAction`, `allow`, `block`, and `unblock` together. Capability-level methods should cover process creation, networking, executable memory and filesystem access. Raw syscalls remain available under `advanced`.

Policy composition must use names such as `restrictFurtherWith` or `intersection`. Generic `combine` and `+` conceal the different Seccomp and Landlock composition rules and must not imply that installed permissions can expand.

### Canonical policy artifacts

`CanonicalPolicyArtifact` version 1 preserves fixed process/worker scope, explicit
runtime, parameterized actions, argument flags, filesystem intent and diagnostic
options. Active empty filesystem restriction is encoded as `DENY_ALL`, not as
unrestricted state. Exact read/write paths remain exact and decoded permission
collections are immutable. Process artifacts cannot carry worker filesystem state.

Parsing rejects unknown/missing fields, versions/actions/scopes, duplicate fields,
syscalls/resources, invalid action data and contradictory filesystem state. The format
accepts canonical JSON token spelling with optional formatting whitespace; resource
paths are absolute. The existing compiler consumes the decoded definition. Artifact
bytes do not grant review approval, provenance trust or a resource-generation binding.

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
