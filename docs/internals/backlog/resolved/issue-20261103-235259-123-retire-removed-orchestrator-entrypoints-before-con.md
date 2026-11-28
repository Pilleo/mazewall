---
title: "Retire removed orchestrator entrypoints before control-plane effects"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "tooling"
target_modules: []
target_files:
  - "scripts/run_paperclip_loop.sh"
target_symbols:
  - "run_paperclip_loop"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retire removed orchestrator entrypoints before control-plane effects

**Context:**
Repository-wide review, 2026-11-04: [TOOL-02](../../reviews/2026-11-04-repository-review-findings.md#tool-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category / confidence / boundary:** confirmed current-source defect and authoritative-workflow gap; high; operator/repository tooling.

**Evidence:**

- `scripts/run_orchestrator.sh:8–16` still launches `:tools:orchestrator:run`; background mode reports a PID without waiting for backend startup.
- `scripts/run_paperclip_loop.sh:42–45` invokes the live, write-capable ingester **before** `59–60` invokes the absent `:tools:orchestrator:supervisor` backend.
- `scripts/new_backlog_issue.sh:33` still forwards legacy scaffolding into that backend. The Codanna wrapper `scripts/code_atlas.sh` has been removed.
- `AGENTS.md:52–55` requires the removed orchestrator review/approval path and points at its missing README. `AGENTS.md:100–102` recommends the external ADK route.
- The current recovery plan explicitly records intentional removal and says it neither identifies nor authorizes a replacement control plane (`docs/superpowers/plans/2026-11-02-profiler-delivery-critical-path.md:32–41`).

**Trigger / consequence:** following the still-present Paperclip loop command can create/update board issues and local `paperclip_issue_id` fields, then fail at a supervisor that cannot exist in this checkout. Following the other wrappers produces dead task paths; background startup can look successful until the log is inspected. This is not an argument to restore the deleted module or to infer that Paperclip is now authorized to dispatch/merge.

**Smallest complete correction:** explicitly retire or fail-fast the removed-backend launchers **before** any ingestion/effect, direct already-supported local discovery/backlog operations to the external ADK wrapper, and record the operator-selected review/approval boundary. Do not invent a replacement publication or retained-recovery launcher.

**Regression:** harmless stub-command tests must prove a removed-backend entry returns a nonzero, explanatory result before Kotlin/HTTP invocation, Gradle, or a misleading background-start success. Validate all documented active script targets exist.

**Tracking / dependencies:** stale/expanded state of resolved `issue-20260823-212901-document-run-orchestrator-as-the-only-production-loop.md` and `issue-20260823-181014-retain-orchestrator-github-ci-merge-states.md`, plus the explicit removal clarification above. The old resolution is not present-day runtime evidence. NEW removal fallout; coordinate documentation wording with the docs lead. Retirement is preferable to fixing an unused legacy ingest subsystem; if ingestion remains supported, TOOL-03/04 are prerequisites.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

The retired launcher and scaffolder entrypoints now return nonzero with guidance to the supported local ADK. The disposable shell fixture failed before the correction because the Paperclip launcher invoked the ingestion stub. It now verifies foreground, background, preview, ordinary loop, and scaffolding calls launch no tooling or ingestion.

---
<!-- id: issue-20261103-235259-123-retire-removed-orchestrator-entrypoints-before-con  file: issue-20261103-235259-123-retire-removed-orchestrator-entrypoints-before-con.md -->
