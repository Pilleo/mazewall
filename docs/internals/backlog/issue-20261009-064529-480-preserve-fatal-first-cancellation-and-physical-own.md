---
title: "Preserve fatal-first cancellation and physical ownership in native test harness cleanup"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
target_symbols:
  - "IsolatedProcessTester"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve fatal-first cancellation and physical ownership in native test harness cleanup

**Context:**
Read-only hard-Detekt diagnosis finds source-supported lifecycle gaps: ordinary isolated wait failure can hide late fatal cleanup error; JVM floor ignores termination result after shutdownNow/worker join and treats every IOException as expected including potential EPERM/EACCES; interrupted container command may mask cleanup failures, omit descendant end proof and flow into candidate fallback. These must not be concealed by lint-only renaming/suppression or test pressure reduction.

**Needed:**
1. Use meaningful deterministic owned-resource regressions for ordinary-primary/late-fatal priority, interruption-preserving attempted cleanup and cancellation/permission classification; preserve native workload counts/sleeps/assertions/skips and existing physical cleanup budgets, avoid public API/policy changes, aggregate errors fatal-first and prove bounded owner termination or explicit incomplete failure.

## Monitored implementation and independent review — 2026-10-09

Private commit `7bfb232a718e95cf7f2123d9a045aa18a61e9f9b` has meaningful
late-fatal/cancellation RED and focused GREEN, actual acquired CONNECT denial
exception-identity controls, and zero hard test-Detekt errors without waivers.
Unchanged private hook passed exit0/273s; this commit is **not integrated**.
Independent review returned **CHANGES_REQUIRED**:

- Container start-failure classification searches the entire message for
  `error=2,`; a non-executable filename containing that text can misclassify an
  actual EACCES failure as absent CLI and permit fallback. Require actual launch
  errno evidence and real owned permission/absent-executable controls.
- Existing floor interruption controls precede Loom/OS-worker acquisition;
  require acquired-owner cancellation/physical-end/interrupt/failure-priority
  controls. Observed loopback timeout does not prove refusal classification.
- Changed reflection runner needs independent exit/unwrap/fatal/fallback controls.

Evidence: coordinator ignored workspace `harness-review-round1.md`, worker11
artifact report and immutable RED/GREEN results. Fresh review-correction scope
retains all original pressure and budgets. Full build remains **FAIL** on separate
prctl executable-mmap EPERM issue20261009-094632-442; no retry or waiver.

Private follow-up `fb56dd8ddc32a4707fd9be67069e897988f0e33b` added genuine
acquired-Loom/OS-owner and reflection controls, resolving review findings2/3.
Focused21lifecycle/16endpoint/9floor/7supervisor/14TierE passed with zero failures,
errors or skips; exactquality exit0/199s, actualprofiling1/1, unchangedhook exit0/347s.
Re-review still rejected integration: redirect-output FileNotFoundException can
carry `error=2,` in its relative filename and masquerade as nested launcherrno.
Worker12 `round3-redirect-red-1` now reproduces both genuine owned permissiondenied
and is-directory failures as incorrect CLI-unavailability. A two-file correction
is active; prior four files frozen, no accepted-control or full-build replay.

---
<!-- id: issue-20261009-064529-480-preserve-fatal-first-cancellation-and-physical-own  file: issue-20261009-064529-480-preserve-fatal-first-cancellation-and-physical-own.md -->
