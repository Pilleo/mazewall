---
title: "Add automated PR diff review guardrail via Vibe in HybridSupervisor"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/CiWatch.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/VibeReviewer.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/VibeReviewTest.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
paperclip_issue_id: bc4e39ae-5f3c-492a-8c30-60b874adaa9d
paperclip_identifier: MAZ-735
---

# 🟡 [Severity: MEDIUM]: Add automated PR diff review guardrail via Vibe in HybridSupervisor

**Context:**
The proposed change adds Vibe-based automated PR diff review to the orchestrator module by extending `CiWatch` and `HybridSupervisor`. Since `CiWatch.tick()` is the central PR processing loop and `HybridSupervisor.runForever()` orchestrates the lifecycle, modifying these components enables proactive quality and safety checks on PRs created by autonomous cloud agents (such as Jules) before they can be merged.

**Needed:**
1. In `CiWatch.kt`: Add `triggerVibeDiffReview` and integrate into `tick()`.
2. In `HybridSupervisor.kt`: Add `enableVibeGuardrail` config and Vibe subprocess lifecycle management.
3. Create `VibeReviewer.kt` for encapsulation if logic exceeds inline thresholds.
4. Add tests in `:tools:orchestrator` for Vibe review triggers and fail-closed blocking behavior.
5. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/CiWatch.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt
- CiWatch.tick(issues: List<PaperclipIssue>)
- GhCheckSource.fetch(repo:String, prNumber: Int)
- IssueSignals.comment(issueId: String, body: String)
- HybridSupervisor.runForever(totalDispatchBudget: Int?)
- CiWatch.tick(List<PaperclipIssue>)
- HybridSupervisor.runForever(Int?)
- HybridSupervisor.tick(Int, String?)
- GhCheckSource.fetch(String, Int)
- IssueSignals.comment(String, String)
- PaperclipIssue

## Important details
- Fail closed: Vibe review must blockPR merge on CRITICAL/HIGHseverity findings; nosilent bypass under anycircumstance
- Deterministic:Vibe analysis mustbe idempotent for identical diffs (no ACP)
- Integration: Must reuseexisting GhCheckSourceand IssueSignals; no direct GitHub API duplication
- Resourcesafety: Vibesubprocesses must becleaned up to preventleaks in HybridSupervisoru2019slong-running process
- Performance:Vibe analysis timeoutmust be bounded toavoid stalling theorchestrator loop
- ABI impact: Newpublic method triggerVibeDiffReview andmodified CiWatch.tick behavioraffect callers
- Configuration impact: New environment variablealters HybridSupervisorstartup parsing
- Runtime impact:Vibe subprocess addslatency and resource overheadto PR loop
- Module isolation: Changes contained within:tools:orchestrator; noimpact on :enforcer/:profiler
- Fail-closed:Vibe failuresmust block PR merge, never silently bypass

## Side effects
- Modifies CiWatch.tick()behavior to perform Vibe diff review onPR events
- Adds Vibe subprocess orchestrationto HybridSupervisorrun loop
- Introduces additionallatency in PR processingdue to Vibeanalysis
- CiWatch.tick() now invokesVibe review,adding network and subprocessoverhead to PR processing
- HybridSupervisor managesVibe lifecycle, introducing new resourcecleanup responsibilities
- New configuration flagchanges supervisor behavior atstartup
- Vibe analysis failureshalt PR progression perfail-closed requirement

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-113008  file: issue-20260827-113008-add-automated-pr-diff-review-guardrail-via-vibe-in-hybridsup.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
