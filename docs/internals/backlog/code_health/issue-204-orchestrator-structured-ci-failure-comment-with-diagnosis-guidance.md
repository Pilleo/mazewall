---
title: "Orchestrator: Structured CI Failure Comment with Diagnosis Guidance for Jules"
severity: "MEDIUM"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "small"
autonomy: "autonomous"
solution_approved: true
chosen_solution: "A"
blast_radius: "low"
reversible: true
---

# 🟡 [Severity: MEDIUM]: Orchestrator: Structured CI Failure Comment with Diagnosis Guidance for Jules

**Context:**
When CI fails on a Jules PR, `CI_RUNNING` posts a comment with the raw log dump and the single line `@jules Please review the failing logs and fix the implementation:`. There is no indication of which specific check failed (unit test vs Jacoco threshold vs SpotBugs vs Detekt vs ktlint), no guidance on how to approach diagnosis, and no reminders of the project constraints (e.g., do not lower Jacoco thresholds, do not suppress SpotBugs warnings, do not catch exceptions to silence failures). Jules receives a wall of log text with no structure and frequently either retries the identical change or applies a hack to silence the test.

**Needed:**
Replace the CI failure comment construction in `CI_RUNNING` (around line 292–301 of `OrchestratorStates.kt`) with a structured template:

1. **Parse the failed log** to extract a summary line: look for lines matching `FAILED`, `> Task :...FAILED`, `BUILD FAILED`, `tests failed`, or Jacoco/SpotBugs violation patterns. Emit the first 5 matching lines as "Failed Check Summary".
2. **Truncate the full log** to the first 3000 characters (or extract only lines containing `ERROR`, `FAILED`, `Exception`, `at `) to reduce noise.
3. **Inject a diagnosis guidance block** with project-specific rules: do not lower thresholds, do not add `@SuppressFBWarnings`, do not catch exceptions to silence failures, do not modify test expectations.
4. Include the commit SHA in the header line.

## Solution Options

### Option A — Inline string construction with log parsing in CI_RUNNING
Extract log parsing into a private helper `fun parseCiFailureSummary(log: String): String` in a new `CiLogParser` object, then use it in the comment construction.
**Pros:** Clean separation of concerns. `CiLogParser` is unit-testable.
**Cons:** New file needed.
**Risk:** LOW
**Effort:** small
**Files changed:** new `CiLogParser.kt`, `OrchestratorStates.kt`

### Option B — Inline everything in CI_RUNNING
Put the log parsing and template directly in `CI_RUNNING`.
**Pros:** No new file.
**Cons:** State logic becomes harder to test and read.
**Risk:** LOW
**Effort:** trivial

---
**Chosen:** Option A
**Rationale:** `CiLogParser` is independently unit-testable and keeps the state machine readable.

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] `CiLogParserTest` covers: (a) extracts FAILED task lines, (b) truncates to 3000 chars, (c) detects Jacoco threshold failure pattern.
- [ ] New comment no longer contains raw unsummarized log when log exceeds 3000 chars.
- [ ] Diagnosis guidance block always present in failure comments.

**Implementation Hints:**
- Key file to modify: `OrchestratorStates.kt`, `CI_RUNNING.execute()`, around the `FAILURE` branch.
- New file: `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/CiLogParser.kt`.
- Log lines indicating Jacoco failure: `Rule violated for bundle`, `instructions covered ratio`.
- Log lines indicating SpotBugs: `SpotBugs rule violations were found`.
