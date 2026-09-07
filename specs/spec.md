# MAZ-1175 — Typed Landlock installation state machine

## Problem

Landlock installation currently interleaves lifecycle-state mutation with native ruleset creation, rule configuration, and restriction calls. That makes the irreversible kernel boundary harder to unit-test deterministically and violates the project convention that state transitions are pure evaluation and native work is explicit effects.

## Users and context

Library users need the existing Landlock containment behavior and public outcomes to remain stable while maintainers gain a typed, mock-testable installation lifecycle. This work operates at the Linux/JVM kernel boundary, where a partial installation must remain accurately reported and permission failures must fail closed.

## In scope

- Refactor the internal Landlock ruleset installation lifecycle into typed events, states, and explicit effects.
- Keep native Landlock operations in an effect interpreter that reports their results back to the evaluator.
- Preserve current containment entry points, Landlock-before-Seccomp sequencing, fallback semantics, FFM ownership, and errno/error behavior.
- Add focused mock-native unit coverage for the installation transition and failure matrix.

## Out of scope

- Changing public APIs, policy semantics, fallback configuration, or adding dependencies.
- Changing Seccomp filter construction, install ordering, or virtual-thread policy.
- Moving containment-registry updates into installation effects; that dependent follow-up is MAZ-1176.
- Adding new Landlock access rights, ABI support, process-wide semantics, or kernel-only test prerequisites unless the mock interpreter cannot cover an affected path.

## Acceptance criteria

1. The internal Landlock installation lifecycle has typed installation events and a pure `evaluate(state, event)` transition that returns the next typed state plus explicit effects; its evaluator performs no native calls and `LandlockSession.state` changes only from an evaluator result. Verify with focused `:enforcer` unit tests, including a structural assertion or equivalent direct evaluator test.
2. The effect protocol represents creation, rule addition, self-restriction, and owned ruleset-FD closure explicitly; the interpreter performs those native operations through the established safe native/FFM boundary and feeds each success or failure back as an event. Verify using `MockNativeEngine` without creating a real kernel ruleset FD.
3. For mocked failures of `landlock_create_ruleset`, rule addition, and `landlock_restrict_self`, the resulting typed outcome retains the operation and errno/cause needed for current failure translation; `EPERM` and `EACCES` are never reported as success or silently bypassed. Verify through focused failure-mode unit tests under the default fail-closed configuration and the existing explicit operator bypass path.
4. A successful interpreter run yields the same externally observable Landlock-applied result and an unsuccessful run preserves the current `Rejected`/explicitly configured `Bypassed` behavior. Existing public containment entry points and receipt semantics remain source- and behavior-compatible. Verify with existing receipt/installation tests plus new state-machine tests.
5. `ContainedExecutors.installInternal` still completes the Landlock phase before invoking Seccomp installation on every applicable path; no refactor may reverse or bypass that ordering. Verify with a focused ordering regression test or an existing test extended to observe the two phases.
6. Ruleset descriptors are closed exactly once by the component that owns them on success and failure paths; the implementation continues to use confined native allocation and captures native error state at the call boundary before later native work can overwrite it. Verify through mock-native lifecycle tests that exercise each terminal path.
7. `./gradlew :enforcer:test` passes. Run kernel integration verification only if a changed interpreter path cannot be covered by mock-native tests; if run, `./gradlew integrationTest` also passes.

## Contract handoff

- `scope_paths`:
  - `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt`
  - `enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt`
  - `enforcer/src/main/kotlin/io/mazewall/landlock/LandlockApplyResult.kt`
  - `enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt`
  - `enforcer/src/test/kotlin/io/mazewall/landlock/**`
  - `enforcer/src/test/kotlin/io/mazewall/enforcer/**`
  - `docs/internals/backlog/code_health/issue-20260907-080955-split-landlock-install-into-evaluate-plus-kernel-effects.md`
- `contracts`:
  - `contract:landlock.install.lifecycle` — changes: internal event/state/effect lifecycle for Landlock installation.
  - `contract:containment.landlock-before-seccomp` — consumes: ordering boundary from containment installation to Seccomp installation.
  - `contract:landlock.apply.outcome` — preserves: `Applied`, `Rejected`, and explicitly configured `Bypassed` outcome semantics.
- `dependencies`: none. MAZ-1176 is a downstream consumer of this requirement’s typed effect boundary.
- `dod.test_command`: `./gradlew :enforcer:test`

## Delivery recommendation

Use the full Design phase. This is an internal, irreversible kernel-containment lifecycle refactor whose effects, FD ownership, failure translation, and ordering boundary must be designed before implementation.
