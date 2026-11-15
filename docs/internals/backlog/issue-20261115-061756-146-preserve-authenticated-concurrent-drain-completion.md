---
title: "Preserve authenticated concurrent drain completion after closure"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/ConfinedHelperRuntimeDrainTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols:
  - "ManagedRuntime"
  - "ConfinedHelperRuntimeDrainTest"
open_questions: false
---

# Preserve authenticated concurrent drain completion after closure

**Context:**
CI run 43680748266 reported `:enforcer:test` at 1,634 tests, 2 skipped, and 1 failed. The failing second thread throws `IllegalStateException` from `ManagedRuntime.awaitConfinedSweep` at `ManagedRuntime.kt:424`; the test then hides that participant failure behind a `future.get` timeout at `ConfinedHelperRuntimeDrainTest.kt:6866`. The observed ordering is that the first drain publishes `CLOSED` while a joined follower later expects `DRAINING`. Related checks around `ManagedRuntime.kt:433` and `mayAwaitHelperSweep` at line 2381 need a coherent authenticated identity and closed-observation check-to-reservation guard. The timeout is not the underlying failure, and increasing it would not resolve the ordering bug.

The concurrency contract and bounded repair belong in [`containment-design.md`](../../designs/enforcer/containment-design.md). Preserve permits, control/preparation behavior, and unknown/history semantics. Retain the foreign-identity negative controls and ensure there are no effects after `CLOSED`. The bug involves real concurrent private-helper ownership retirement, not thread-name scheduling. This issue adds no public API, dependencies, or native activation and does not change Native Live guards.

**Needed:**

1. Reproduce the existing failing ordering in the original construction test before changing implementation. Preserve the original participant frontiers and prove the test drives actual public participants and private-helper ownership retirement.
2. Repair completion/exception propagation so a joined participant can observe the authenticated drain's terminal outcome after the owner publishes `CLOSED`, while preserving the closed-frontier check-to-reservation guard and foreign-identity rejection.
3. Add deterministic barrier tests for the original owner, joined public participants, exception propagation, and foreign-identity negatives. Assert permits/control/preparation invariants, retained unknown/history behavior, and zero post-close effects. Keep existing timeout bounds unchanged.
4. Verify the focused drain tests, complete `ConfinedHelperRuntimeDrainTest` class, host `:enforcer:test`, and fresh CI after the bounded fix. Preserve the original XML and new receipts; do not hide participant exceptions behind a later timeout.

Keep this issue OPEN until the actual concurrent ownership case, negative controls, regression suite, and fresh CI pass. Follow the accepted root plan and test-fixed3 patch evidence without expanding this scope.

---
<!-- id: issue-20261115-061756-146-preserve-authenticated-concurrent-drain-completion  file: issue-20261115-061756-146-preserve-authenticated-concurrent-drain-completion.md -->

**Focused verification update (2026-11-15):** The original public CI failure is retained; deterministic private original-object tests recorded RED at all three named closed-frontier checks before the bounded repair. They do not claim to force the original public scheduling instant. The focused six-case XML then passed 6/6 with no skips, failures, or errors, including the public concurrent-participant case, foreign ownership negatives, and retained unknown checks. Existing retained-history tests also passed in the complete class. The complete `ConfinedHelperRuntimeDrainTest` XML reports 209 tests with zero skips, failures, or errors. Its enclosing full-class Gradle attempt failed only at the test-source ktlint check on one newline-before-expression rule; the literal newline-only correction passed the focused test-lint task in 45 seconds. Earlier style failures remain retained. Independent Terra review found no blockers.

