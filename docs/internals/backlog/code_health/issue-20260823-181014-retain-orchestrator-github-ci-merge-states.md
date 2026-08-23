---
title: "Keep orchestrator GitHub/CI/PR/merge states until a dispatcher replacement exists"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/AGENTS.md"
  - "tools/orchestrator/README.md"
  - "plan.md"
effort: "small"
autonomy: "autonomous"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Keep orchestrator GitHub/CI/PR/merge states until a dispatcher replacement exists

**Context:**
Paperclip heartbeats wake an adapter and record runs. They do not poll GitHub PR creation, CI checks, Jules session failure retries, structured `VERDICT:` review, serial merge, or superseded-generation on conflict. Those are `AwaitingPrState`, `CiRunningState`, `AwaitingReviewState`, `AwaitingMergeState`, `CreateGenerationState`, `ResolveTaskState` in `OrchestratorStates.kt`. The hybrid plan (`plan.md`) sketches “Reviewer & Verification Agent” and “git lifecycle hook” as Python/Telegram. Live glue does none of the CI/PR loop. Deleting or gutting those states because “we moved to Paperclip” would drop the only working merge queue.

**Needed:**
1. Add an invariant to `tools/orchestrator/AGENTS.md`: do not remove GitHub issue/PR/CI/review/merge/generation states unless a replacement dispatcher has tests covering the same transitions (PR appears, CI fail comments once per SHA, review verdict, merge, conflict → new generation).
2. Correct `plan.md` Phase 3–4: Paperclip is board + multi-adapter wakeup; GitHub/CI/merge stays in `:tools:orchestrator` (or a future dedicated dispatcher module), not in `paperclip_telegram_bridge.py`.
3. README: one sentence that Jules is *an* adapter, not the control plane, and that CI/merge remains orchestrator-owned.
4. No functional state-machine rewrite in this issue.

---

**Verification:** Docs match the hybrid split. `./gradlew :tools:orchestrator:checkBacklog`.
