---
title: "Graceful Fallback for AI Agent Failures"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
---

# 🔴 [Severity: HIGH]: Graceful Fallback for AI Agent Failures

**Context:**
If `agy` / Antigravity execution fails (due to token exhaustion, network errors, rate limits, etc.), the daemon may crash, stall, or misbehave, halting the pipeline.

**Needed:**
Wrap the Antigravity execution block in `OrchestratorDaemon.kt` in a `try-catch` block. If an exception occurs:
1. Log the failure.
2. Send a Telegram notification: `"⚠️ Automated review failed (Token limit or error). Defaulting to manual human review for PR #$finalPrNumber"`.
3. Bypass the automated reviewer approval/rejection logic and immediately enter the manual review waiting loop.
