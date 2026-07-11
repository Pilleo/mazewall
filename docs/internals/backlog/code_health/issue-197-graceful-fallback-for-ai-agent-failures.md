---
title: "Graceful Fallback for AI Agent Failures"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Graceful Fallback for AI Agent Failures

**Context:**
**Context:**
If `agy` / Antigravity execution fails (due to token exhaustion, network errors, rate limits, etc.), the daemon may crash, stall, or misbehave, halting the pipeline.

**Needed:**
Wrap the Antigravity execution block in `OrchestratorDaemon.kt` in a `try-catch` block. If an exception occurs:
1. Log the failure.
2. Send a Telegram notification: `"⚠️ Automated review failed (Token limit or error). Defaulting to manual human review for PR #$finalPrNumber"`.
3. Bypass the automated reviewer approval/rejection logic and immediately enter the manual review waiting loop.

**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
