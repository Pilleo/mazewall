---
title: "Migrate remaining installer and execution tests before approved public executor retirement"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer", "cli-demo", "consumers"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
target_symbols:
  - "ContainedExecutors"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Migrate remaining installer and execution tests before approved public executor retirement

**Context:**
**Resolution — 2026-10-12:** Needed steps1–3 fulfilled: exact callers/tests migrated before approved public cutover, acquired native installer/cause/quarantine/end controls retained, internal coroutine wrapper preserved. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-4c2/task-4c2-review.md` (133 PASS/2 original condition skips), `task-4c3/task-4c3-review.md` (34 focused PASS and emitted signature/type absence), and final P617 `task-6c-final/task-6c-final-review.md` Java/stable Kotlin individual negatives on JDK22/25. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md). Earlier WIP/open prose is historical.

Task4c preserves accepted Task1–4b WIP and native/lifecycle controls while retiring approved borrowed public APIs after caller migration.

**Needed:**
1. Classify and migrate exact Task4b test inventory, then verify intended ABI cutover and published consumers.
2. Preserve installer-specific acquired workers, required receipt gating, captured native backends, original causes, registration/quarantine and physical retirement.
3. Remove approved public APIs only after remaining callers migrate; retain internal coroutine dependencies and prove published positive/individual negative consumers on JDK22/25.

**Checkpoint (partial, 12 October 2026):**
- Migrated nine test files: CLI attack execution uses owned runtimes in disposable JVMs; eight core installer/native files use explicit supported installation on their acquired disposable workers.
- Focused checkpoint: 59 tests, zero failures/errors/skips; enforcer formatting, test/integration Detekt, unchanged ABI check and production warning ratchet passed.
- Remaining caller migration, public retirement, emitted absence, published consumer diagnostics and independent review remain open. Exact inventory, source/output custody and receipts are in the operator's Task4c artifact directory; no full build, staging or commit.

**Task4c2 continuation (12 October 2026):**
- Migrated the remaining 17 actual execution/installer/native/concurrency/supervisor test files and updated shared NeedsFreshJvm guidance. Execution contracts use owned runtimes in disposable JVMs; native installer controls retain their acquired workers, required receipts and registration/retirement boundaries. The nine accepted Task4c files remain byte-identical.
- Focused host/native/fresh-JVM selections: 135 tests, zero failures/errors, 133 executed successfully. Two original conditions remain explicit: native CET lock acceptance requires a loader/runtime with SHSTK already enabled; the non-Linux fallback control is inapplicable on this Linux host. No condition, profile, budget, permission or coverage waiver.
- Enforcer KtLint, host/integration Detekt, unchanged ABI and production warning ratchet passed. Original ACT_LOG whitelist policies retain one effective filter; removing WRITE from their explicit allowances does not deny it, so no policy was changed to manufacture a second filter. The original 35-install loop remains.
- A fresh owned NO_NETWORK child requires real socket-library initialization before the original strict worker profile; a real bound-and-closed bootstrap socket performs it. The contained connect attempt, preset and permission-denial assertion remain intact.
- Exact task-only diff, incoming/source/class custody, actual receipts and five remaining references are in the operator's Task4c2 artifact directory. Only ContainedExecutorWrapTest, ContainedExecutorTest and ContainmentDesignSpec retain obsolete callers for Task4c3. Public retirement, emitted absence, publication/consumer cutover and independent operator review remain open; this issue stays open.

**Task4c3 cutover (12 October 2026):**
- Removed the approved public wrap overloads, wrapTyped, single/fixed/cached factories and ContainedExecutor/view after the accepted caller migrations. The final three host files now check emitted absence, owned physical retirement and explicit required acquired-worker installation; a real coroutine suspension/resumption control covers the retained internal dispatcher closure.
- Explicit installation/bootstrap/assessment/diagnostic APIs, custom owned preparation, declared invocation contexts and the internal experimental coroutine machinery remain supported. Current installer KDoc, validation guidance and primary API/design guidance reflect the cutover; historical records retain their original identities.
- New checking publications and individual real Java/stable-Kotlin retired-surface diagnostics use a distinct source-hash-bound WIP coordinate and new strict locks, with positive Callable/fixed-scope/context/custom-prepare controls. Exact source/class/JAR/POM/lock custody and actual outcomes belong to the operator's Task4c3 artifact report; this checkpoint is not final-candidate acceptance.
- The issue stays open for independent operator review and the combined Task6 source/candidate checkpoint. No full build, staging or commit is part of Task4c3.

---
<!-- id: issue-20261012-011748-866-migrate-remaining-installer-and-execution-tests-be  file: issue-20261012-011748-866-migrate-remaining-installer-and-execution-tests-be.md -->
