---
title: "Preserve fatal failures during Portal declared-exception reconstruction"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "portal-runtime"
target_modules: [":portals:runtime", ":portals:codegen"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalDeclaredExceptionMapper.kt"
target_symbols:
  - "rethrow"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve fatal failures during Portal declared-exception reconstruction

**Context:**
Repository-wide review, 2026-11-04: [PCG-10](../../reviews/2026-11-04-repository-review-findings.md#pcg-10). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · NEW freshly reproduced defect · high confidence · Portal generated-error
path/runtime helper.**

- **Source/trigger:** The generated catch calls `PortalDeclaredExceptionMapper.rethrow`
  (`PortalStubGenerator.kt:145–169`). In
  `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalDeclaredExceptionMapper.kt:22–30`,
  `runCatching/recoverCatching/getOrNull` treats any constructor failure as a reason to
  try the next constructor or return the original remote error. If a declared
  exception's public message/no-argument constructor throws an `AssertionError`,
  reflection wraps it, and both failures disappear.
- **Consequence:** A fatal **host** failure becomes an ordinary completed remote
  `PortalRemoteCallException`, with no retained cause/suppressed evidence. The
  stateful gate's `catch (Error)` loss path
  (`portals/runtime/src/main/kotlin/io/mazewall/portal/PortalStatefulInvocationGate.kt:45–47`)
  cannot see it: `portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt:67–73`
  wraps the generated delegate in that gate
  and deliberately unwraps the outer reflective invocation. This is trusted
  application constructor code, not arbitrary remote
  class loading; the fixed class allow-list remains sound.
- **Fresh evidence:** Both allowed constructors threw the same known `AssertionError`
  object; the mapper called both, then rethrew the original remote exception with no
  cause/suppressed evidence (`additional-probe.log:1`). Stateful-gate consequences
  are connected-source evidence, not a newly executed stateful/native call.
- **Smallest complete correction:** Fall back only for absent/unsupported constructors;
  deliberately unwrap invocation-target failures and propagate their actual Throwable,
  preserving fatal identity and remote-call evidence. Do not use a broad catch that
  retries another user constructor after a fatal outcome.
- **Regression/qualification:** Add message/no-arg fallback controls and a constructor
  throwing a known Error object. Require the same Error identity, no later constructor
  invocation and retained remote context; cover a generated declared-exception call
  through the existing stateful gate. Current mapper tests only cover ordinary
  construction and an undeclared remote type
  (`portals/runtime/src/test/kotlin/io/mazewall/portal/PortalDeclaredExceptionMapperTest.kt:15–43`).
- **Mapping/dependency:** No specific existing issue found. It is adjacent to CG05's
  trusted-host-failure distinction, not a duplicate of its checkout-order bug.
  Fix in this shared helper before P03/P07/P13 claim complete generated
  exception/fatal-state semantics; no new API or dependency is necessary.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Updated `PortalDeclaredExceptionMapper` so `(String)` and `()` constructor lookup falls back only on `NoSuchMethodException` / non-invocation reflective failures, while `InvocationTargetException` unwraps and rethrows the constructor's actual `Throwable` with `remote` attached in `suppressed`; verified with `PortalStatefulInvocationGate` in `PortalDeclaredExceptionMapperTest`.

---
<!-- id: issue-20261103-235254-619-preserve-fatal-failures-during-portal-declared-exc  file: issue-20261103-235254-619-preserve-fatal-failures-during-portal-declared-exc.md -->
