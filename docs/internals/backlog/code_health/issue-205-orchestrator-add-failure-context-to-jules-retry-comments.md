---
title: "Orchestrator: Add Failure Context to Jules Retry and Completed-Without-PR Comments"
severity: "MEDIUM"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "trivial"
autonomy: "autonomous"
solution_approved: true
chosen_solution: "A"
blast_radius: "low"
reversible: true
---

# 🟡 [Severity: MEDIUM]: Orchestrator: Add Failure Context to Jules Retry and Completed-Without-PR Comments

**Context:**
The orchestrator posts exactly `"Retry"` as an issue comment in two situations: (1) Jules session `status == "failed"` or `"cancelled"`, and (2) Jules session `status == "completed"` but no PR was found. In case 1, Jules has no information about why it failed or what it attempted, making it likely to repeat the same mistake. In case 2, `"Retry"` is actually a wrong signal — Jules completing without a PR often means it decided the issue was already fixed or non-actionable. Retrying blindly can cause an infinite loop of no-op sessions. Both cases are in `AWAITING_PR.execute()` in `OrchestratorStates.kt`.

**Needed:**
1. **For `failed`/`cancelled` retry** (around line 186): append failure context after the `Retry` trigger word:
   ```
   Retry

   ---
   > **Why this retry was triggered:** Your previous session ended with status `{session.status}`.
   > This is automated retry {attempt}/2 by the mazewall orchestrator.
   > If the same issue occurs again, please describe what you attempted and what blocked you
   > before the session ended.
   ```
2. **For completed-without-PR retry** (around line 224): use a different body that explains the situation:
   ```
   Retry

   ---
   > **Why this retry was triggered:** Your previous session completed successfully but did not
   > open a Pull Request. If you determined the issue is already fixed or not actionable,
   > please explain why in a comment instead of silently completing.
   > If you need to make code changes, please open a PR with those changes.
   ```

> **IMPORTANT:** The `Retry` keyword MUST remain the first word on the first line exactly as-is. Jules uses it as a trigger token. All context must follow a blank line and separator.

## Solution Options

### Option A — Inline the two different comment bodies in AWAITING_PR
Replace both `env.commentOnIssue(githubIssueNumber, "Retry")` calls with calls that build the appropriate body string.
**Pros:** Trivial change, no new abstractions needed.
**Cons:** None.
**Risk:** LOW
**Effort:** trivial
**Files changed:** `OrchestratorStates.kt`

---
**Chosen:** Option A

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] Unit test `AwaitingPrStateTest.retryCommentContainsFailureContext`: mock env captures the comment body; assert it starts with `"Retry\n"` and contains the session status.
- [ ] Unit test `retryCommentForCompletedWithoutPrContainsPrGuidance`: assert comment starts with `"Retry\n"` and contains "did not open a Pull Request".
