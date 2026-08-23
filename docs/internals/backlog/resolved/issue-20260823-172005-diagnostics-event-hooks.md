---
title: "Operator Observability: Structured Diagnostics Events Beyond JUL Strings"
severity: "LOW"
status: "resolved"
priority: low
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorDaemonManager.kt"
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟡 [Severity: LOW]: Operator Observability — Structured Diagnostics Events

**Context:** All operational signaling today is `java.util.logging` strings plus the one-shot
`Platform.diagnose()` text dump. The most safety-relevant lifecycle events are effectively invisible
to operators running services: supervisor daemon unexpected exit currently ends in
`Runtime.halt(1)` (fail-closed, correct) with SEVERE logs — but there is no programmatic hook to
alert, dump state, or attempt orderly shutdown first. Similarly, fallback bypasses
(WARN_AND_BYPASS), Landlock application, CET arm/lock outcomes, and self-verification results (once
issue-20260823-172003 lands) are log-only.

**Resolution (2026-08-23):** Implemented `io.mazewall.enforcer.diagnostics.MazewallEvents`:
sealed `Event` types (DaemonExited, FallbackEngaged, LandlockApplied, CetOutcome,
SelfVerificationResult) with a CopyOnWriteArrayList listener registry, SAM
`DiagnosticEventListener`, and swallow-by-default listener isolation (operator/test seam
`failOnListenerError` to propagate). Wired at: daemon unexpected exit (event emitted BEFORE the
fail-closed `halt(1)` — open question answered: halt remains non-negotiable, the SPI is
observability-only), WARN_AND_BYPASS engagement, Landlock application (scope + ABI), CET arm
outcome, and self-verification result. JUL remains the default sink; ArchUnit generic-catch rule
whitelists MazewallEvents with justification. Tests: MazewallEventsTest.

**Needed:**
1. Introduce a tiny event SPI: `MazewallEvents { fun onEvent(event: DiagnosticEvent) }` with a
   default no-op; events are sealed data objects (DaemonExited(exitCode, lastLogLines),
   FallbackEngaged(behavior, reason), LandlockApplied(scope, abiVersion), CetOutcome,
   SelfVerificationResult) — no dependency on any metrics library.
2. Emit at existing decision points (the `onUnexpectedExit` hook already exists — generalize it).
3. JUL adapters remain the default sink so current behavior is unchanged.
4. Document a recipe: wiring events into Micrometer/OTel in the presentation docs.

