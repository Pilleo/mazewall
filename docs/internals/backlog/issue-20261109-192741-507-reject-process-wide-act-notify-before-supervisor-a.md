---
title: "Reject process-wide ACT_NOTIFY before supervisor acquisition"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["platform", "enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/policy/PolicyBuilder.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/policy/PolicyDefinition.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
target_symbols:
  - "PolicyBuilder"
  - "PolicyDefinition"
  - "ContainedExecutors"
open_questions: false
---

# 🟡 [Severity: HIGH]: Reject process-wide ACT_NOTIFY before supervisor acquisition

**Context:**
The source-backed [baseline installer ownership audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-direct-owner-implementation/component11-next-caller-preparation/baseline-installer-ownership-audit.md) (SHA-256 `215dfa44b5f4be1c5c4de285a3eec2e25d9570e63fa0d9c91690d3473ed6a507`) records that `PolicyBuilder.kt:47–60` accepts caller-selected notification actions for `ProcessWideSafe`, and `PolicyDefinition.kt:42–44` includes explicit or default `ACT_NOTIFY` in `hasSupervisedSyscalls`. `ContainedExecutors.kt:416–425` may acquire/spawn the shared supervisor before `applyBpfFilter` rejects this process-wide supervised mode at `:651–653`. This is an unqualified pre-effect acquisition/rollback frontier; no actual native execution, escape, or harmlessness is established. This is distinct from issue 509's static-deny composition scope; retain issue 338 as a broader prepublication rollback dependency without merging causes. Future work should reject process-wide explicit/default notification at the earliest decision point before helper/native effects. Preserve old `open` behavior until a separately reviewed decision.

**Needed:**
1. Trace the earliest process-wide explicit/default notification admission checks through `PolicyBuilder`, `PolicyDefinition` and `ContainedExecutors`; then propose a fail-closed refusal before supervisor acquisition with focused ordering and rollback tests. Do not change behavior until separately reviewed.

---
<!-- id: issue-20261109-192741-507-reject-process-wide-act-notify-before-supervisor-a  file: issue-20261109-192741-507-reject-process-wide-act-notify-before-supervisor-a.md -->
