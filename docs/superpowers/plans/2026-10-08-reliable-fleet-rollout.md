---
document_type: execution_plan
base_revision: d957719f
---

# Reliable Existing Agent Fleet Rollout Implementation Plan

> **For agentic workers:** Use executing-plans for the pilot; fresh scoped worker
> sessions and independent task review follow only after the reliability gate passes.

## Operator override and pilot result (2026-10-08)

The operator subsequently authorized updating OpenCode and selected Luna 6 for
tests. OpenCode was backed up and updated to 1.18.35; isolated probes used
`openai/gpt-6-luna` and OmO5.1.26. This supersedes the original no-upgrade
constraint below without authorizing credential migration or assignment transfer.

The candidate **failed** restart and blocked-permission recovery: child messages
survived a restart but the `bg_*` registry lookup did not, and a pending permission
remained busy past 375 seconds with configured five-minute staleness. An SDK
receiver-binding error prevents stale activity refresh and cancellation. Durable
evidence is in the ignored plan workspace at `fleet-canary/supervisor-pilot-report.md`.
See backlog `issue-20261008-192836-364-require-durable-restart-and-blocked-tool-recovery-`.
Task4 production fleet adoption stays gated; evaluate the existing external
supervisor alternative under Task3's same disposable-repository requirements.

The operator approved that replacement pilot. Agent Orchestrator v0.13.5's
checksum-verified release passed actual Luna6 completion, exact conversation
restart, visible pending-request restart, explicit cancellation, preserved real
fixture commit/untracked state and occupied-branch replacement refusal. A live
Python tool also ceased after explicit interruption. Evidence is retained at
`fleet-canary/ao-profile/pilot-report.md`. An injected provider HTTP503 remained
active for45s and recovered after explicit interruption/model switching;
automatic bounded failure/fallback still needs verification. These results
qualify monitored recovery primitives, not the full unattended adoption gate.

The operator subsequently selected OpenCode V1 through Zed as the daily baseline.
V1 binary/config/MCP/model checks are restored and verified; V2 remains a separate
experiment. The proposed continuation sequence is
`2026-10-09-opencode-v1-zed-continuation.md`. It retains the unattended recovery
gate and requires an explicit monitored-mode/ownership ruling before new writers.

**Goal:** Replace unbounded blocking agent handoffs with an existing supervised
background-task system, proven recoverable before restarting MazeWall writers.

**Architecture:** Pilot the OpenCode edition of OmO in an isolated configuration,
using existing Git worktrees/checkpoints as durable task state. Keep the repository's
single assignment/review authority and serial native lane. No custom fleet framework;
no global upgrade or credential migration as part of this plan.

**Tech Stack:** Installed OpenCode1.18.34; candidate oh-my-opencode5.1.26, source
2025cb14a24f5217825d93e9ead69bc25f31cd0a; Git; OpenCode session/events API.

**Spec:** Operator-approved recovery-first protocol and incident facts in
`.superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/progress.md`.

## Binding constraints

- Preserve every worktree, source change, staged file, commit and checkpoint.
- No installer may migrate provider keys or alter credential environment; operator owns auth.
- No automatic merges/pushes/publication, no duplicate writer per worktree.
- Native builds/tests remain serial and fresh; task-manager timeouts do not change test budgets.
- Do not resume the 350k-token task12 history; new task context is brief+checkpoint+diff.
- Configuration/schema/package compatibility is checked before active profile changes.

## Task 1: Inventory and choose one compatible existing manager

Files: create pilot inventory/report in the existing ignored plan workspace; read
only selected package/plugin/agent/compaction fields, never dump provider/auth secrets.

- [ ] Record installed OpenCode1.18.34 and exact active plugin versions.
- [ ] Inspect released OmO OpenCode configuration/schema/source at the pinned commit.
- [ ] Check background concurrency, stale/session-gone/sync wait bounds, circuit breaker,
  runtime fallback and persisted task/result behavior against the actual installed API.
- [ ] Record source caveats: teamRunId tasks skip inactivity/TTL checks in inspected poller;
  taskTtlMs is not a universal busy-session wall-clock deadline; fallback is error-driven.
- [ ] Select ordinary supervised background tasks for pilot, with team mode disabled.
  Do not load a second autonomous dispatcher alongside the repository control plane.

## Task 2: Isolated pilot configuration with bounded policies

Files: create a dedicated OpenCode config/profile and OmO pilot config under the ignored
recovery workspace; active global/project configuration stays unchanged during pilot.

- [ ] Pin the candidate plugin version, validate against its actual released schema.
- [ ] Policy targets: two workers maximum, one OpenAI request at a time initially,
  delegation depth1, idle detection5min, first-output bound5min, synchronous wait1min.
  These are pilot targets, not already-enforced guarantees or native-test timeouts.
- [ ] Choose model/category mappings only from configured available providers; fast
  model for mechanical fixes, independent capable reviewer, explicit fallback chain.
- [ ] Fresh context per bounded task; initial brief<=10k tokens, operational session
  target<=80k tokens. Rotate to a new session from durable state before that target.
- [ ] Verify whether the selected manager enforces session/token/wall-clock targets;
  unsupported targets remain a failed pilot requirement, never a prompt-only promise.
- [ ] Restart only the pilot OpenCode process for config loading; no active-session teardown.

## Task 3: Executable failure/recovery canary

Files: create a disposable tiny repository and owned canary logs/task manifest.
Never exercise failure injection against the MazeWall repository or operator files.

- [ ] Launch a worker that creates an untracked file and scoped commit, then completes;
  require persisted result before coordinator completion notification.
- [ ] Inject a provider failure; verify bounded failure reporting and configured fallback.
- [ ] Inject a deliberately blocked tool; verify coordinator remains responsive and
  terminal state arrives within configured bound. Inactivity alone is not termination proof.
- [ ] Disconnect the coordinator/client and restart; recover exact task/session/worktree
  without replaying the completed commit or discarding the untracked file.
- [ ] Hold the writer/tool alive during cancellation; require replacement writer refusal
  until the old session and modifying tools have actually ceased.
- [ ] Confirm no permission prompt or pending tool can remain silent indefinitely;
  surface actionable state with task/session/tool identifiers.
- [ ] Record each command, observed latency, statuses, retained files/commits and failure logs.

Gate: every canary requirement passes. A failure holds the fleet; no production writers.

## Task 4: Restart only a small reliable fleet

- [ ] One coding task and one independent reviewer first, each in its existing/private tree.
- [ ] Finish the specific symlink/.. filesystem-resolution regression using a fresh brief;
  do not redesign the scanner or carry its previous conversation.
- [ ] On accepted pilot and first task, allow at most two disjoint writers; serial integration.
- [ ] Revalidate existing checkpoints against Git and logs before trusting earlier test claims.
- [ ] Continue final combined opt-in build/native/container/candidate gates once the
  verification blocker is accepted; retain all failures rather than weaken gates.

## Existing alternatives and fallback decision

OpenCode V2 has native background subagents, but plugin/server APIs change and current
V1 custom tools need compatibility validation; migration is a separate pilot.
Orchestrator.inc supplies external worker/worktree/session supervision including OpenCode;
it must replace, not compete with, the existing dispatch authority if selected.
KDCO background-agents/workspace/worktree are explicitly retired V1 projects; the former
background agent plugin is read-only and cannot supervise this write-capable fleet.

If OmO cannot pass blocked-tool and exclusive-writer recovery canaries, do not patch
around it with another unbounded loop. Evaluate external supervision or V2 in an isolated
profile and keep a single writer until one existing stack passes those same tests.
