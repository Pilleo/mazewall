---
title: "Policy.Builder Type-State Hole via Unchecked Casts"
severity: "HIGH"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
effort: "medium"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🔴 [Severity: HIGH]: Policy.Builder Type-State Hole via Unchecked Casts

**Context:** In `Policy.Builder` (enforcer/src/main/kotlin/io/mazewall/Policy.kt:255-283), the methods
`allowFsRead(path)`, `allowFsWrite(path)`, and `allowJvmClasspath()` mutate the builder's phantom scope
parameter by casting the *same mutable instance*: `@Suppress("UNCHECKED_CAST") return this as Builder<PolicyScope.ThreadLocalOnly>`.
This means a `Builder<PolicyScope.ProcessWideSafe>` silently re-types itself to `Builder<ThreadLocalOnly>`
after a single FS call, defeating the compile-time guarantee that process-wide policies never contain
Landlock filesystem rules. The type parameter is a lie enforced only at the call site; aliased builders
or intermediate variables bypass it entirely. The same hole exists in `plus` composition operators
(Policy.kt:365-370, `combine(...) as Policy<ThreadLocalOnly, Uncompiled>`).

**Resolution note (2026-08-23):** Resolved via copy-on-promotion semantics, per the operator's
"no backwards compatibility constraints" decision. `PolicyBuilder.allowFsRead/allowFsWrite/allowJvmClasspath`
now return a NEW `PolicyBuilder<ThreadLocalOnly>` built by `snapshotAsThreadLocal()` (deep state copy);
the receiver is never re-typed or mutated across the scope boundary. The legacy `Policy.Builder`
wrapper mirrors this: FS methods wrap the promoted internal builder in a fresh typed wrapper —
all five unchecked casts removed. Three discarded-return call sites that relied on same-instance
mutation were converted to reassignment (PolicyTransformer, IterativeProfiler, BillOfBehavior).
Regression tests added in PolicyTest: aliasing (`fs promotion must not alias the original builder`)
and content-typing consistency.

**Needed:**
1. Replace the self-casting pattern with an immutable builder step design: `allowFsRead` should return a
   *new* `Builder<PolicyScope.ThreadLocalOnly>` wrapping a copied internal state (the underlying
   `PolicyBuilder` already accumulates state; make the wrapper immutable or use `withX` copies).
2. Alternatively, encode scope promotion as a typed method that can only be invoked on
   `Builder<ProcessWideSafe>` and returns a genuinely distinct receiver (e.g.
   `fun Builder<ProcessWideSafe>.promoteToThreadLocalWithFs(...)`), keeping variance sound.
3. Add ArchUnit/unit tests asserting: (a) calling `allowFsRead` twice does not alias state,
   (b) the resulting policy's `definition.enforceLandlock` matches the declared phantom scope.
4. Preserve binary compatibility for Java callers where feasible; document any breaking change.

## ❓ Open Questions
1. Should the legacy `Builder` be fixed in place or deprecated fully in favor of `PolicyBuilder` +
   `PolicyLists` DSLs (which do not exhibit this hole)?
2. Is fixing the `plus(ThreadLocalOnly)` unchecked cast in scope here, or should it get its own issue?
