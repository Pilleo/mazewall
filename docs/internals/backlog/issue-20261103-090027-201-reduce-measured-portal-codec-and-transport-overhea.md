---
title: "Reduce measured Portal codec and transport overhead without changing wire semantics"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis"
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
  - "issue-20261103-090026-291-provide-bounded-portal-batching-over-explicit-batc"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalIo.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalCodec.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRequestWriter.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalPayload.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalChannel.kt"
  - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalCodecTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalFrameTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalConfiguredTransportBoundsTest.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalOwnershipDeadlineTest.kt"
  - "portals/runtime/src/integrationTest"
  - "benchmarks/portal-comparison"
  - "docs/internals/designs/enforcer/process-portal-design.md"
target_symbols:
  - "PortalIo"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reduce measured Portal codec and transport overhead without changing wire semantics

**Context:**
Portal developer-experience plan P10: optimize only hot paths established by the comparable Portal/API benchmark, starting with redundant encoding/copies and poll-before-I/O overhead while preserving ownership and cancellation.

**Needed:**
1. Profile P01's equal-work matrix after generated submission/batching land, separating encoding, allocation/copy, host dispatch, syscalls and worker/transport aggregate time.
2. Select one demonstrated dominant cost and add a differential correctness regression before changing it. Reuse the shared CG06 bounded writer consumed by P03; consider copy/write/poll changes only with evidence.
3. Retain focused changes only with reproducible benefit and preserved public/wire/lifecycle semantics; record the same before/after matrix.

**Plan and dependencies:**
- [Implementation plan P10](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [measurement/optimization contract](../designs/portal/portal-developer-experience.md#9-evidence-and-optimization-policy).
- Requires P01, P03 and P06. The existing worker-packaging issue stays independent; identify the exact package version in measurements.
- [CG06](issue-20261103-095309-136-bound-generated-portal-encoding-before-allocation-.md) owns corrective encoding bounds before P03; this issue measures and optimizes that implementation while retaining the hardening review's wire/ownership guarantees.

**Acceptance:**
- Codec round trips retain identical frame bytes, method IDs, nullability and supported nested values. Malformed lengths/counts reject before unbounded allocation.
- I/O controls force short writes, EAGAIN, EINTR, partial EOF, timeout before/after progress and cancellation, retaining the same failure classification and owner retirement.
- Public defensive copies, byte admission, one absolute deadline, partial-frame terminality and confined native arenas remain valid.
- Only nonblocking sockets may attempt I/O before polling; interruption/close/deadline checks still precede attempts. No shared mutable buffer pool or speculative protocol rewrite.
- Report comparable warmed latency/throughput/allocation/memory observations and investigate tail regressions. If no safe measured improvement is available, retain the evidence and avoid an unsupported optimization claim.

**Verification:**
- [Junior implementation walkthrough: P10](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p10--one-measured-optimization-at-a-time) defines one-cost-center experiments, differential regressions and fair before/after evidence.
- Run focused codec/frame/bounds/deadline tests and affected native integrations, then P01's identical fork/workload/concurrency matrix.
- Run `./gradlew build`, backlog validation and whitespace checks. Keep each additional measured cost-center change independently reviewable.

---
<!-- id: issue-20261103-090027-201-reduce-measured-portal-codec-and-transport-overhea  file: issue-20261103-090027-201-reduce-measured-portal-codec-and-transport-overhea.md -->
