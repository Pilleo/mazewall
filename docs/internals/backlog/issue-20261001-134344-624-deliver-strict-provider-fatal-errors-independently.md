---
title: "Deliver strict provider fatal errors independently of stack lookups"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-design.md"
  - "docs/plans/2026-10-01-agentless-hotspot-epoch-plan.md"
  - "experiments/hotspot-agentless/results/identity-investigation.md"
target_symbols:
  - "HandleTrace"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Deliver strict provider fatal errors independently of stack lookups

**Context:**
Second review found that pinned HandleTrace only logs ReportTraceEvent errors and the collector observes lookup errors only for admitted enrichment jobs. Storing EPERM/EACCES, regression or conflicts on a rejected definition does not terminate a session when its event is lost, skipped or never looked up.

**Needed:**
1. Specify bounded session-owned fatal notification and retained error delivery, including a test with no lookup and no waiter.
2. Deliver permission, regression and identity-conflict errors independently of event intake, skip decisions and pending enrichment; preserve the error in the final session result after cancellation.
3. Replace first-error-only read retention: a later EPERM/EACCES must remain observable even if an earlier read failed differently.

**Evidence:** provider `processmanager/manager.go:385–386` logs reporter failure.
Collector `internal/capture/collector.go:168` joins runtime/enrichment/callback/
target-watch errors, without a provider-fatal source. The isolated
`TestReviewLedgerMustRetainLaterPermissionFailure` fails after EOF then EACCES:
the proposed ledger retains EOF alone (`build/epoch-review.r6i0tgok/probe.log`).

**Closure evidence:** rejected definition and conflicting duplicate with no
matching lookup must still terminate strict capture and retain their typed cause;
notification/retention is bounded and does not deadlock during close/cancellation.

**Implemented; independent review pending:** strict configuration requires a
session fatal owner. The handler sends decoder/reporter failures directly to
that owner; the collector retains bounded first/EPERM/EACCES causes and cancels
independently of event jobs. Tests cover absent-owner refusal, native failure
with no lookup, later EACCES after earlier failure and collector cancellation.
The focused red/green regressions and complete host race/upstream gate pass.

---
<!-- id: issue-20261001-134344-624-deliver-strict-provider-fatal-errors-independently  file: issue-20261001-134344-624-deliver-strict-provider-fatal-errors-independently.md -->
