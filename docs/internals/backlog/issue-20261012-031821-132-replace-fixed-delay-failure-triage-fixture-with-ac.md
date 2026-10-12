---
title: "Replace fixed-delay failure-triage fixture with actual wait-entry handshake"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "buildSrc"
target_modules: []
target_files:
  - "buildSrc/src/test/java/io/mazewall/build/RootVerificationFunctionalTest.java"
target_symbols:
  - "verifyFailureTriage"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace fixed-delay failure-triage fixture with actual wait-entry handshake

**Context:**
Task5c: fixed 1.5-second event delay does not prove production finalizer wait entry; retain real failed Test, production collector and configuration-cache controls.

**Needed:**
1. Bound observation of the acquired fixture finalizer thread in the production CountDownLatch wait and reject handshake timeout visibly.

**Implementation evidence (Task5c, awaiting parent scoped acceptance):**
- Fixture-only service override captures the actual finalizer thread, then delegates to the unchanged production `awaitFailure()`. The withheld serial Test event is released only after that thread is observed `TIMED_WAITING` with both production `FailureEvents.awaitFailure` and `CountDownLatch.await` frames. Acquisition and observation each have a 10-second fixture bound; the production 60-second wait is unchanged.
- First/reused configuration-cache and no-cache builds retain a real failed Test, one-test/one-failure/zero-skip XML, event ordering and genuine five-section collector JSON. The same observer visibly rejects a pre-await marker on the acquired running control-task thread after its 100ms bound.
- `./gradlew -p buildSrc failureTriageTest check --no-configuration-cache --console=plain --max-workers=2` exited 0 and ran both existing executable suites plus plugin validation. A direct fixture CLI run exited 1 with both failed Test and handshake-timeout task, while the production collector completed successfully.
- Exact source, graph/argv/log/exit receipts, task diff and custody are in the operator-owned `ROOT/.superpowers/sdd/2026-10-11-integration-contract-completion/task-5c/` report package. The issue remains open for the parent review path.

---
<!-- id: issue-20261012-031821-132-replace-fixed-delay-failure-triage-fixture-with-ac  file: issue-20261012-031821-132-replace-fixed-delay-failure-triage-fixture-with-ac.md -->
