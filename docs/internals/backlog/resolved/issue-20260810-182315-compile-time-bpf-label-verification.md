---
title: "Compile-Time BPF Label Verification (Type-Safe AST)"
severity: "ENHANCEMENT"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":platform"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/BpfProgram.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🟢 [Severity: ENHANCEMENT]: Compile-Time BPF Label Verification (Type-Safe AST)

**Context:**
Jump targets already used `BpfLabel` (issue 044) instead of raw strings, but `BpfLabel` was a public name-only value class. Callers could construct `BpfLabel("allow_read")` or reuse a token from another builder; two builders that called `nextLabel("x")` produced equal tokens.

**Resolution:**
- `BpfLabel` has a private constructor. Only `BpfBuilder.nextLabel` / `createLabel` can mint tokens.
- Identity is `(builder owner, serial)`, not the debug name.
- `jumpIfEqual` / `jumpIfSet` / `jumpIfGreaterThan` / `mark` reject labels that were not issued by the same builder (state transitions share `labelOwner` via `continueAs()`).
- Unmarked labels still fail at `build()` (`Unknown label`).
- `BpfBuilderCoverageTest` covers foreign-builder jump/mark and name collisions.

**Needed:** (done)
