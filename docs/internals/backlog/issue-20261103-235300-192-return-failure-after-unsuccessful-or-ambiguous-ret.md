---
title: "Return failure after unsuccessful or ambiguous retained ingestion writes"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: ["issue-20261103-235259-123-retire-removed-orchestrator-entrypoints-before-con"]
component: "tooling"
target_modules: []
target_files:
  - "scripts/paperclip_backlog_sync.kts"
target_symbols:
  - "sync"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Return failure after unsuccessful or ambiguous retained ingestion writes

**Context:**
Repository-wide review, 2026-11-04: [TOOL-04](../reviews/2026-11-04-repository-review-findings.md#tool-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category / confidence / boundary:** confirmed source defect; high; optional Paperclip ingest, conditional on retaining it.

**Evidence:** `scripts/paperclip_backlog_sync.kts:519–531` increments `failed` and continues for a rejected POST or an accepted POST whose returned ID cannot be read. `541–553` logs PATCH status but neither rejects non-2xx nor counts it. `563–569,618` print totals, release the lock and return normally, regardless of `failed`. The shell launcher's `set -e` therefore does not stop it after these failures (`scripts/run_paperclip_loop.sh:21,42–45,59–60`).

**Trigger / consequence:** the server returns a non-2xx POST/PATCH, or a successful POST has an unrecognized response shape. The invocation can exit zero despite incomplete sync; a supported successor loop could proceed on incomplete dependency/board state. In the present checkout the next supervisor step fails separately (TOOL-02), but direct script callers still receive false success. Successful partial POSTs and local writebacks already performed are not rolled back.

**Smallest complete correction:** aggregate failed effects and return nonzero after cleanup; count/reject PATCH failures too. Preserve partial-success identifiers/evidence and avoid blind replay of an ambiguously successful creation.

**Regression:** fake HTTP boundary returns POST 500, PATCH 403 and POST 201 with missing ID; each run must fail visibly, retain legitimate earlier receipts, and prevent downstream dispatch. No live server is needed.

**Tracking / dependencies:** **NEW** after scoped searches; related resolved ingest item `issue-20260823-181011` calls for fail-closed/tested ingestion, but does not document this failure-status defect. Depends on TOOL-02's retention decision. Whole JSON parser correctness/idempotent recovery after ambiguous POST is an escalation area, not certified here.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235300-192-return-failure-after-unsuccessful-or-ambiguous-ret  file: issue-20261103-235300-192-return-failure-after-unsuccessful-or-ambiguous-ret.md -->
