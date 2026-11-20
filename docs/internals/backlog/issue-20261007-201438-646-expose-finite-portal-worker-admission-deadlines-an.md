---
title: "Expose finite Portal worker admission deadlines and transport limits"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["portals:runtime"]
target_files: 
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Expose finite Portal worker admission deadlines and transport limits

**Context:**
Approved P2 requires host-configured finite capacity, operation/startup/close budgets and bounded request/response/capability data. Forward one immutable limits object through runtime, broker and channel; preserve the existing absolute RPC deadline and NOT_STARTED rejection before I/O.

**Needed:**
1. Verify configured limits reject overload/oversized inputs before transport and response headers before allocation.

**Implementation evidence (pending repository review):**
- One immutable `PortalRuntimeLimits` replaces duplicated broker pool/timeouts and is captured by runtime configuration. Stateful singleton capacity stays one.
- Admission rejects overload/oversized inputs as NOT_STARTED before checkout or I/O; response headers reject configured bounds before body allocation. Cleanup keeps its independent physical owners.
- Focused host, real overlap admission and Detekt warning ratchet passed (`run-277eeb2019aa`). A full gate exposed an existing fixture's implicit coupling of close to startup timeout; the fixture now explicitly configures its 100 ms close budget and the late-acquisition retirement regression passed (`run-5cfdec508a66`).
- `./gradlew build` passed in 44 seconds (`run-2871266b9e50`), including the complete real broker integration suite. No warning inventory expansion or assertion weakening.

---
## Repository-review expansion — 2026-11-04

[PRT-04](../reviews/2026-11-04-repository-review-findings.md#prt-04) identifies waits that
escape closeTimeoutMs: minimum five-second maintenance wait and fresh per-worker reap/pump
allowances. Thread one absolute deadline through them, retaining every unfinished owner on
expiry. Test held maintenance and multiple delayed slots, then bounded same-owner retry.
Coordinate with PRT-02 replacement ownership; the existing short-budget late-launch test does
not cover these paths. No complete native retirement proof was produced by source review.

---
<!-- id: issue-20261007-201438-646-expose-finite-portal-worker-admission-deadlines-an  file: issue-20261007-201438-646-expose-finite-portal-worker-admission-deadlines-an.md -->
