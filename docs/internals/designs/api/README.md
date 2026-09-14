---
title: "Public API Design"
scope: "enforcer | profiler"
critical_syscalls: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
keywords: ["public-api", "usability", "lifecycle", "java-interop"]
---

# Public API Design

This directory records the target public API for moving Mazewall from a proof of concept to a production JVM library. The governing rule is: irreversible kernel behavior must be visible in types, names, ownership, diagnostics, and examples.

## Documents

| Document | Scope |
|---|---|
| [Enforcer Public API](enforcer-public-api.md) | Policy construction, executor ownership, installation and diagnostics |
| [Profiler Public API](profiler-public-api.md) | Profiling sessions, strategy selection, confidence and behavioral output |
| [Migration Plan](migration-plan.md) | Additive facade, deprecations, compatibility and validation |

## Design Principles

1. The short happy path must also be the safe path.
2. Thread and process scope must remain compile-time distinguishable.
3. Irreversible containment must never look lexically scoped or reversible.
4. Contained worker threads must have explicit ownership.
5. Runtime compatibility must be a named choice, not a hidden Boolean combination.
6. Installation must be preceded by inspectable preflight diagnostics.
7. Fail-closed behavior must be visible at configuration and installation sites.
8. Profiling output is evidence with declared coverage, never an “exact” proof.
9. Low-level FFM, BPF and daemon machinery is not part of the application API.
10. Kotlin and Java must each have an intentional entry point.

## Binary Compatibility Gate

`:platform`, `:enforcer`, and `:profiler` keep Kotlin ABI signatures in their
respective `api/*.api` files. `checkKotlinAbi` verifies that the compiled public
binary contract still matches its reviewed signature; `updateKotlinAbi` is the
explicit review action that changes the signature. The normal `check` lifecycle
includes this verification.

The supported contract consists of configuration, policies, contained-executor
operations, installation assessments and receipts, diagnostics and violations,
and profiler sessions and results. Types below `ffi`, daemon transport, native
memory, and internal profiler packages are implementation details unless a
separate published SPI names them. A native component receives a captured
`NativeEngine` from its assembly boundary rather than taking a fresh global
snapshot during an operation.
