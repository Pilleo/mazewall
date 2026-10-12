---
title: "Compile canonical owned-runtime and coverage-preserving profile recipes"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "README.md"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Compile canonical owned-runtime and coverage-preserving profile recipes

**Context:**
Primary recipes advertise borrowed workers, nonexistent public incomplete overrides and overstrong diagnostic/JIT guarantees; Task1 replaces guidance and verifies actual snippets against publications.

**Needed:**
1. Correct the four primary guides and add stable external actual-snippet coverage with incomplete-result refusal.

## Task 1 implementation checkpoint (11 October 2026)

The four primary guides now use explicitly bootstrapped owned platform workers,
Callable bodies, finite admission and honest cancellation/physical-end/drain rules.
Result-level profiling conversion/export preserves coverage and refuses incomplete
results; runtime selection precedes restrictive composition. Permission-exception
inference is distinct from observed events, and a Portal-launching host is distinct
from the no-process-creation example. The applied historical record retains producer
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`; eight historical YAML trailing spaces and
one dangling primary README article reference are removed.

`tests/consumers/doc-snippets` extracts 15 actual runtime fences and compiles them
against local Maven publications using stable Kotlin 2.4.20 on real JDK22/JDK25.
Its runtime control round-trips genuinely absent coverage and requires refusal from
the actual conversion snippet, direct policy conversion and Portal document path.
Ten core fences also compile against `0.1.0-candidate.dc6f7bf4`; retained original
recipes reproduce wrong-package imports and nonexistent public `allowIncomplete`.

Evidence and unstaged WIP custody:
`.superpowers/sdd/2026-10-11-integration-contract-completion/task-1-report.md` and
`task-1-review.diff` in the repository root's ignored workspace. This issue stays
open pending scoped review; native profile→install→post-install-JIT proof and Task2+
implementation remain separately owned.

---
<!-- id: issue-20261011-160846-071-compile-canonical-owned-runtime-and-coverage-prese  file: issue-20261011-160846-071-compile-canonical-owned-runtime-and-coverage-prese.md -->