The production change is limited to identity-first observation of `CLOSED` and reservation gating; it preserves existing timeout bounds, public API, permits/control/preparation checks, and unknown/history semantics, and adds no post-close release or sweep effects. The private closed-frontier tests do not force a public follower to pause at the exact live midpoint, so they are not claimed as a deterministic reproduction of that scheduling instant. No kernel/native activation, Native Live qualification, or broader Tier E qualification follows. Full-incremental proposal 003 later failed its Detekt warning ratchet after 8m01s with two new findings in `ManagedRuntime` (`ReturnCount` and `CyclomaticComplexMethod`); source/index were unchanged and the runtime retired. Keep this issue OPEN: full proposal 004 and fresh required CI remain pending. Issue 752 remains UNKNOWN, broader Tier E remains paused, and Live guards remain closed.

Workspace-only result receipts (not portable inputs): focused six-case XML SHA-256 `a4b085c38665463b45c7f6af0c2d189a6bb2adca8d8e5dfe24fa2c9ebba8bb00`; complete class XML SHA-256 `a7665798fe9deee760dc0b386a683ffb3788399f06e238fc2ba807d730671c6a`; newline-only test-lint log SHA-256 `36f4b8953bca19c09efb34bd0ba2e3a70cdda55cd8ebd4fb4cc83edf4b67aa3a`. CI run 43680748266 remains the original red evidence.

**Latest bounded verification (2026-11-15):** The structural correction keeps the same `Future`/deadline wait and failure catches inside the existing `RuntimeOperations` method and removes the extra outer method. It adds no API, deadline, or effects. Static proposal 001's 31-function `TooManyFunctions` finding and four main-source style failures are retained. Final static proposal 002 completed successfully in 3m54s with the enforcer warning ratchet showing zero added and 18 removed findings; main lint came from cache and test lint was up-to-date, so this is not an absolute warning-free claim. The subsequent complete class test run passed 209 tests with 0 skips/failures/errors in 10m26s; 5 of 27 tasks executed and 22 were up-to-date. The runtime retired and source/index comparisons were unchanged.

Current production source SHA-256 is `1278bec4a7b93fb39cd36f5b17f66e635cf51b9872b13a2b255918420009d813`; test source SHA-256 is `b5f3128c0a56f2392b0d112b6a5a87d3f58c6e83f1e3b64a7d29a6796a32a6f0`. Static build log SHA-256 is `fccbbd2ab9bfcebcf8316056243c35c47f5024695989d43b1c9963a21781511f`; actual 209-test XML SHA-256 is `b9282984597f9bde6dbe43c503fd2bdc81a74934c1d7c50a475af108a7108e3d`. Full proposal 003 log SHA-256 is `b605a322edc9866094cc06307a521bb300a0b9df2a0e2041276ddfeb8d34a646`. These are ignored workspace-only receipts. Fresh CI remains required. Preserve issue 752 UNKNOWN, paused Tier E, and closed Live guards.

**Current full-gate status (2026-11-15):** Full-incremental proposal 004 passed locally in 44m12s (248 tasks: 100 executed, 148 up-to-date; configuration cache stored), with source/index unchanged and runtime retired. The actual enforcer unit task reported 1,639 tests / 2 skipped / 0 failures or errors; integration reported 244 / 2 / 0; fresh-JVM integration reported 120 / 1 / 0. This is incremental evidence, not an all-fresh run. An independent inventory counted 678 XML files / 4,749 tests / 25 skipped / 0 failures or errors and includes up-to-date results. Full proposal 003 remains a recorded failed predecessor; proposal 004 is the passing current local gate. Keep this issue OPEN pending fresh required remote CI, including both required kernel lanes. The private frontier tests still do not force a public follower to pause at the exact live midpoint. Issue 752 remains UNKNOWN; Tier E remains paused, Live guards remain closed, and the NVD HTTP 503 is not a clean scan.

Workspace-only receipt hashes: full proposal 004 log `cfc22e226fad7287bb15fdbff6cda8f7dc30e1411aa9757b923eb2560fdde657`; source manifest `c70116c46d8976a455758fe9607b6b28c5b9fcd3079ec2889c6ed49e93cd5abb`; independent inventory audit `c614966ab560fb95be4bb54a30a315e701fe106269644332a6c8d7a0952b2b3e`.
