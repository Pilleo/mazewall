---
title: "Organize adoption guidance around explicit security boundaries"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Organize adoption guidance around explicit security boundaries

**Context:**
Architecture assessment finding 1: in-process synchronous restrictions, process Portals and profiling evidence serve different threat models. A process-wide no-exec baseline does not isolate shared secrets or sibling executors. Evidence: enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/api/execution/OwnedIndependentHelperNativeTest.kt:134-173. Strategic adoption gap, not a newly discovered enforcement bypass.

**Needed:**
1. Publish an operation-first boundary selection guide covering trusted synchronous input processing, separate-memory requirements and observational profiling; validate it with an unfamiliar developer and link the existing negative-boundary tests.

**Acceptance:** The guide distinguishes restrictions from address-space isolation and observations from authorization, covers shared heap/preopened descriptors/independent executors, and lets an unfamiliar developer select the correct boundary for a representative operation. Reuse existing demonstrations; preserve their documented limitations.

---
## Repository-review expansion — 2026-11-04

[DOCS-01](../reviews/2026-11-04-repository-review-findings.md#docs-01) identifies secondary
HotSpot recipes recommending raw NO_EXEC and overstating sibling confinement; use explicit
runtime-safe baselines and exact shared-resource limitations.
[DOCS-02](../reviews/2026-11-04-repository-review-findings.md#docs-02) identifies public-site,
module-tree and architectural-map prose advertising removed borrowed executors. Align with
current owned runtime and separate advanced manual installation; preserve historical migration
records. Also correct IO_URING_PROFILING's leak-detector flag being offered as transport disabling;
require verified framework-specific disabling. Review current API/example/reference consistency;
no new runtime mechanism, fallback or compatibility shim is implied.

---
<!-- id: issue-20261028-230623-718-organize-adoption-guidance-around-explicit-securit  file: issue-20261028-230623-718-organize-adoption-guidance-around-explicit-securit.md -->
