---
title: "Make loop-driven Checker use cheap module tests; OCI only when needs_kernel"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":tools:orchestrator"
target_files:
  - ".agents/skills/loop_driven_development/SKILL.md"
  - ".agents/profiles/checker.md"
  - ".agents/profiles/maker.md"
effort: "small"
autonomy: "autonomous"
open_questions: false
paperclip_issue_id: 96dbcce1-8434-4602-a787-338b1c29c7dd
---

# 🟡 [Severity: MEDIUM]: Make loop-driven Checker use cheap module tests; OCI only when needs_kernel

**Context:**
Loop-driven development plus `checker.md` tells the Checker to run `./scripts/run_tests.sh` (full nested-seccomp OCI) and Jacoco thresholds on every Maker step. Maker/Checker/Triager profiles are not launched by any runner; a single agent role-plays all three and then pays full-suite cost. Host `./gradlew :module:test --tests …` is the inner loop that actually fits TDD. Kernel tests are required only when the work package says so (seccomp install, Landlock, USER_NOTIF). Current skill text fights `issue-20260823-181001` cheap-vs-merge split and makes autonomous runs expensive.

**Needed:**
1. Rewrite Checker commands in `loop_driven_development/SKILL.md` and `.agents/profiles/checker.md`:
   - Inner: `./gradlew :<module>:compileKotlin` and `./gradlew :<module>:test --tests <TestClass>` from the work package.
   - Module gate: `./gradlew :<module>:test` (host unit) before review.
   - Kernel: `./gradlew integrationTest` / `./scripts/run_tests.sh` only if `needs_kernel: true`.
   - Merge: `./gradlew build` once.
2. State that Maker/Checker/Triager are separate subagents **only if the runner spawns them**; a single agent should still follow cheap-first commands.
3. Triager profile: keep `build/triage_report.json` / hs_err / dmesg guidance; do not tell Checker to always produce a full OCI dump.
4. Do not change Gradle task wiring in this issue.

---

**Verification:** Skills/profiles no longer list `./scripts/run_tests.sh` as the default Checker action. `./gradlew :tools:orchestrator:checkBacklog` passes.
