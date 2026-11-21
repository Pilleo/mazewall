---
title: "Bound generated Portal encoding before allocation and traversal"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRequestWriter.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCodecTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalConfiguredTransportBoundsTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "docs/internals/designs/portal/local-service-isolation-design.md"
  - "docs/internals/designs/portal/portal-developer-experience.md"
target_symbols:
  - "PortalStubGenerator"
  - "PortalCodec"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound generated Portal encoding before allocation and traversal

**Context:**
CG06: a generated dispatcher encoded 16385 list elements rejected by its own reader and allocated an 8388613-byte string reply beyond the 8 MiB frame limit. Current concatenation checks transport bounds only after encoding and repeated temporary allocations.

**Needed:**
1. Add failing generated encode/decode controls for 16,385-element collections and replies beyond 8 MiB, plus configured smaller limits, nested collections and checked-arithmetic overflow.
2. Introduce the bounded writer already proposed for P03 (planned `PortalRequestWriter.kt`, or the equivalent minimal helper alongside `PortalCodec`). Use it for current synchronous request and worker-reply encoding before per-element/field allocation or buffer growth.
3. Enforce finite byte, collection-count and traversal budgets with checked arithmetic. Apply the applicable transport limit before emitting a payload, including variable-width UTF-8 and nested values; count scratch buffers/copies in the peak allocation budget.
4. Preserve valid wire bytes and service-error handling. Make the writer reusable by submit-time snapshots while P03 retains admission/deadline ownership and P10 retains measurement-driven optimization.

**Scope and dependencies:**
- Follows [CG01](resolved/issue-20261103-095305-625-make-accepted-portal-byte-array-and-list-contracts.md). [Review CG06](../reviews/2026-11-03-portal-codegen-hardening.md#cg06--enforce-bounds-while-encoding) records actual oversized encoded arrays.
- The writer path is planned, not an existing source file. Reuse a matching writer if P03 implementation has already introduced one; do not create competing encoders.
- This bounds library-controlled encoding work and allocations. A malicious user getter/iterator can itself block or allocate; no purity or arbitrary callback memory bound is implied.

**Acceptance:**
- Generated senders never produce collection counts rejected by their own readers. Exact-limit values round-trip; limit-plus-one fails before growth or visiting further elements beyond the finite budget.
- Oversized strings/arrays and aggregate nested values fail without constructing the oversized encoded representation. Tests use finite counters/small budgets rather than relying on an actual OOM.
- Request encoding failure precedes guest dispatch; reply encoding failure remains bounded and follows existing completed-service/error semantics.
- Golden valid payloads, IDs and supported Java/Kotlin contracts remain compatible. Peak-memory evidence includes temporary copies rather than only final payload size.

**Verification:**
- [Junior implementation walkthrough: CG06](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#cg06--bounded-encoding-before-allocation) explains small-budget/counting fixtures, shared writer integration and pre-allocation evidence.
- Run `PortalCodecTest`, `PortalConfiguredTransportBoundsTest` and compiler-backed request/reply boundary cases in `PortalCodegenFunctionalTest`.
- Run affected module suites and `./gradlew build`, backlog and whitespace checks at implementation completion. A speedup benchmark is not a substitute for bounded-allocation evidence.

---
## Repository-review revalidation — 2026-11-04

[CG06/PCG-06/PRT-07](../reviews/2026-11-04-repository-review-findings.md#cg06) freshly emits
a 16,385-element list and string response above the 8 MiB cap before rejection. Use one bounded
writer for requests/replies, checked aggregate arithmetic and small-budget/counting controls;
measure scratch/copy peaks without intentional OOM. P03 consumes correctness; P10 optimizes
that same implementation, and P01 includes real encoding/validation cost.

---
<!-- id: issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-  file: issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-.md -->
