---
title: "Require durable restart and blocked-tool recovery before fleet supervisor adoption"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules: []
target_files:
  - "docs/superpowers/plans/2026-10-08-reliable-fleet-rollout.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Require durable restart and blocked-tool recovery before fleet supervisor adoption

**Context:**
Isolated OpenCode 1.18.35 and OmO 5.1.26 Luna 6 canaries retain child messages after restart but lose the bg task registry; a pending bash permission remained busy for 375.65 seconds despite configured 300000ms staleness. Source detaches client.session.get, and manager logs this._client receiver failures then skips stale cancellation. Production fleet gate failed.

**Needed:**
1. Verify existing configurable automatic deadline/fallback policies for the isolated Agent Orchestrator candidate, preserving one assignment authority. Adopt production writers only after the complete recovery gate passes.

**Replacement pilot update:**
- Operator approved an isolated Agent Orchestrator pilot. Verified Linux release
  v0.13.5 artifact, commit `c95ae361eee48d33c2f443c6d2fe69c445f548a8`, SHA-256
  `336d8aef2b812f43bcaf88a979a29ab22f5268c773d68fe35cc5a708f5648716`.
- Actual Luna6 ACP completion and exact session/conversation/messages/turns
  survived daemon restarts. Pending permission stayed visible as `waiting_input`
  with the same request ID; explicit interruption settled in ~0.51s.
- `run-aa54e1f05fc7`: fixture commit plus untracked evidence survived restart;
  duplicate occupied branch was refused with HTTP409.
- `run-520cf080f202`: replacement refused while an actual Python tool was alive;
  explicit interruption ended the original PID/starttime within ~0.52s.
- `run-2cff655861fd`: five real loopback HTTP503 requests in45.38s, with no terminal
  turn in that window. Explicit interruption/model switch back to real Luna6
  recovered. Automatic deadline/fallback is not yet proven;45s is not proof of
  failure at a five-minute bound.
- Historical approval remained pending after interruption; resolving it marked
  only that record resolved, with no observed restart of the aborted command
  (`run-5b78d4d97066`, `run-405208ed4c97`).
- Durable replacement report: ignored plan workspace
  `fleet-canary/ao-profile/pilot-report.md`. Monitored primitives pass; the full
  unattended adoption gate remains open.

**Observed evidence:**
- `run-7267a2dd3e20`: coordinator `ses_ee30c4e99ffeADT06YCopx1IfD`, child
  `ses_ee30c3b0fffe4WcLvBR6p8yV0x`, task `bg_0f7c0801`. Child completed on
  `openai/gpt-6-luna`; after a server restart child messages were identical but
  `background_output` returned `Task not found: bg_0f7c0801`.
- `run-6269c7be2a57`: child `ses_ee30a1f89ffeFZSNgmGwDiSoWK`, task
  `bg_03dd90a3`, permission `per_11cf5e629001yetK3Zhlatf6cQ`. Harmless print
  command never received approval. API remained responsive for 186 observations;
  at 375.65 seconds no terminal notification had arrived and permission remained
  pending. Probe explicitly aborted its child and reaped its server.
- Released bundle `getSessionActivityFromClient` calls detached
  `const sessionGet = client.session.get; await sessionGet(...)`. Logs report
  `undefined is not an object (evaluating 'this._client')`; the stale poller
  continues when activity refresh returns `unavailable`.
- Pilot logs/configs and canary script are retained under the ignored
  `.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/fleet-canary/`.
  All owned pilot listener ports closed; no process remained in the disposable
  repository. Provider-fallback, modifying-tool cessation, commit/untracked-file
  recovery and exclusive-writer canaries remain unproven.

---
<!-- id: issue-20261008-192836-364-require-durable-restart-and-blocked-tool-recovery-  file: issue-20261008-192836-364-require-durable-restart-and-blocked-tool-recovery-.md -->
