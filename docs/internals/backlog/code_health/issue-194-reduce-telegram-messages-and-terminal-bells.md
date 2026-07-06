---
title: "Reduce Telegram Notification and Terminal Bell Spam on Status Changes"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Reduce Telegram Notification and Terminal Bell Spam on Status Changes

**Context:**
The Autonomous Backlog Orchestrator daemon currently sends Telegram messages (`bot?.sendMessage(...)`) and rings the terminal bell (`ringTerminalBell(...)`) on almost every state transition and status change (e.g. startup, task selection, recovery, PR build success, requesting reviews, and local resolution updates). This creates significant notification fatigue and terminal noise.

**Needed:**
We need to audit and restrict Telegram notifications and terminal bells. They should **only** occur when explicit human/operator intervention or input is required. 

Specifically:
1. **Silence Status Changes:**
   Transition logs, build updates (like build passing, automated review requests), and normal daemon progression must be printed silently to the terminal log and **not** trigger Telegram messages or terminal bells.
2. **Limit to Actionable Events:**
   Only alert the user (via Telegram and terminal bells) when:
   * A task is pending manual approval/input (`SELECT_TASK` state requires confirmation).
   * A build failure on the PR occurs (`❌ Build failed on PR...`).
   * A PR conflict requires manual resolution (`⚠️ PR has conflicts!`).
   * A task completes and the PR is ready for manual review and merge (`🟢 Jules reviewed PR! Ready for final manual review and merge`).
   * A critical error occurs that stops execution or loops indefinitely.

### Target Areas:
* **`OrchestratorDaemon.kt`:** Refactor calls to `bot?.sendMessage` and `ringTerminalBell` to ensure they are omitted during normal, automated daemon cycles (such as starting a task, recovery from local issues, and starting automated review steps). Replace these noisy alerts with standard log print statements (`println` or `logger`).
