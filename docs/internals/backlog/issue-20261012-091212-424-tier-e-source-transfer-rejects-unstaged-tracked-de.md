---
title: "Tier E source transfer rejects unstaged tracked deletions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tierE/tools/TierERootfulSourceTransfer.kt"
target_symbols:
  - "TierERootfulSourceTransfer"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Tier E source transfer rejects unstaged tracked deletions

**Context:**
Task7b actual rootful gate failed before workloads: git ls-files --cached emits intentionally deleted ContainedExecutor.kt and tar cannot stat it. Preserve tracked modified and untracked scoped source and fail-closed tar handling.

**Needed:**
1. Add real temporary Git repository regression with an unstaged tracked deletion, then omit absent worktree entries from the tar input without changing declared scope.

**Task7b local evidence (2026-10-12):**
- The original aggregate exited 1 before workloads because tar received an intentionally deleted index entry.
- The temporary Git/tar regression reproduced that failure. The initial JVM-path missing-entry filter passed that control but was subsequently rejected by scoped review for lossy raw-filename handling (see fixround1 below).
- Focused regression plus installed asset admission: 13 tests, zero failures/errors/skips. Fresh unchanged-budget rootful gate: 45 native CTests, both phase verdicts from six fresh samples, and all five required installed TierE cases passed; terminal COMPLETE, policy-ineligible.
- Actual command/source/image/native/cleanup receipts are in ROOT `.superpowers/sdd/2026-10-11-integration-contract-completion/task-7b/`. Keep this issue open pending scoped independent review and the later coherent source checkpoint; no source commit or final publication was performed.

**Task7b fixround1:**
- Existing non-UTF-8 source and an unreadable tracked parent both reproduced silent-success archive omissions in the reviewed helper. The raw Git `--deleted` probe also demonstrated exit-zero permission diagnostics; those diagnostics must fail closed before subtracting deletion records.
- The helper now subtracts checked raw NUL-delimited Git deletion records by byte identity and writes retained bytes directly to tar. No pathname UTF-8 conversion, broad missing-path filter, new source backend or unsafe Path reflection is used.
- Five real temporary Git/tar tests pass with zero failures/errors/skips: the original unstaged deletion, raw tracked/untracked/newline names plus raw deletion and dangling link, actual parent/file permission failures, and tar destination I/O failure. Ktlint passed. Optional `detektTooling` failed with 87 errors in 36 unchanged files and no helper errors; no rules/baselines were waived.
- Actual worktree archive byte/content custody and all 68 unchanged native asset files were checked separately. Earlier native feature receipts remain bound to their original sources; expensive native gates were not replayed for this source-transfer-only correction. Independent review remains required.

---
<!-- id: issue-20261012-091212-424-tier-e-source-transfer-rejects-unstaged-tracked-de  file: issue-20261012-091212-424-tier-e-source-transfer-rejects-unstaged-tracked-de.md -->
