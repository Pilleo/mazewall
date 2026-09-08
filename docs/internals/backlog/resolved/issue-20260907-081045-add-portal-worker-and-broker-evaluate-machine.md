---
title: "Add portal worker and broker evaluate machine"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260907-080845"
component: "docs"
target_modules:
  - ":portal"
  - ":portal-worker"
target_files:
  - "portal/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols:
  - "ProcessBroker"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "8480b6af-eaf8-4117-8c23-cdf9e0abeb75"
paperclip_identifier: "MAZ-1182"
---

# 🟡 [Severity: MEDIUM]: Add portal worker and broker evaluate machine

**Context:**
Portal worker `serve` and broker pool `call` are send/receive loops with no `fun evaluate`. Request/response/error, granted FDs, and shutdown are implicit in the loop. That is the same review problem as supervisor USER_NOTIF before it had a route machine.

**Needed:**
1. Add sealed portal RPC state + events and `evaluate(state, event)` for worker `serve` and broker `call`.
2. Keep SCM_RIGHTS / frame bytes in the interpreter; evaluate stays pure.
3. Unit-test accept, request, error, and shutdown transitions without a live worker process.
4. Run `:portal:test` and `:portal-worker:test`.

## Side effects
- serve and call loops become interpreters over evaluate rather than inline send/receive state

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `PortalBrokerCallMachine` and `PortalWorkerMachine` provide
sealed RPC lifecycle states, events, effects, and pure evaluators. The broker call and worker
serve loops interpret frame/SCM_RIGHTS I/O around those transitions. `./gradlew :portal:test
--tests io.mazewall.portal.PortalBrokerCallMachineTest :portal-worker:test --tests
io.mazewall.portal.worker.PortalWorkerMachineTest` passed.

<!-- id: issue-20260907-081045  file: issue-20260907-081045-add-portal-worker-and-broker-evaluate-machine.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
