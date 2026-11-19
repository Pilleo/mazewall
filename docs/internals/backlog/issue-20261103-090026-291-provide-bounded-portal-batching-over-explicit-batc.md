---
title: "Provide bounded Portal batching over explicit batch contracts"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-085927-123-compare-warm-portal-calls-with-executor-and-persis"
  - "issue-20261103-090025-757-generate-typed-portal-operations-with-bounded-subm"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/main/java/io/mazewall/portal/PreparedPortalOperation.java"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBatches.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBatchOptions.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalBatchCursor.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalBatchesTest.kt"
  - "tests/consumers/portal-plugin"
  - "benchmarks/portal-comparison"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PreparedPortalOperation"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Provide bounded Portal batching over explicit batch contracts

**Context:**
Portal developer-experience plan P06: amortize IPC by sending bounded chunks through ordinary generated List request/response methods and expose a bounded result cursor instead of eagerly submitting an unbounded input.

**Needed:**
1. Implement a lazy, ordered closeable cursor over an explicit `List<A> -> List<R>` prepared service method. Each chunk is one ordinary generated RPC.
2. Bound input lookahead, encoded chunk size and all retained invocation handles. Use one cursor-creation deadline through submission/waiting and each configured call cap.
3. Stop pulling on failure/cancellation, retain unfinished cleanup handles for close retry and compare real batching against scalar calls.

**Plan and dependencies:**
- [Implementation plan P06](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [batch contract](../designs/portal/portal-developer-experience.md#6-batching-over-ordinary-contracts).
- Requires P03's generated snapshots/deadline hook and P01's comparison for completion evidence.

**Acceptance:**
- With batchSize=3/maxInFlight=2, a held response permits at most six elements pulled. Results stay in input order even when later chunks finish first.
- The window counts unconsumed chunks and pending physical ends after result delivery; fast results with delayed cleanup cannot accumulate unbounded retained handles.
- Check positive bounds, arithmetic overflow, collection/payload limits and exact response cardinality. Iterator exceptions/oversized chunks stop further input traversal.
- Cursor close requests cancellation and waits under one finite close budget, preserves primary failures, surfaces retirement failures and retains incomplete owners for retry.
- Count worker method entries to prove one RPC per chunk. Document partial chunk effects and absence of automatic replay/rollback; host iterator work is not forcibly preempted.

**Verification:**
- [Junior implementation walkthrough: P06](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p06--finite-batching-not-a-list-of-scalar-rpcs) describes counting-iterator/held-end fixtures, ordered results and finite cleanup slots.
- Run `./gradlew :portals:runtime:test --tests '*PortalBatchesTest'`, a generated native batch consumer and P01 scalar/batch comparisons at equal work and limits.
- Run `./gradlew build`, backlog validation and whitespace checks at completion.

---
<!-- id: issue-20261103-090026-291-provide-bounded-portal-batching-over-explicit-batc  file: issue-20261103-090026-291-provide-bounded-portal-batching-over-explicit-batc.md -->
