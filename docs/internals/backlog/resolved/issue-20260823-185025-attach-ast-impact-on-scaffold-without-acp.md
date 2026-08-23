---
title: "Attach AST impact on scaffold without ACP"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/NewBacklogIssue.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "NewBacklogIssue"
  - "FilesystemImpactScanner"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Attach AST impact on scaffold without ACP

**Context:**
Jules has no ACP. It consumes the markdown issue as the whole spec. Today `NewBacklogIssue` only runs `FilesystemImpactScanner` when `has_side_effects==true` or `--clarify`. A typical Jules-bound `./scripts/new_backlog_issue.sh --non-interactive --file … --symbol …` therefore ships FILL Context/Needed and **no** Investigation/Side effects. Callers in other modules are invisible to the worker. Deterministic scan must run on every scaffold that has files or symbols, including `--dry-run`, with or without `--clarify`.

**Needed:**
1. After `scaffold(write=false)`, always `scan` + `withImpactHits` when `impactSymbols` is non-empty (not only when the human set `--side-effects`).
2. `--clarify` still runs, but must see the same hits (do not skip the scan if ACP is missing).
3. `--no-side-effects` remains an explicit override: still record investigation “AST found N hits while has_side_effects=false” when N>0; do not flip the flag to true.
4. Test: temp repo with `Cache.kt` + `UsesCache.kt`, scaffold `--file Cache.kt --symbol Cache` without clarify → markdown contains `## Side effects` and the profiler path. A second case `--no-side-effects` keeps `has_side_effects: false` but still lists the hit under Investigation.
5. No new Gradle deps. No ACP in these tests.

## Investigation
- `NewBacklogIssue` else-branch only scans when `hasSideEffects == true`.
- Jules/orchestrator workers never run `--clarify`.

## Important details
- Origin files omitted from hits (existing scanner).
- FILL Context/Needed is still allowed without ACP; impact sections must not depend on a model.

## Side effects
- Jules and `--non-interactive` writes gain Investigation/Side effects from the disk scan even without `--clarify`.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-185025  file: issue-20260823-185025-attach-ast-impact-on-scaffold-without-acp.md -->
